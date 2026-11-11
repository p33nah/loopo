package com.p33nah.loopo.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.p33nah.loopo.platform.audio.PlaybackService
import com.p33nah.loopo.ui.StudyUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@Composable
internal fun VoiceStudy(
	session: PlaybackService.Session,
	trackUri: Uri?,
	isSessionActive: () -> Boolean,
	content: @Composable (cancelVoice: () -> Unit, voiceControls: (@Composable () -> Unit)?) -> Unit,
) {
	val context = LocalContext.current
	val appContext = context.applicationContext
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val sessionIsActive by rememberUpdatedState(isSessionActive)
	val modelAvailable = remember(appContext) { VoskWakeDetector.isModelAvailable(appContext) }
	val localRevision = remember(session, trackUri) { MutableStateFlow(0L) }
	var status by remember(session, trackUri) { mutableStateOf<VoiceStatus?>(null) }
	val backgroundStatus by BackgroundVoiceState.status.collectAsState()
	val preferences = remember(appContext) {
		appContext.getSharedPreferences(BackgroundVoiceService.PREFERENCES, Context.MODE_PRIVATE)
	}
	var backgroundPauseEnabled by remember(preferences) {
		mutableStateOf(preferences.getBoolean(BackgroundVoiceService.ENABLED, false))
	}
	fun setBackgroundPauseEnabled(enabled: Boolean) {
		backgroundPauseEnabled = enabled
		preferences.edit().putBoolean(BackgroundVoiceService.ENABLED, enabled).apply()
		if (!enabled) stopBackgroundVoice(appContext)
	}
	val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		localRevision.value += 1
	}
	val requestBackgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		if (it && modelAvailable) setBackgroundPauseEnabled(true)
	}

	DisposableEffect(lifecycle) {
		val observer = LifecycleEventObserver { _, event ->
			when (event) {
				Lifecycle.Event.ON_RESUME -> BackgroundVoiceState.setForeground(true)
				Lifecycle.Event.ON_PAUSE -> BackgroundVoiceState.setForeground(false)
				else -> Unit
			}
		}
		lifecycle.addObserver(observer)
		onDispose { lifecycle.removeObserver(observer) }
	}

	LaunchedEffect(session, trackUri, backgroundPauseEnabled, modelAvailable) {
		if (!backgroundPauseEnabled || trackUri == null || !modelAvailable) return@LaunchedEffect
		lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			session.study.playbackState.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
				if (playing && hasMicrophonePermission(appContext)) {
					armBackgroundVoice(appContext)
				}
			}
		}
	}

	LaunchedEffect(context, lifecycle, session, trackUri, modelAvailable) {
		if (trackUri == null || !modelAvailable) return@LaunchedEffect
		lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			val wakeDetector = VoskWakeDetector(appContext)
			try {
				combine(localRevision, session.externalPauseVersion, ::Pair).collectLatest { (local, external) ->
					fun isCurrent(): Boolean {
						val ready = session.study.state.value as? StudyUiState.Ready
						return ready?.inspection?.track?.uri == trackUri && sessionIsActive() &&
							lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
							localRevision.value == local && session.externalPauseVersion.value == external
					}
					if (!isCurrent()) return@collectLatest
					status = null
					if (!hasOnDeviceRecognizer(context)) {
						status = VoiceStatus.Unavailable
						return@collectLatest
					}
					if (!hasMicrophonePermission(context)) {
						if (preferences.getBoolean("microphone_requested", false)) {
							status = VoiceStatus.PermissionDenied
						} else {
							preferences.edit().putBoolean("microphone_requested", true).apply()
							requestPermission.launch(Manifest.permission.RECORD_AUDIO)
						}
						return@collectLatest
					}

					fun canUseVoice(): Boolean = isCurrent() && hasMicrophonePermission(context)

					val commandCoordinator = VoiceCommandCoordinator(
						pause = { session.pauseForVoice(::canUseVoice) },
						transcribe = { onListening ->
							if (canUseVoice()) transcribeOnDevice(context, onListening) else TranscriptResult.Cancelled
						},
						dispatch = { command ->
							if (!canUseVoice()) return@VoiceCommandCoordinator false
							when (command) {
								VoiceCommand.Resume -> session.requestPlay()
								VoiceCommand.StayPaused -> Unit
								is VoiceCommand.SetRate -> session.study.setPlaybackRate(command.rate)
							}
							true
						},
					)
					val wakeCoordinator = WakeCommandCoordinator(
						awaitWake = wakeDetector::awaitWake,
						runCommand = commandCoordinator::run,
					)
					wakeCoordinator.listen(::canUseVoice) { if (isCurrent()) status = it }
				}
			} finally {
				status = null
				wakeDetector.close()
			}
		}
	}

	val text = when {
		trackUri == null -> null
		!modelAvailable -> statusText(VoiceStatus.WakeUnavailable)
		else -> status?.let(::statusText)
	}
	val voiceStatus: (@Composable () -> Unit)? = text?.let {
		{
			Text(
				text = it,
				style = MaterialTheme.typography.labelMedium,
				modifier = Modifier.widthIn(max = 180.dp).semantics { liveRegion = LiveRegionMode.Polite },
			)
		}
	}
	val controls: (@Composable () -> Unit)? = if (trackUri != null) {
		{
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Text(
					"Pausa vocale a schermo spento",
					style = MaterialTheme.typography.labelSmall,
					modifier = Modifier.weight(1f),
				)
				Switch(
					checked = backgroundPauseEnabled,
					enabled = modelAvailable,
					modifier = Modifier.semantics { contentDescription = "Pausa vocale a schermo spento" },
					onCheckedChange = { enabled ->
						if (enabled && !hasMicrophonePermission(appContext)) {
							requestBackgroundPermission.launch(Manifest.permission.RECORD_AUDIO)
						} else {
							setBackgroundPauseEnabled(enabled)
						}
					},
				)
			}
		}
	} else null
	val backgroundText = when (backgroundStatus) {
		VoiceStatus.WakeListening -> "Pausa vocale pronta"
		VoiceStatus.WakeFailed, VoiceStatus.WakeUnavailable, VoiceStatus.PauseFailed, VoiceStatus.PermissionDenied ->
			"Pausa vocale a schermo spento non disponibile"
		else -> null
	}
	val voiceControls: (@Composable () -> Unit)? = if (trackUri != null) {
		{
			Column(Modifier.widthIn(max = 188.dp)) {
				controls?.invoke()
				voiceStatus?.invoke()
				if (backgroundPauseEnabled && backgroundText != null) {
					Text(backgroundText, style = MaterialTheme.typography.labelSmall)
				}
			}
		}
	} else null
	content({ localRevision.value += 1 }, voiceControls)
}

private fun statusText(status: VoiceStatus) = when (status) {
	VoiceStatus.PermissionDenied -> "Consenti il microfono nelle impostazioni."
	VoiceStatus.Pausing -> "Metto in pausa…"
	VoiceStatus.Listening -> "Ti ascolto…"
	VoiceStatus.WakeListening -> "In ascolto per Ehi Lupo…"
	VoiceStatus.WakeFailed -> "Ascolto di Ehi Lupo non riuscito"
	VoiceStatus.WakeUnavailable -> "Risveglio offline non disponibile"
	VoiceStatus.Paused -> "In pausa"
	VoiceStatus.CommandSent -> "Comando inviato"
	VoiceStatus.PauseFailed -> "Pausa audio non riuscita"
	VoiceStatus.TimedOut -> "Nessun comando rilevato"
	VoiceStatus.Unavailable -> "Voce offline in italiano non disponibile"
	VoiceStatus.Unrecognized -> "Comando non riconosciuto"
	VoiceStatus.Failed -> "Riconoscimento non riuscito"
	VoiceStatus.Cancelled -> "Comando annullato"
}

private fun hasOnDeviceRecognizer(context: Context): Boolean =
	Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

internal fun hasMicrophonePermission(context: Context): Boolean =
	context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private suspend fun transcribeOnDevice(context: Context, onListening: () -> Unit): TranscriptResult =
	VoiceMicrophoneAccess.mutex.withLock {
		if (Build.VERSION.SDK_INT < 31 || !hasOnDeviceRecognizer(context)) return@withLock TranscriptResult.Unavailable
		if (!hasMicrophonePermission(context)) return@withLock TranscriptResult.PermissionDenied
		try {
			val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
			try {
				withTimeoutOrNull(12_000) {
					awaitTranscript(recognizer, onListening)
				} ?: TranscriptResult.TimedOut
			} finally {
				recognizer.destroy()
			}
		} catch (_: SecurityException) {
			TranscriptResult.PermissionDenied
		} catch (_: UnsupportedOperationException) {
			TranscriptResult.Unavailable
		}
	}

private suspend fun awaitTranscript(recognizer: SpeechRecognizer, onListening: () -> Unit): TranscriptResult =
	suspendCancellableCoroutine { continuation ->
		val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
			putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
			putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
			putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
		}
		recognizer.setRecognitionListener(object : RecognitionListener {
			override fun onReadyForSpeech(params: Bundle?) {
				if (continuation.isActive) onListening()
			}

			override fun onResults(results: Bundle?) {
				if (!continuation.isActive) return
				val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
				continuation.resume(text?.let(TranscriptResult::Text) ?: TranscriptResult.Unrecognized)
			}

			override fun onError(error: Int) {
				if (!continuation.isActive) return
				continuation.resume(when (error) {
					SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
					SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> TranscriptResult.Unavailable
					SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> TranscriptResult.PermissionDenied
					SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> TranscriptResult.TimedOut
					SpeechRecognizer.ERROR_NO_MATCH -> TranscriptResult.Unrecognized
					else -> TranscriptResult.Failed
				})
			}

			override fun onBeginningOfSpeech() = Unit
			override fun onRmsChanged(rmsdB: Float) = Unit
			override fun onBufferReceived(buffer: ByteArray?) = Unit
			override fun onEndOfSpeech() = Unit
			override fun onPartialResults(partialResults: Bundle?) = Unit
			override fun onEvent(eventType: Int, params: Bundle?) = Unit
		})
		recognizer.startListening(intent)
	}
