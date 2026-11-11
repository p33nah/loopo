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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

internal object VoiceMicrophoneAccess {
	val mutex = Mutex()
}

internal class VoskWakeDetector(private val context: Context) {
	private var model: Model? = null

	suspend fun awaitWake(onListening: () -> Unit): WakeDetection = try {
		VoiceMicrophoneAccess.mutex.withLock {
			withContext(Dispatchers.IO) {
				currentCoroutineContext().ensureActive()
				val loadedModel = model ?: loadModel().also { model = it }
				currentCoroutineContext().ensureActive()
				captureWake(loadedModel, onListening)
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

	fun close() {
		model?.close()
		model = null
	}

	private suspend fun captureWake(model: Model, onListening: () -> Unit): WakeDetection {
		val minimumBuffer = AudioRecord.getMinBufferSize(
			SAMPLE_RATE,
			AudioFormat.CHANNEL_IN_MONO,
			AudioFormat.ENCODING_PCM_16BIT,
		)
		if (minimumBuffer <= 0) return WakeDetection.Failed
		if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			return WakeDetection.PermissionDenied
		}
		val recorder = AudioRecord(
			MediaRecorder.AudioSource.MIC,
			SAMPLE_RATE,
			AudioFormat.CHANNEL_IN_MONO,
			AudioFormat.ENCODING_PCM_16BIT,
			maxOf(minimumBuffer, CHUNK_SAMPLES * 2),
		)
		if (recorder.state != AudioRecord.STATE_INITIALIZED) {
			recorder.release()
			return WakeDetection.Failed
		}
		try {
			val recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), WAKE_GRAMMAR)
			try {
				recorder.startRecording()
				if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return WakeDetection.Failed
				withContext(Dispatchers.Main.immediate) { onListening() }
				val samples = ShortArray(CHUNK_SAMPLES)
				var sampleCount = 0
				while (true) {
					currentCoroutineContext().ensureActive()
					val count = recorder.read(samples, sampleCount, samples.size - sampleCount, AudioRecord.READ_NON_BLOCKING)
					if (count < 0) return WakeDetection.Failed
					if (count == 0) {
						delay(10)
						continue
					}
					sampleCount += count
					if (sampleCount < samples.size) continue
					sampleCount = 0
					if (!recognizer.acceptWaveForm(samples, samples.size)) continue
					val phrase = JSONObject(recognizer.result).optString("text")
					if (phrase == WAKE_PHRASE) return WakeDetection.Detected
				}
			} finally {
				recognizer.close()
			}
		} finally {
			try {
				if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
			} finally {
				recorder.release()
			}
		}
	}

	private suspend fun loadModel(): Model {
		val destination = File(context.noBackupFilesDir, MODEL_DIRECTORY)
		val marker = File(destination, COMPLETE_MARKER)
		val modelDirectory = File(destination, MODEL_ARCHIVE_ROOT)
		if (modelDirectory.isDirectory && marker.isFile && marker.readText() == MODEL_VERSION) {
			return Model(modelDirectory.absolutePath)
		}

		val staging = File(context.noBackupFilesDir, "$MODEL_DIRECTORY.tmp")
		staging.deleteRecursively()
		check(staging.mkdirs()) { "Could not create the Vosk model directory" }
		val root = staging.canonicalPath + File.separator
		val buffer = ByteArray(BUFFER_SIZE)
		try {
			ZipInputStream(context.assets.open(MODEL_ASSET)).use { zip ->
				while (true) {
					currentCoroutineContext().ensureActive()
					val entry = zip.nextEntry ?: break
					val output = File(staging, entry.name).canonicalFile
					check(output.path.startsWith(root)) { "The Vosk model archive contains an unsafe path" }
					if (entry.isDirectory) {
						check(output.isDirectory || output.mkdirs()) { "Could not create a model directory" }
					} else {
						val parent = checkNotNull(output.parentFile)
						check(parent.isDirectory || parent.mkdirs()) { "Could not create a model directory" }
						output.outputStream().use { file ->
							while (true) {
								currentCoroutineContext().ensureActive()
								val count = zip.read(buffer)
								if (count < 0) break
								file.write(buffer, 0, count)
							}
						}
					}
					zip.closeEntry()
				}
			}
			File(staging, COMPLETE_MARKER).writeText(MODEL_VERSION)
			destination.deleteRecursively()
			check(staging.renameTo(destination)) { "Could not install the Vosk model" }
			currentCoroutineContext().ensureActive()
			return Model(File(destination, MODEL_ARCHIVE_ROOT).absolutePath)
		} finally {
			staging.deleteRecursively()
		}
	}

	companion object {
		fun isModelAvailable(context: Context): Boolean = try {
			context.assets.open(MODEL_ASSET).use { true }
		} catch (_: IOException) {
			false
		}

		const val SAMPLE_RATE = 16_000
		const val CHUNK_SAMPLES = 1_600
		const val BUFFER_SIZE = 64 * 1024
		const val WAKE_PHRASE = "ehi lupo"
		const val WAKE_GRAMMAR = "[\"ehi lupo\",\"ehi luca\",\"ehi lupa\",\"ehi luogo\",\"ehi dopo\",\"il lupo\",\"ehi\",\"[unk]\"]"
		const val MODEL_ASSET = "loopo-vosk-it.zip"
		const val MODEL_DIRECTORY = "vosk-it-loopo-wake"
		const val MODEL_ARCHIVE_ROOT = "vosk-model-small-it-0.22"
		const val COMPLETE_MARKER = ".loopo-model-complete"
		const val MODEL_VERSION = "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267"
	}
}
