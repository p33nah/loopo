package com.p33nah.loopo.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal fun hasMicrophonePermission(context: Context): Boolean =
	context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

internal fun hasOnDeviceRecognizer(context: Context): Boolean =
	Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

internal suspend fun transcribeOnDevice(context: Context, onListening: () -> Unit): TranscriptResult =
	VoiceMicrophoneAccess.mutex.withLock {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !hasOnDeviceRecognizer(context)) {
			return@withLock TranscriptResult.Unavailable
		}
		if (!hasMicrophonePermission(context)) return@withLock TranscriptResult.PermissionDenied
		try {
			val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
			try {
				withTimeoutOrNull(12_000) { awaitTranscript(recognizer, onListening) } ?: TranscriptResult.TimedOut
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
			override fun onReadyForSpeech(params: Bundle?) { if (continuation.isActive) onListening() }
			override fun onResults(results: Bundle?) {
				if (!continuation.isActive) return
				val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
				continuation.resume(text?.let(TranscriptResult::Text) ?: TranscriptResult.Unrecognized)
			}
			override fun onError(error: Int) {
				if (!continuation.isActive) return
				continuation.resume(when (error) {
					SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> TranscriptResult.Unavailable
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
