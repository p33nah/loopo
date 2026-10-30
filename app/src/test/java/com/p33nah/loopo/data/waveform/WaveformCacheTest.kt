package com.p33nah.loopo.data.waveform

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.p33nah.loopo.domain.model.AudioContainer
import com.p33nah.loopo.domain.model.TrackId
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.waveform.WaveformChannelPeaks
import com.p33nah.loopo.domain.waveform.WaveformData
import com.p33nah.loopo.domain.waveform.WaveformLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaveformCacheTest {
	private val context: Context = ApplicationProvider.getApplicationContext()
	private val resolver: ContentResolver = context.contentResolver
	private val uri = Uri.parse("content://waveform-cache-test/audio")

	@Test
	fun sourceKeyChangesWhenInteriorContentChangesUnderStableUriAndMetadata() {
		val source = File.createTempFile("loopo-source-", ".wav")
		val bytes = ByteArray(100_000) { (it * 31).toByte() }
		source.writeBytes(bytes)
		val originalModifiedAt = source.lastModified()
		installStableSource(source, size = bytes.size.toLong(), modifiedAt = 123_456L)
		val cache = WaveformCache(resolver, tempDirectory())
		val track = track()

		val originalKey = cache.sourceKey(track)
		val interior = source.length().toInt() / 2
		source.writeBytesAt(interior, (bytes[interior].toInt() xor 0x5a).toByte())
		source.setLastModified(originalModifiedAt)
		val changedKey = cache.sourceKey(track)

		assertNotNull(originalKey)
		assertNotNull(changedKey)
		assertNotEquals(originalKey, changedKey)
		assertEquals(bytes.size.toLong(), source.length())
		assertEquals(originalModifiedAt, source.lastModified())
		source.delete()
	}

	@Test
	fun waveformCacheRoundTripsAndDeletesAnEntryWithABadChecksum() {
		val source = File.createTempFile("loopo-source-", ".wav")
		source.writeBytes(byteArrayOf(1, 2, 3, 4))
		installStableSource(source, size = 4, modifiedAt = 9L)
		val root = tempDirectory()
		val cacheDirectory = File(root, "waveforms-v1").apply { mkdirs() }
		ShadowStatFs.registerStats(cacheDirectory, 4_096, 100_000, 80_000)
		val cache = WaveformCache(resolver, root)
		val track = track()
		val key = cache.sourceKey(track)!!
		cache.write(key, waveform(track.id))

		val restored = cache.read(key, track)
		assertNotNull(restored)
		assertEquals(4L, restored!!.coveredFrames)
		assertEquals(2, restored.levels.first().bucketCount)
		assertArrayEquals(floatArrayOf(-1f, -0.25f), restored.levels.first().channels.single().minimum, 0f)
		val cacheFile = File(cacheDirectory, "$key.wfm")
		assertTrue(cacheFile.isFile)
		java.io.RandomAccessFile(cacheFile, "rw").use { file ->
			file.seek(file.length() - 1)
			val original = file.readByte().toInt()
			file.seek(file.length() - 1)
			file.writeByte(original xor 0xff)
		}

		assertNull(cache.read(key, track))
		assertTrue(!cacheFile.exists())
		source.delete()
	}

	private fun installStableSource(source: File, size: Long, modifiedAt: Long) {
		val provider = StableSourceProvider(source, size, modifiedAt)
		provider.attachInfo(context, ProviderInfo().apply {
			authority = uri.authority
			applicationInfo = context.applicationInfo
		})
		ShadowContentResolver.registerProviderInternal(uri.authority, provider)
	}

	private fun track() = TrackInfo(
		id = TrackId("cached-track"),
		uri = uri,
		displayName = "same.wav",
		container = AudioContainer.WAV,
		durationFrames = 4,
		sampleRate = 8_000,
		channelCount = 1,
		sourceMimeType = "audio/wav",
		sourceSizeBytes = 4,
		sourceModifiedAtMillis = 9L,
	)

	private fun waveform(trackId: TrackId) = WaveformData(
		trackId = trackId,
		durationFrames = 4,
		coveredFrames = 4,
		sampleRate = 8_000,
		channelCount = 1,
		levels = listOf(
			WaveformLevel(2, listOf(WaveformChannelPeaks(floatArrayOf(-1f, -0.25f), floatArrayOf(0.5f, 1f)))),
			WaveformLevel(4, listOf(WaveformChannelPeaks(floatArrayOf(-1f), floatArrayOf(1f)))),
		),
	)

	private fun tempDirectory(): File = File(context.cacheDir, "waveform-cache-test-${UUID.randomUUID()}").apply { mkdirs() }

	private fun File.writeBytesAt(position: Int, value: Byte) {
		java.io.RandomAccessFile(this, "rw").use { file ->
			file.seek(position.toLong())
			file.writeByte(value.toInt())
		}
	}

	private class StableSourceProvider(
		private val source: File,
		private val size: Long,
		private val modifiedAt: Long,
	) : ContentProvider() {
		override fun onCreate() = true
		override fun getType(uri: Uri) = "audio/wav"
		override fun insert(uri: Uri, values: ContentValues?): Uri? = null
		override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
		override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
		override fun query(
			uri: Uri,
			projection: Array<out String>?,
			selection: String?,
			selectionArgs: Array<out String>?,
			sortOrder: String?,
		): Cursor {
			val columns = projection?.toList()?.toTypedArray()
				?: arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
			return MatrixCursor(columns).apply {
				addRow(Array<Any?>(columns.size) { index ->
					when (columns[index]) {
						OpenableColumns.SIZE -> size
						DocumentsContract.Document.COLUMN_LAST_MODIFIED -> modifiedAt
						else -> null
					}
				})
			}
		}
		override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
			ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
	}
}
