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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.p33nah.loopo.platform.audio.PlaybackService
import com.p33nah.loopo.ui.StudyUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val scope = rememberCoroutineScope()
	var status by remember(session, trackUri) { mutableStateOf<VoiceStatus?>(null) }
	var activeTurn by remember(session, trackUri) { mutableStateOf<VoiceTurn?>(null) }

	fun cancelVoice() {
		val turn = activeTurn ?: return
		turn.job.cancel()
		activeTurn = null
		status = null
	}

	fun isTrackCurrent(): Boolean {
		val ready = session.study.state.value as? StudyUiState.Ready
		return trackUri != null && ready?.inspection?.track?.uri == trackUri &&
			isSessionActive() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
	}

	val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		status = if (granted) VoiceStatus.PermissionGranted else VoiceStatus.PermissionDenied
	}
	DisposableEffect(lifecycle, session, trackUri) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_PAUSE) cancelVoice()
		}
		lifecycle.addObserver(observer)
		onDispose {
			lifecycle.removeObserver(observer)
			cancelVoice()
		}
	}

	LaunchedEffect(session, trackUri) {
		session.externalPauseVersion.collect { version ->
			val turn = activeTurn
			if (turn != null && turn.externalPauseVersion != version) cancelVoice()
		}
	}

	fun activate() {
		if (activeTurn != null) {
			cancelVoice()
			status = VoiceStatus.Cancelled
			return
		}
		if (!isTrackCurrent()) return
		if (!hasOnDeviceRecognizer(context)) {
			status = VoiceStatus.Unavailable
			return
		}
		if (!hasMicrophonePermission(context)) {
			requestPermission.launch(Manifest.permission.RECORD_AUDIO)
			return
		}

		val externalPauseVersion = session.externalPauseVersion.value
		fun isTurnCurrent(): Boolean =
			isTrackCurrent() && session.externalPauseVersion.value == externalPauseVersion

		val coordinator = VoiceCommandCoordinator(
			pause = { session.pauseForVoice(::isTurnCurrent) },
			transcribe = { onListening ->
				if (isTurnCurrent()) transcribeOnDevice(context, onListening) else TranscriptResult.Cancelled
			},
			dispatch = { command ->
				if (!isTurnCurrent() || !hasMicrophonePermission(context)) return@VoiceCommandCoordinator false
				when (command) {
					VoiceCommand.Resume -> session.requestPlay()
					VoiceCommand.StayPaused -> Unit
					is VoiceCommand.SetRate -> session.study.setPlaybackRate(command.rate)
				}
				true
			},
		)
		val job = scope.launch(start = CoroutineStart.LAZY) {
			try {
				val outcome = coordinator.run { status = it }
				status = if (isTurnCurrent()) outcome else VoiceStatus.Cancelled
			} finally {
				if (activeTurn?.job === coroutineContext[Job]) activeTurn = null
			}
		}
		activeTurn = VoiceTurn(job, externalPauseVersion)
		job.start()
	}

	val controls: (@Composable () -> Unit)? = if (trackUri == null) null else {
		{
			Row(verticalAlignment = Alignment.CenterVertically) {
				OutlinedButton(onClick = ::activate) {
					Text(if (activeTurn == null) "Prova voce" else "Annulla voce")
				}
				status?.let {
					Spacer(Modifier.width(8.dp))
					Text(
						text = statusText(it),
						style = MaterialTheme.typography.labelMedium,
						modifier = Modifier.widthIn(max = 160.dp).semantics { liveRegion = LiveRegionMode.Polite },
					)
				}
			}
		}
	}
	content(::cancelVoice, controls)
}

private data class VoiceTurn(val job: Job, val externalPauseVersion: Long)

private fun statusText(status: VoiceStatus) = when (status) {
	VoiceStatus.PermissionGranted -> "Permesso concesso. Tocca Prova voce."
	VoiceStatus.PermissionDenied -> "Serve il permesso microfono."
	VoiceStatus.Pausing -> "Metto in pausa…"
	VoiceStatus.Listening -> "Ti ascolto…"
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

private fun hasMicrophonePermission(context: Context): Boolean =
	context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private suspend fun transcribeOnDevice(context: Context, onListening: () -> Unit): TranscriptResult {
	if (Build.VERSION.SDK_INT < 31 || !hasOnDeviceRecognizer(context)) return TranscriptResult.Unavailable
	if (!hasMicrophonePermission(context)) return TranscriptResult.PermissionDenied
	return try {
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
