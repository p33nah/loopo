package com.p33nah.loopo.ui.voice

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceModelStoreTest {
	@Test
	fun downloadsVerifiesInstallsAndReusesModelWithoutAnotherRequest() = runBlocking {
		val context = isolatedContext()
		val archive = modelArchive()
		val digest = sha256(archive)
		val requests = AtomicInteger()
		LocalAssetServer(archive, requests).use { server ->
			val store = VoiceModelStore(context, server.baseUrl, "0.0.1", digest)
			var reportedBytes = 0L
			val installed = store.ensureInstalled { received, _ -> reportedBytes = received }
			assertTrue(installed.isDirectory)
			assertTrue(installed.resolve("conf/model.conf").isFile)
			assertEquals(archive.size.toLong(), reportedBytes)
			assertEquals(1, requests.get())
			assertTrue(server.requestPath.get().contains("/v0.0.1/${VoiceModelStore.MODEL_ASSET}"))
			assertEquals(installed, store.ensureInstalled())
			assertEquals("A cached model must not contact the release host again", 1, requests.get())
		}
	}

	@Test
	fun rejectsChecksumMismatchAndDoesNotLeaveAnInstalledModel() = runBlocking {
		val context = isolatedContext()
		val archive = modelArchive()
		LocalAssetServer(archive).use { server ->
			val store = VoiceModelStore(context, server.baseUrl, "0.0.1", "00".repeat(32))
			val failure = runCatching { store.ensureInstalled() }.exceptionOrNull()
			assertTrue(failure is IllegalStateException)
			assertFalse(context.noBackupFilesDir.resolve(VoiceModelStore.MODEL_DIRECTORY).exists())
			assertFalse(context.noBackupFilesDir.resolve("${VoiceModelStore.MODEL_DIRECTORY}.zip.part").exists())
			VoiceModelStore(context, server.baseUrl, "0.0.1", sha256(archive)).ensureInstalled()
			assertTrue(context.noBackupFilesDir.resolve(VoiceModelStore.MODEL_DIRECTORY).resolve(VoiceModelStore.MODEL_ROOT).isDirectory)
			assertEquals("A checksum error can be repaired by retrying", 2, server.requests.get())
		}
	}

	@Test
	fun rejectsZipTraversalWithoutWritingOutsideTheStagingDirectory() = runBlocking {
		val context = isolatedContext()
		val escapeName = "${VoiceModelStore.MODEL_ROOT}/../../escaped-model-file"
		val archive = ByteArrayOutputStream().use { bytes ->
			ZipOutputStream(bytes).use { zip ->
				zip.putNextEntry(ZipEntry(escapeName))
				zip.write(byteArrayOf(1, 2, 3))
				zip.closeEntry()
				zip.putNextEntry(ZipEntry("${VoiceModelStore.MODEL_ROOT}/conf/model.conf"))
				zip.write("model".toByteArray())
				zip.closeEntry()
			}
			bytes.toByteArray()
		}
		val digest = sha256(archive)
		LocalAssetServer(archive).use { server ->
			val store = VoiceModelStore(context, server.baseUrl, "0.0.1", digest)
			val failure = runCatching { store.ensureInstalled() }.exceptionOrNull()
			assertTrue(failure is IllegalStateException)
			assertFalse(context.noBackupFilesDir.resolve("escaped-model-file").exists())
			assertFalse(context.noBackupFilesDir.resolve(VoiceModelStore.MODEL_DIRECTORY).exists())
		}
	}

	private fun modelArchive(): ByteArray = ByteArrayOutputStream().use { bytes ->
		ZipOutputStream(bytes).use { zip ->
			zip.putNextEntry(ZipEntry("${VoiceModelStore.MODEL_ROOT}/conf/model.conf"))
			zip.write("model config".toByteArray())
			zip.closeEntry()
			zip.putNextEntry(ZipEntry("${VoiceModelStore.MODEL_ROOT}/am/model.bin"))
			zip.write(byteArrayOf(1, 2, 3, 4))
			zip.closeEntry()
		}
		bytes.toByteArray()
	}

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it) }

	private fun isolatedContext(): Context {
		val app = RuntimeEnvironment.getApplication()
		val directory = File(app.cacheDir, "voice-model-test-${System.nanoTime()}").apply { check(mkdirs()) }
		return object : ContextWrapper(app) {
			override fun getNoBackupFilesDir(): File = directory
		}
	}

	private class LocalAssetServer(private val body: ByteArray, val requests: AtomicInteger = AtomicInteger()) : AutoCloseable {
		private val socket = ServerSocket(0)
		val baseUrl = "http://127.0.0.1:${socket.localPort}"
		val requestPath = AtomicReference("")
		private val worker = Thread {
			try {
				while (!socket.isClosed) {
					socket.accept().use { client ->
						val reader = BufferedReader(InputStreamReader(client.getInputStream()))
						val request = reader.readLine() ?: ""
						requestPath.set(request)
						while (reader.readLine()?.isNotEmpty() == true) Unit
						requests.incrementAndGet()
						val output = client.getOutputStream()
						output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
						output.write(body)
						output.flush()
					}
				}
			} catch (_: Exception) {
				Unit
			}
		}.apply { isDaemon = true; start() }

		override fun close() {
			socket.close()
			worker.join(2_000)
		}
	}
}
