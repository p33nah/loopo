package com.p33nah.loopo.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Isolated debug-only manual and microphone harness. This activity never executes the plan. */
internal class FunctionGemmaPilotActivity : ComponentActivity() {
	private var input by mutableStateOf("")
	private var status by mutableStateOf("Pronto")
	private var busy by mutableStateOf(false)
	private val history = mutableStateListOf<PilotInteraction>()
	private var engine: Engine? = null
	private var activeOperation: Job? = null
	private val resourceCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private lateinit var wakeDetector: VoskWakeDetector
	private var pendingPermissionRequest = false

	private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted && pendingPermissionRequest) transcribeOnePhrase()
		else status = "Permesso microfono negato."
		pendingPermissionRequest = false
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		status = "Bundle: ${File(filesDir, MODEL_RELATIVE_PATH).absolutePath}"
		wakeDetector = VoskWakeDetector(this)
		setContent {
			MaterialTheme {
				Surface(modifier = Modifier.fillMaxSize()) {
					Column(
						modifier = Modifier.fillMaxSize().padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp),
					) {
						Text("FunctionGemma · solo debug", style = MaterialTheme.typography.titleLarge)
						Text("Nessun comando viene inviato al player. I risultati restano in memoria finché questa schermata è aperta.")
						OutlinedTextField(
							value = input,
							onValueChange = { input = it },
							label = { Text("Frase italiana") },
							modifier = Modifier.fillMaxWidth(),
							maxLines = 2,
						)
						Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Button(enabled = !busy && input.isNotBlank(), onClick = { runInference(input.trim()) }) {
								Text("Interpreta testo")
							}
							Button(enabled = !busy, onClick = ::requestTranscription) {
								Text("Tocca e parla")
							}
						}
						Text(status, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
						Text("Storico interazioni", style = MaterialTheme.typography.titleMedium)
						val historyListState = rememberLazyListState()
						LaunchedEffect(history.size) {
							if (history.isNotEmpty()) historyListState.animateScrollToItem(history.lastIndex)
						}
						SelectionContainer {
							LazyColumn(
								modifier = Modifier.weight(1f).fillMaxWidth(),
								state = historyListState,
								verticalArrangement = Arrangement.spacedBy(8.dp),
							) {
								items(history, key = PilotInteraction::number) { record ->
									Text(record.render(), modifier = Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace)
								}
							}
						}
					}
				}
			}
		}

		if (savedInstanceState == null) {
			intent.getStringExtra(EXTRA_TRANSCRIPT)?.takeIf(String::isNotBlank)?.let { phrase ->
				input = phrase
				runInference(phrase)
			}
		}
	}

	private fun requestTranscription() {
		if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
			transcribeOnePhrase()
		} else {
			pendingPermissionRequest = true
			microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
		}
	}

	private fun transcribeOnePhrase() {
		busy = true
		status = "In ascolto… parla una frase e fai una pausa."
		activeOperation = lifecycleScope.launch {
			val result = wakeDetector.transcribe { status = "Microfono attivo…" }
			when (result) {
				is TranscriptResult.Text -> {
					input = result.value
					busy = false
					runInference(result.value)
				}
				TranscriptResult.TimedOut -> finishMicrophoneFailure("Timeout trascrizione Vosk")
				TranscriptResult.PermissionDenied -> finishMicrophoneFailure("Permesso microfono negato")
				TranscriptResult.Unavailable -> finishMicrophoneFailure("Vosk non disponibile")
				TranscriptResult.Unrecognized -> finishMicrophoneFailure("Vosk non ha riconosciuto la frase")
				TranscriptResult.Failed -> finishMicrophoneFailure("Errore trascrizione Vosk")
				TranscriptResult.Cancelled -> finishMicrophoneFailure("Trascrizione annullata")
			}
		}
	}

	private fun finishMicrophoneFailure(message: String) {
		history += PilotInteraction(history.size + 1, transcript = null, parserPlan = "non disponibile", failure = message)
		busy = false
		status = message
	}

	private fun runInference(transcript: String) {
		if (busy) return
		busy = true
		status = "Inizializzazione / inferenza CPU…"
		val record = PilotInteraction(history.size + 1, transcript = transcript, parserPlan = "in attesa")
		history += record
		val parserPlan = ItalianVoiceCommandParser.parse(transcript)?.let(::formatPlan) ?: "rifiuto (nessun piano valido)"
		val mutableRecord = record.copy(parserPlan = parserPlan)
		history[history.lastIndex] = mutableRecord
		activeOperation = lifecycleScope.launch {
			try {
				val result = withContext(Dispatchers.IO) { infer(transcript) }
				history[history.lastIndex] = mutableRecord.copy(
					rawText = result.rawText,
					calls = result.calls,
					validatedPlan = when (val interpretation = FunctionGemmaVoiceCommandInterpreter.interpret(result.calls)) {
						is VoiceInterpretation.Accepted -> formatPlan(interpretation.plan)
						is VoiceInterpretation.Rejected -> "rifiuto (${interpretation.reason})"
					},
					inferenceMillis = result.inferenceMillis,
				)
				status = "Completato · ${result.inferenceMillis} ms"
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: Exception) {
				history[history.lastIndex] = mutableRecord.copy(failure = failure.toString())
				status = "Fallimento: ${failure.message ?: failure.javaClass.simpleName}"
			} catch (failure: LinkageError) {
				history[history.lastIndex] = mutableRecord.copy(failure = failure.toString())
				status = "Fallimento runtime nativo: ${failure.message ?: failure.javaClass.simpleName}"
			} finally {
				busy = false
			}
		}
	}

	private fun infer(transcript: String): InferenceResult {
		val model = File(filesDir, MODEL_RELATIVE_PATH)
		check(model.isFile && model.length() > 0L) { "Bundle mancante o vuoto: ${model.absolutePath}" }
		val activeEngine = engine ?: Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(), cacheDir = cacheDir.absolutePath))
			.also { it.initialize(); engine = it }
		activeEngine.createConversation(
			ConversationConfig(
				systemInstruction = Contents.of(FUNCTIONGEMMA_SYSTEM_INSTRUCTION),
				tools = FUNCTIONGEMMA_TOOLS.map(::tool),
				automaticToolCalling = false,
				samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
				maxOutputToken = 64,
			),
		).use { conversation ->
			val startedAt = System.nanoTime()
			val reply = conversation.sendMessage(transcript)
			val inferenceMillis = (System.nanoTime() - startedAt) / 1_000_000L
			val calls = reply.toolCalls.map { VoiceModelToolCall(it.name, it.arguments) }
			val rawText = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("\n") { it.text }
			return InferenceResult(rawText, calls, inferenceMillis)
		}
	}

	override fun onDestroy() {
		val operation = activeOperation
		activeOperation = null
		operation?.cancel()
		resourceCleanupScope.launch {
			operation?.join()
			wakeDetector.close()
			engine?.close()
			engine = null
			resourceCleanupScope.cancel()
		}
		super.onDestroy()
	}

	private data class InferenceResult(val rawText: String, val calls: List<VoiceModelToolCall>, val inferenceMillis: Long)
	private data class PilotInteraction(
		val number: Int,
		val transcript: String?,
		val parserPlan: String,
		val rawText: String? = null,
		val calls: List<VoiceModelToolCall>? = null,
		val validatedPlan: String? = null,
		val inferenceMillis: Long? = null,
		val failure: String? = null,
	) {
		fun render(): String = buildString {
			append("#$number\nTranscript: ${transcript ?: "(none)"}\nParser plan: $parserPlan")
			rawText?.let { append("\nModel raw text: $it") }
			calls?.let { calls ->
				append("\nRaw structured calls: ")
				append(calls.joinToString(prefix = "[", postfix = "]") { call ->
					val arguments = JSONObject()
					call.arguments.forEach { (name, value) -> arguments.put(name, value ?: JSONObject.NULL) }
					"${call.name}$arguments"
				})
			}
			validatedPlan?.let { append("\nValidated model plan: $it") }
			inferenceMillis?.let { append("\nInference: $it ms") }
			failure?.let { append("\nFailure: $it") }
		}
	}

	companion object {
		const val EXTRA_TRANSCRIPT = "com.p33nah.loopo.functiongemma.TRANSCRIPT"
		private const val MODEL_RELATIVE_PATH = "functiongemma/model.litertlm"
		private const val FUNCTIONGEMMA_SYSTEM_INSTRUCTION =
			"You are a model that can do function calling with the following functions. " +
			"Interpreta un comando vocale italiano per Loopo. Usa soltanto gli strumenti dichiarati. " +
			"È ammessa una sola azione oppure un cambio di velocità seguito da riprendi o ricomincia. " +
			"Per richieste ambigue, negate o non supportate, non chiamare strumenti e rispondi: Comando non riconosciuto."
		private val FUNCTIONGEMMA_TOOLS = listOf(
			pilotTool("resume", "Riprendi la riproduzione dal punto corrente."),
			pilotTool("restart", "Ricomincia la riproduzione dall'inizio del loop o del brano."),
			pilotTool("stay_paused", "Lascia la riproduzione in pausa."),
			pilotTool("set_rate", "Imposta la velocità assoluta di riproduzione senza avviare la traccia.", "percent", "Velocità intera in percentuale da 25 a 200.", 25, 200),
			pilotTool("adjust_rate", "Aumenta o riduce la velocità corrente senza avviare la traccia.", "delta_percent", "Variazione intera in punti percentuali da -175 a 175, escluso zero.", -175, 175, rejectZero = true),
		)

		private fun formatPlan(plan: VoicePlan): String = plan.actions.joinToString(" → ") { action ->
			when (action) {
				VoiceAction.Resume -> "resume"
				VoiceAction.Restart -> "restart"
				VoiceAction.StayPaused -> "stay_paused"
				is VoiceAction.SetRate -> "set_rate(percent=${action.rate.percent})"
				is VoiceAction.AdjustRate -> "adjust_rate(delta_percent=${action.delta.value})"
			}
		}

		private fun pilotTool(
			name: String,
			description: String,
			argument: String? = null,
			argumentDescription: String? = null,
			minimum: Int? = null,
			maximum: Int? = null,
			rejectZero: Boolean = false,
		): OpenApiTool = object : OpenApiTool {
			override fun getToolDescriptionJsonString(): String {
				val properties = JSONObject()
				if (argument != null) {
					val value = JSONObject().put("type", "integer").put("description", argumentDescription)
						.put("minimum", minimum).put("maximum", maximum)
					if (rejectZero) value.put("not", JSONObject().put("const", 0))
					properties.put(argument, value)
				}
				val parameters = JSONObject().put("type", "object").put("properties", properties)
					.put("required", if (argument == null) org.json.JSONArray() else org.json.JSONArray().put(argument))
					.put("additionalProperties", false)
				return JSONObject().put("name", name).put("description", description)
					.put("parameters", parameters).toString()
			}

			override fun execute(paramsJsonString: String): String =
				"{\"error\":\"tool execution disabled in Loopo pilot\"}"
		}
	}
}
