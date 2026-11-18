package com.p33nah.loopo

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.p33nah.loopo.ui.voice.FunctionGemmaCommandInterpreter
import com.p33nah.loopo.ui.voice.ItalianVoiceCommandParser
import com.p33nah.loopo.ui.voice.VoiceModelToolCall
import com.p33nah.loopo.ui.voice.VoiceExperiment
import com.p33nah.loopo.ui.voice.VoiceExperimentSession
import com.p33nah.loopo.ui.voice.VoiceInterpretation
import com.p33nah.loopo.ui.voice.VoicePlan
import com.p33nah.loopo.ui.voice.VoiceTurnTiming
import com.p33nah.loopo.ui.voice.VoiceExperimentUnavailable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Experimental app implementation. This class is only packaged by the FunctionGemma variant. */
internal class FunctionGemmaApplication : Application(), VoiceExperiment {
	private val modelMutex = Mutex()
	private val preferences by lazy { getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
	private val mutableUi = MutableStateFlow(
		ExperimentUiState(
			backend = InferenceBackend.CPU,
			modelOnly = false,
			modelReady = false,
		),
	)
	private val logTag = "FunctionGemmaExperiment"

	private val ui: StateFlow<ExperimentUiState> = mutableUi

	override fun onCreate() {
		super.onCreate()
		mutableUi.value = mutableUi.value.copy(
			backend = readBackend(),
			modelOnly = preferences.getBoolean(KEY_MODEL_ONLY, false),
			modelReady = modelFile().let { it.isFile && it.length() == MODEL_SIZE_BYTES },
		)
	}

	@Composable
	override fun Controls(armed: Boolean) {
		val context = LocalContext.current
		val application = context.applicationContext as FunctionGemmaApplication
		val state by application.ui.collectAsState()
		var showDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
		val scope = rememberCoroutineScope()
		val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			if (uri != null) scope.launch { application.importModel(uri) }
		}
		OutlinedButton(onClick = { showDialog = true }) {
			Text("FunctionGemma · ${state.backend.shortLabel}")
		}
		if (showDialog) AlertDialog(
			onDismissRequest = { showDialog = false },
			title = { Text("Esperimento FunctionGemma") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
					HorizontalDivider()
					Text(
						if (state.modelReady) "Bundle presente · SHA-256 verificato prima dell'uso" else "Bundle FunctionGemma non installato",
						style = MaterialTheme.typography.bodySmall,
					)
					Button(
						enabled = !armed && !state.sessionOpen && !state.importing,
						onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
					) { Text(if (state.modelReady) "Sostituisci bundle" else "Importa bundle .litertlm") }
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						InferenceBackend.entries.forEach { backend ->
							if (state.backend == backend) {
								Button(enabled = !armed && !state.sessionOpen && !state.importing, onClick = {}) {
									Text(backend.label)
								}
							} else {
								OutlinedButton(
									enabled = !armed && !state.sessionOpen && !state.importing,
									onClick = { scope.launch { application.selectBackend(backend) } },
								) { Text(backend.label) }
							}
						}
					}
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						Switch(
							checked = state.modelOnly,
							enabled = !armed && !state.sessionOpen && !state.importing,
							onCheckedChange = { enabled -> scope.launch { application.setModelOnly(enabled) } },
						)
						Column {
							Text("Solo modello · modalità sperimentale")
							Text("Se attiva, un piano può essere inviato anche quando il parser non concorda.", style = MaterialTheme.typography.bodySmall)
						}
					}
					Text(state.status, style = MaterialTheme.typography.bodySmall)
					state.lastInference?.let { Text("Ultima inferenza: $it", style = MaterialTheme.typography.bodySmall) }
					state.lastTurn?.let { Text("Ultimo turno: $it", style = MaterialTheme.typography.bodySmall) }
				}
			},
			confirmButton = { TextButton(onClick = { showDialog = false }) { Text("Chiudi") } },
		)
	}

	override suspend fun open(): VoiceExperimentSession {
		modelMutex.lock()
		var engine: Engine? = null
		var backendInitializationAttempted = false
		var modelVerificationMs = 0L
		var engineInitializationMs = 0L
		try {
			val selectedBackend = mutableUi.value.backend
			val modelOnly = mutableUi.value.modelOnly
			val model = modelFile()
			withContext(Dispatchers.IO) {
				val verifyStarted = System.nanoTime()
				check(model.isFile && model.length() == MODEL_SIZE_BYTES) {
					"Importa il bundle FunctionGemma verificato prima di attivare la voce."
				}
				check(sha256(model) == MODEL_SHA256) {
					"Il bundle FunctionGemma non supera il controllo SHA-256. Importalo di nuovo."
				}
				modelVerificationMs = (System.nanoTime() - verifyStarted) / NANOS_PER_MILLI
				backendInitializationAttempted = true
				val backend = selectedBackend.toLiteRtBackend()
				val created = Engine(
					EngineConfig(
						modelPath = model.absolutePath,
						backend = backend,
						cacheDir = cacheDir.absolutePath,
					),
				)
				try {
					val initializeStarted = System.nanoTime()
					created.initialize()
					engineInitializationMs = (System.nanoTime() - initializeStarted) / NANOS_PER_MILLI
					engine = created
				} catch (failure: Throwable) {
					runCatching { created.close() }.exceptionOrNull()?.let(failure::addSuppressed)
					throw failure
				}
			}
			currentCoroutineContext().ensureActive()
			val opened = checkNotNull(engine)
			engine = null
			mutableUi.value = mutableUi.value.copy(sessionOpen = true, status = "${selectedBackend.label} pronto")
			Log.i(logTag, "Engine initialized backend=${selectedBackend.name} verifyMs=$modelVerificationMs initializeMs=$engineInitializationMs")
			return FunctionGemmaSession(opened, selectedBackend, modelOnly) {
				mutableUi.value = mutableUi.value.copy(sessionOpen = false)
				modelMutex.unlock()
			}
		} catch (cancelled: CancellationException) {
			try {
				closeFailedEngine(engine)
			} finally {
				modelMutex.unlock()
			}
			throw cancelled
		} catch (failure: Throwable) {
			try {
				closeFailedEngine(engine)
			} finally {
				modelMutex.unlock()
			}
			val selected = mutableUi.value.backend
			val explanation = if (selected == InferenceBackend.GPU && backendInitializationAttempted) {
				"Inizializzazione GPU FunctionGemma non riuscita. Seleziona CPU per riprovare."
			} else {
				"Impossibile inizializzare FunctionGemma: ${failure.message ?: failure.javaClass.simpleName}"
			}
			mutableUi.value = mutableUi.value.copy(sessionOpen = false, status = explanation)
			throw VoiceExperimentUnavailable(explanation, failure)
		}
	}

	private suspend fun closeFailedEngine(engine: Engine?) {
		if (engine == null) return
		withContext(NonCancellable + Dispatchers.IO) { engine.close() }
	}

	private suspend fun importModel(uri: Uri) {
		mutableUi.value = mutableUi.value.copy(importing = true, status = "Verifico e importo il bundle…")
		try {
			withContext(Dispatchers.IO) {
				modelMutex.withLock {
					val destination = modelFile()
					destination.parentFile?.mkdirs()
					val temporary = File(destination.parentFile, "${destination.name}.importing")
					temporary.delete()
					try {
						val digest = MessageDigest.getInstance("SHA-256")
						var bytesCopied = 0L
						val input = contentResolver.openInputStream(uri)
							?: error("Android non può aprire il file selezionato.")
						input.use { source ->
							FileOutputStream(temporary).use { output ->
								val buffer = ByteArray(COPY_BUFFER_SIZE)
								while (true) {
								currentCoroutineContext().ensureActive()
									val count = source.read(buffer)
									if (count < 0) break
									bytesCopied += count
									check(bytesCopied <= MODEL_SIZE_BYTES) { "Il bundle è più grande della versione attesa." }
									digest.update(buffer, 0, count)
									output.write(buffer, 0, count)
								}
								output.fd.sync()
							}
						}
						check(bytesCopied == MODEL_SIZE_BYTES) {
							"Dimensione bundle errata: $bytesCopied byte; attesi $MODEL_SIZE_BYTES."
						}
						val actualHash = digest.digest().toHex()
						check(actualHash == MODEL_SHA256) { "SHA-256 bundle errato: $actualHash" }
						java.nio.file.Files.move(
							temporary.toPath(), destination.toPath(),
							java.nio.file.StandardCopyOption.ATOMIC_MOVE,
							java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						)
					}
					catch (failure: Throwable) {
						temporary.delete()
						throw failure
					}
				}
			}
			mutableUi.value = mutableUi.value.copy(importing = false, modelReady = true, status = "Bundle verificato e pronto")
			Log.i(logTag, "Pinned model imported size=$MODEL_SIZE_BYTES sha256=$MODEL_SHA256")
		} catch (cancelled: CancellationException) {
			mutableUi.value = mutableUi.value.copy(importing = false, status = "Importazione annullata")
			throw cancelled
		} catch (failure: Throwable) {
			mutableUi.value = mutableUi.value.copy(
				importing = false,
				status = "Importazione non riuscita: ${failure.message ?: failure.javaClass.simpleName}",
			)
		}
	}

	private suspend fun selectBackend(backend: InferenceBackend) {
		withContext(Dispatchers.IO) {
			modelMutex.withLock {
				preferences.edit().putString(KEY_BACKEND, backend.name).apply()
				mutableUi.value = mutableUi.value.copy(backend = backend, status = "${backend.label} selezionata")
			}
		}
	}

	private suspend fun setModelOnly(enabled: Boolean) {
		withContext(Dispatchers.IO) {
			modelMutex.withLock {
				preferences.edit().putBoolean(KEY_MODEL_ONLY, enabled).apply()
				mutableUi.value = mutableUi.value.copy(
					modelOnly = enabled,
					status = if (enabled) "Modalità solo modello attiva: i piani non concordi con il parser possono essere inviati." else "Richiesta conferma del parser attiva",
				)
			}
		}
	}

	private fun modelFile() = File(filesDir, MODEL_RELATIVE_PATH)

	private fun readBackend(): InferenceBackend =
		preferences.getString(KEY_BACKEND, InferenceBackend.CPU.name)
			?.let { runCatching { InferenceBackend.valueOf(it) }.getOrNull() }
			?: InferenceBackend.CPU

	private inner class FunctionGemmaSession(
		private val engine: Engine,
		private val backend: InferenceBackend,
		private val modelOnly: Boolean,
		private val releaseEngineLock: () -> Unit,
	) : VoiceExperimentSession {
		private val closed = AtomicBoolean(false)

		override suspend fun interpret(transcript: String): VoicePlan? {
			require(transcript.isNotBlank()) { "Trascrizione vuota" }
			val startedAt = System.nanoTime()
			try {
				val calls = withContext(Dispatchers.IO) {
					engine.createConversation(
						ConversationConfig(
							systemInstruction = Contents.of(FUNCTIONGEMMA_SYSTEM_INSTRUCTION),
							tools = FUNCTIONGEMMA_TOOLS.map(::tool),
							automaticToolCalling = false,
							samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
							maxOutputToken = 64,
						),
						).use { conversation ->
							conversation.sendMessage(transcript).toolCalls.map { call ->
								VoiceModelToolCall(call.name, call.arguments)
							}
						}
				}
				val candidate = FunctionGemmaCommandInterpreter.interpret(calls)
				val accepted = if (modelOnly) candidate else FunctionGemmaCommandInterpreter.requireParserAgreement(
					candidate,
					ItalianVoiceCommandParser.parse(transcript),
				)
				val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
				Log.i(logTag, "Inference complete backend=${backend.name} durationMs=$elapsedMs outcome=${accepted.javaClass.simpleName}")
				mutableUi.value = mutableUi.value.copy(
					lastInference = "${backend.label}: $elapsedMs ms · ${FunctionGemmaCommandInterpreter.renderLabel(accepted)}",
				)
				return (accepted as? VoiceInterpretation.Accepted)?.plan
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: Throwable) {
				val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
				Log.e(logTag, "Inference failed backend=${backend.name} durationMs=$elapsedMs failure=${failure.javaClass.simpleName}")
				throw VoiceExperimentUnavailable("FunctionGemma inference failed: ${failure.message ?: failure.javaClass.simpleName}", failure)
			}
		}

		override fun report(timing: VoiceTurnTiming) {
			val summary = "${backend.label} · capture ${timing.captureMs ?: "—"} ms · inference ${timing.interpretationMs ?: "—"} ms · dispatch ${timing.dispatchMs ?: "—"} ms · total ${timing.totalMs} ms · ${timing.outcome.name}"
			Log.i(logTag, "Voice turn backend=${backend.name} $summary")
			mutableUi.value = mutableUi.value.copy(lastTurn = summary)
		}

		override fun close() {
			if (!closed.compareAndSet(false, true)) return
			try {
				engine.close()
			} finally {
				releaseEngineLock()
				Log.i(logTag, "Engine closed backend=${backend.name}")
			}
		}
	}

	private enum class InferenceBackend(val label: String, val shortLabel: String) {
		CPU("CPU · 4 thread", "CPU"), GPU("GPU", "GPU");
		fun toLiteRtBackend(): Backend = when (this) {
			CPU -> Backend.CPU(threadCount = 4)
			GPU -> Backend.GPU()
		}
	}

	private data class ExperimentUiState(
		val backend: InferenceBackend,
		val modelOnly: Boolean,
		val modelReady: Boolean,
		val importing: Boolean = false,
		val sessionOpen: Boolean = false,
		val status: String = "Seleziona o importa il bundle FunctionGemma",
		val lastInference: String? = null,
		val lastTurn: String? = null,
	)

	private companion object {
		const val PREFERENCES = "functiongemma-experiment"
		const val KEY_BACKEND = "backend"
		const val KEY_MODEL_ONLY = "model_only"
		const val MODEL_RELATIVE_PATH = "functiongemma/model.litertlm"
		const val MODEL_SIZE_BYTES = 452_594_560L
		const val MODEL_SHA256 = "b0164093abbc2adcf776c9012d75248f5534328e5569f63a64ee55870770a836"
		const val COPY_BUFFER_SIZE = 64 * 1024
		const val NANOS_PER_MILLI = 1_000_000L
		const val FUNCTIONGEMMA_SYSTEM_INSTRUCTION =
			"You are a model that can do function calling with the following functions. " +
			"Interpreta un comando vocale italiano per Loopo. Usa soltanto gli strumenti dichiarati. " +
			"È ammessa una sola azione oppure un cambio di velocità seguito da riprendi o ricomincia. " +
			"Per richieste ambigue, negate o non supportate, non chiamare strumenti e rispondi: Comando non riconosciuto."
		val FUNCTIONGEMMA_TOOLS by lazy {
			listOf(
				functionTool("resume", "Riprendi la riproduzione dal punto corrente."),
				functionTool("restart", "Ricomincia la riproduzione dall'inizio del loop o del brano."),
				functionTool("stay_paused", "Lascia la riproduzione in pausa."),
				functionTool("set_rate", "Imposta la velocità assoluta di riproduzione senza avviare la traccia.", "percent", "Velocità intera in percentuale da 25 a 200.", 25, 200),
				functionTool("adjust_rate", "Aumenta o riduce la velocità corrente senza avviare la traccia.", "delta_percent", "Variazione intera in punti percentuali da -175 a 175, escluso zero.", -175, 175, rejectZero = true),
			)
		}

		fun functionTool(
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
					.put("required", if (argument == null) JSONArray() else JSONArray().put(argument))
					.put("additionalProperties", false)
				return JSONObject().put("name", name).put("description", description)
					.put("parameters", parameters).toString()
			}

			override fun execute(paramsJsonString: String): String =
				"{\"error\":\"tool execution disabled; Loopo dispatches only validated VoicePlan values\"}"
		}

		fun sha256(file: File): String {
			val digest = MessageDigest.getInstance("SHA-256")
			file.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
				val buffer = ByteArray(COPY_BUFFER_SIZE)
				while (true) {
					val count = input.read(buffer)
					if (count < 0) break
					digest.update(buffer, 0, count)
				}
			}
			return digest.digest().toHex()
		}

		fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
	}
}
