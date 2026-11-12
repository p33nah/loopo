package com.p33nah.loopo.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

internal fun hasMicrophonePermission(context: Context): Boolean =
	context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

internal object VoiceMicrophoneAccess { val mutex = Mutex() }

internal class VoskWakeDetector(
	private val context: Context,
	private val onModelProgress: (Long, Long) -> Unit = { _, _ -> },
	private val onModelError: (String) -> Unit = {},
) {
	private var model: Model? = null

	suspend fun awaitWake(onListening: () -> Unit): WakeDetection = try {
		VoiceMicrophoneAccess.mutex.withLock {
			withContext(Dispatchers.IO) {
				currentCoroutineContext().ensureActive()
				val loadedModel = model ?: loadModel().also { model = it }
				currentCoroutineContext().ensureActive()
				capture(loadedModel, WAKE_GRAMMAR, onListening) { samples, recognizer ->
					if (!recognizer.acceptWaveForm(samples, samples.size)) null
					else JSONObject(recognizer.result).optString("text").takeIf { text -> text == WAKE_PHRASE }
				}
				WakeDetection.Detected
			}
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: SecurityException) {
		WakeDetection.PermissionDenied
	} catch (_: UnsatisfiedLinkError) {
		WakeDetection.Unavailable
	} catch (_: Exception) {
		WakeDetection.Failed
	}

	suspend fun transcribe(onListening: () -> Unit): TranscriptResult = try {
		val text = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
			VoiceMicrophoneAccess.mutex.withLock {
				withContext(Dispatchers.IO) {
					currentCoroutineContext().ensureActive()
					val loadedModel = model ?: loadModel().also { model = it }
					capture(loadedModel, null, onListening) { samples, recognizer ->
						if (!recognizer.acceptWaveForm(samples, samples.size)) null
						else JSONObject(recognizer.result).optString("text").takeIf(String::isNotBlank)
					}
				}
			}
		} ?: return TranscriptResult.TimedOut
		TranscriptResult.Text(text)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: SecurityException) {
		TranscriptResult.PermissionDenied
	} catch (_: UnsatisfiedLinkError) {
		TranscriptResult.Unavailable
	} catch (_: Exception) {
		TranscriptResult.Failed
	}

	fun close() { model?.close(); model = null }

	private suspend fun loadModel(): Model = try {
		Model(VoiceModelStore(context).ensureInstalled(onModelProgress).absolutePath)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: Exception) {
		onModelError("Modello vocale non disponibile. Verifica la connessione e tocca il lupo per riprovare.")
		throw failure
	}

	private suspend fun capture(
		model: Model,
		grammar: String?,
		onListening: () -> Unit,
		consume: (ShortArray, Recognizer) -> String?,
	): String? {
		if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			throw SecurityException("Microphone permission was revoked")
		}
		val minimumBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
		if (minimumBuffer <= 0) error("AudioRecord buffer unavailable")
		val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
			AudioFormat.ENCODING_PCM_16BIT, maxOf(minimumBuffer, CHUNK_SAMPLES * 2))
		if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); error("AudioRecord unavailable") }
		val recognizer = if (grammar == null) Recognizer(model, SAMPLE_RATE.toFloat()) else Recognizer(model, SAMPLE_RATE.toFloat(), grammar)
		try {
			recorder.startRecording()
			if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("AudioRecord did not start")
			withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { onListening() }
			val samples = ShortArray(CHUNK_SAMPLES)
			var sampleCount = 0
			while (true) {
				currentCoroutineContext().ensureActive()
				val count = recorder.read(samples, sampleCount, samples.size - sampleCount, AudioRecord.READ_NON_BLOCKING)
				if (count < 0) error("AudioRecord read failed")
				if (count == 0) { delay(10); continue }
				sampleCount += count
				if (sampleCount < samples.size) continue
				sampleCount = 0
				consume(samples, recognizer)?.let { return it }
			}
		} finally {
			recognizer.close()
			try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
			finally { recorder.release() }
		}
	}

	companion object {
		const val SAMPLE_RATE = 16_000
		const val CHUNK_SAMPLES = 1_600
		const val WAKE_PHRASE = "ehi lupo"
		const val WAKE_GRAMMAR = "[\"ehi lupo\",\"ehi luca\",\"ehi lupa\",\"ehi luogo\",\"ehi dopo\",\"il lupo\",\"ehi\",\"[unk]\"]"
		const val COMMAND_TIMEOUT_MS = 12_000L
	}
}
