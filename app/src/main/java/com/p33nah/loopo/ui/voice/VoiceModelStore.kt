package com.p33nah.loopo.ui.voice

import android.content.Context
import com.p33nah.loopo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal class VoiceModelStore(
	private val context: Context,
	private val releaseAssetBase: String = RELEASE_ASSET_BASE,
	private val versionName: String = BuildConfig.VERSION_NAME,
	private val expectedSha256: String = MODEL_SHA256,
) {
	suspend fun ensureInstalled(onProgress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
		val base = File(context.noBackupFilesDir, MODEL_DIRECTORY)
		val model = File(base, MODEL_ROOT)
		if (model.isDirectory && File(model, "conf/model.conf").isFile && File(model, "am").isDirectory &&
			File(base, COMPLETE_MARKER).isFile && File(base, COMPLETE_MARKER).readText() == expectedSha256
		) return@withContext model

		val download = File(context.noBackupFilesDir, "$MODEL_DIRECTORY.zip.part")
		val archive = File(context.noBackupFilesDir, "$MODEL_DIRECTORY.zip")
		try {
			val bundled = runCatching { context.assets.open(MODEL_ASSET) }.getOrNull()
			if (bundled != null) {
				bundled.use { input -> FileOutputStream(archive).use { output -> input.copyTo(output) } }
			} else {
				downloadArchive(download, onProgress)
				check(download.renameTo(archive)) { "Impossibile salvare il modello vocale scaricato." }
			}
			check(sha256(archive) == expectedSha256) { "Il modello vocale scaricato non supera il controllo di integrità." }
			installArchive(archive, base)
			model
		} finally {
			download.delete()
			archive.delete()
		}
	}

	private suspend fun downloadArchive(destination: File, onProgress: (Long, Long) -> Unit) {
		val url = URL("$releaseAssetBase/v$versionName/$MODEL_ASSET")
		val connection = (url.openConnection() as HttpURLConnection).apply {
			connectTimeout = 15_000
			readTimeout = 5_000
			instanceFollowRedirects = true
		}
		try {
			connection.connect()
			check(connection.responseCode in 200..299) { "Download del modello non riuscito (HTTP ${connection.responseCode})." }
			val total = connection.contentLengthLong.takeIf { it in 1..MAX_ARCHIVE_BYTES } ?: -1L
			var received = 0L
			val buffer = ByteArray(BUFFER_SIZE)
			connection.inputStream.use { input ->
				FileOutputStream(destination).use { output ->
					while (true) {
						currentCoroutineContext().ensureActive()
						val count = input.read(buffer)
						if (count < 0) break
						received += count
						check(received <= MAX_ARCHIVE_BYTES) { "Il file del modello supera la dimensione consentita." }
						output.write(buffer, 0, count)
						onProgress(received, total)
					}
				}
			}
			check(received > 0L) { "Il download del modello è vuoto." }
		} finally {
			connection.disconnect()
		}
	}

	private suspend fun installArchive(archive: File, destination: File) {
		val staging = File(context.noBackupFilesDir, "$MODEL_DIRECTORY.installing")
		val backup = File(context.noBackupFilesDir, "$MODEL_DIRECTORY.previous")
		staging.deleteRecursively()
		backup.deleteRecursively()
		check(staging.mkdirs()) { "Impossibile preparare l’installazione del modello." }
		val safeRoot = staging.canonicalPath + File.separator
		var expanded = 0L
		var entryCount = 0
		try {
			ZipInputStream(FileInputStream(archive)).use { zip ->
				while (true) {
					currentCoroutineContext().ensureActive()
					val entry = zip.nextEntry ?: break
					entryCount++
					check(entryCount <= MAX_ARCHIVE_ENTRIES) { "Il modello contiene troppe voci nell’archivio." }
					val output = File(staging, entry.name).canonicalFile
					check(output.path.startsWith(safeRoot)) { "Il modello contiene un percorso non valido." }
					if (entry.isDirectory) check(output.isDirectory || output.mkdirs())
					else {
						check(output.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
						FileOutputStream(output).use { stream ->
							val buffer = ByteArray(BUFFER_SIZE)
							while (true) {
								currentCoroutineContext().ensureActive()
								val count = zip.read(buffer)
								if (count < 0) break
								expanded += count
								check(expanded <= MAX_EXPANDED_BYTES) { "Il modello estratto supera la dimensione consentita." }
								stream.write(buffer, 0, count)
							}
						}
					}
					zip.closeEntry()
				}
			}
			check(File(staging, MODEL_ROOT).isDirectory) { "L’archivio non contiene il modello italiano atteso." }
			File(staging, COMPLETE_MARKER).writeText(expectedSha256)
			if (destination.exists()) check(destination.renameTo(backup)) { "Impossibile sostituire il modello vocale." }
			if (!staging.renameTo(destination)) {
				backup.renameTo(destination)
				error("Impossibile completare l’installazione del modello.")
			}
			backup.deleteRecursively()
		} finally {
			staging.deleteRecursively()
		}
	}

	private fun sha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		FileInputStream(file).use { input ->
			val buffer = ByteArray(BUFFER_SIZE)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	companion object {
		const val MODEL_SHA256 = "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267"
		const val MODEL_ASSET = "loopo-vosk-it-0.22.zip"
		const val MODEL_DIRECTORY = "loopo-vosk-it-0.22"
		const val MODEL_ROOT = "vosk-model-small-it-0.22"
		const val COMPLETE_MARKER = ".loopo-model-complete"
		const val RELEASE_ASSET_BASE = "https://github.com/p33nah/loopo/releases/download"
		private const val BUFFER_SIZE = 64 * 1024
		private const val MAX_ARCHIVE_BYTES = 60L * 1024 * 1024
		private const val MAX_EXPANDED_BYTES = 120L * 1024 * 1024
		private const val MAX_ARCHIVE_ENTRIES = 20_000
	}
}
