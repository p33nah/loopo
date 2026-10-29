package com.p33nah.loopo.data.waveform

import android.content.ContentResolver
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.waveform.WaveformChannelPeaks
import com.p33nah.loopo.domain.waveform.WaveformData
import com.p33nah.loopo.domain.waveform.WaveformLevel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream
import java.util.zip.CRC32

internal class WaveformCache(
	private val resolver: ContentResolver,
	cacheRoot: File,
) {
	private val directory = File(cacheRoot, "waveforms-v1")

	@Synchronized
	fun sourceKey(track: TrackInfo): String? {
		val normalizedUri = track.uri.normalizeScheme().toString()
		val metadata = documentMetadata(track.uri)
		val sourceHash = if (metadata != null) {
			endpointHash(track.uri, metadata.first) ?: fullContentHash(track.uri)
		} else {
			fullContentHash(track.uri)
		} ?: return null
		val identity = listOf(
			normalizedUri,
			track.durationFrames,
			track.sampleRate,
			track.channelCount,
			metadata?.first ?: -1L,
			metadata?.second ?: -1L,
			sourceHash,
		).joinToString("\n")
		return digest(identity.toByteArray(Charsets.UTF_8))
	}

	@Synchronized
	fun read(key: String, track: TrackInfo): WaveformData? {
		val file = cacheFile(key)
		if (!file.isFile || file.length() !in MIN_CACHE_FILE_BYTES..MAX_CACHE_BYTES) return null
		return try {
			val data = readFile(file, key, track)
			file.setLastModified(System.currentTimeMillis())
			data
		} catch (_: IOException) {
			file.delete()
			null
		} catch (_: IllegalArgumentException) {
			file.delete()
			null
		} catch (_: OutOfMemoryError) {
			file.delete()
			null
		}
	}

	@Synchronized
	fun write(key: String, data: WaveformData) {
		if (!data.isComplete || data.levels.isEmpty()) return
		if (!directory.isDirectory && !directory.mkdirs()) return
		directory.listFiles { file -> file.extension == TEMP_EXTENSION }?.forEach { it.delete() }
		val limit = quotaBytes()
		if (limit < MIN_CACHE_FILE_BYTES) return
		val target = cacheFile(key)
		val temporary = File(directory, "${target.name}.${UUID.randomUUID()}.tmp")
		try {
			writeFile(temporary, key, data)
			val size = temporary.length()
			if (size !in MIN_CACHE_FILE_BYTES..limit) return
			if (!evictToFit(target, size, limit)) return
			Files.move(
				temporary.toPath(),
				target.toPath(),
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING,
			)
		} catch (_: IOException) {
			temporary.delete()
		} finally {
			temporary.delete()
		}
	}

	private fun documentMetadata(uri: Uri): Pair<Long, Long>? = try {
		resolver.query(
			uri,
			arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
			null,
			null,
			null,
		)?.use { cursor ->
			if (!cursor.moveToFirst()) return null
			val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
			val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
			if (sizeIndex < 0 || modifiedIndex < 0) return null
			val size = cursor.getLong(sizeIndex)
			val modified = cursor.getLong(modifiedIndex)
			if (size > 0 && modified > 0) size to modified else null
		}
	} catch (_: RuntimeException) {
		null
	}

	private fun endpointHash(uri: Uri, size: Long): String? {
		val descriptor = try {
			resolver.openFileDescriptor(uri, "r") ?: return null
		} catch (_: RuntimeException) {
			return null
		}
		return try {
			descriptor.use { parcelFile ->
				FileInputStream(parcelFile.fileDescriptor).use { stream ->
					val channel = stream.channel
					val windowBytes = minOf(size, FINGERPRINT_WINDOW_BYTES.toLong()).toInt()
					val first = readAt(channel, 0, windowBytes) ?: return null
					val last = readAt(channel, (size - windowBytes).coerceAtLeast(0), windowBytes) ?: return null
					val digest = MessageDigest.getInstance("SHA-256")
					digest.update(first)
					digest.update(last)
					digest.digest().toHex()
				}
			}
		} catch (_: IOException) {
			null
		} catch (_: RuntimeException) {
			null
		}
	}

	private fun readAt(channel: FileChannel, position: Long, size: Int): ByteArray? {
		val bytes = ByteBuffer.allocate(size)
		channel.position(position)
		while (bytes.hasRemaining()) {
			if (channel.read(bytes) <= 0) return null
		}
		return bytes.array()
	}

	private fun fullContentHash(uri: Uri): String? = try {
		val digest = MessageDigest.getInstance("SHA-256")
		resolver.openInputStream(uri)?.use { input ->
			val buffer = ByteArray(HASH_BUFFER_BYTES)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				if (count > 0) digest.update(buffer, 0, count)
			}
		} ?: return null
		digest.digest().toHex()
	} catch (_: IOException) {
		null
	} catch (_: RuntimeException) {
		null
	}

	private fun readFile(file: File, key: String, track: TrackInfo): WaveformData {
		FileInputStream(file).use { raw ->
			val checksum = CRC32()
			val checked = CheckedInputStream(BufferedInputStream(raw), checksum)
			val input = DataInputStream(checked)
			if (input.readInt() != MAGIC || input.readInt() != SCHEMA_VERSION) throw IOException("Unsupported waveform cache")
			if (input.readUTF() != key || input.readUTF() != track.id.value) throw IOException("Waveform cache identity mismatch")
			val durationFrames = input.readLong()
			val coveredFrames = input.readLong()
			val sampleRate = input.readInt()
			val channelCount = input.readInt()
			val levelCount = input.readInt()
			if (durationFrames != track.durationFrames || coveredFrames !in 1..durationFrames ||
				sampleRate != track.sampleRate || channelCount != track.channelCount || levelCount !in 1..63
			) throw IOException("Invalid waveform cache header")
			val levels = ArrayList<WaveformLevel>(levelCount)
			var expectedBucketFrames = -1L
			var expectedBucketCount = -1
			var peakBytes = 0L
			for (levelIndex in 0 until levelCount) {
				val bucketFrames = input.readLong()
				val bucketCount = input.readInt()
				if (levelIndex == 0 && bucketFrames > 0) expectedBucketCount = ceilBucketCount(coveredFrames, bucketFrames)
				if (bucketFrames <= 0 || (expectedBucketFrames > 0 && bucketFrames != expectedBucketFrames) ||
					bucketCount != expectedBucketCount || bucketCount !in 1..MAX_BUCKET_COUNT
				) throw IOException("Invalid waveform cache level")
				if (expectedBucketCount == 1 && levelIndex < levelCount - 1) throw IOException("Extra waveform cache levels")
				peakBytes += bucketCount.toLong() * channelCount * Float.SIZE_BYTES * 2
				if (peakBytes > MAX_PEAK_MEMORY_BYTES) throw IOException("Waveform cache exceeds peak memory limit")
				val channels = ArrayList<WaveformChannelPeaks>(channelCount)
				for (channel in 0 until channelCount) {
					val minimum = FloatArray(bucketCount)
					val maximum = FloatArray(bucketCount)
					for (index in 0 until bucketCount) {
						val low = input.readFloat()
						val high = input.readFloat()
						if (!low.isFinite() || !high.isFinite() || low < -1f || high > 1f || low > high) {
							throw IOException("Invalid waveform cache peak")
						}
						minimum[index] = low
						maximum[index] = high
					}
					channels += WaveformChannelPeaks(minimum, maximum)
				}
				levels += WaveformLevel(bucketFrames, channels)
				expectedBucketFrames = bucketFrames * 2
				expectedBucketCount = (bucketCount + 1) / 2
			}
			if (expectedBucketCount != 1) throw IOException("Incomplete waveform cache pyramid")
			val actualChecksum = checksum.value
			val expectedChecksum = input.readLong()
			if (actualChecksum != expectedChecksum || input.read() != -1) throw IOException("Waveform cache checksum mismatch")
			return WaveformData(track.id, durationFrames, coveredFrames, sampleRate, channelCount, levels)
		}
	}

	private fun writeFile(file: File, key: String, data: WaveformData) {
		FileOutputStream(file).use { raw ->
			val checksum = CRC32()
			val output = DataOutputStream(CheckedOutputStream(BufferedOutputStream(raw), checksum))
			output.writeInt(MAGIC)
			output.writeInt(SCHEMA_VERSION)
			output.writeUTF(key)
			output.writeUTF(data.trackId.value)
			output.writeLong(data.durationFrames)
			output.writeLong(data.coveredFrames)
			output.writeInt(data.sampleRate)
			output.writeInt(data.channelCount)
			output.writeInt(data.levels.size)
			for (level in data.levels) {
				output.writeLong(level.bucketFrames)
				output.writeInt(level.bucketCount)
				for (channel in level.channels) {
					for (index in 0 until level.bucketCount) {
						output.writeFloat(channel.minimum[index])
						output.writeFloat(channel.maximum[index])
					}
				}
			}
			output.flush()
			output.writeLong(checksum.value)
			output.flush()
		}
	}

	private fun evictToFit(target: File, newSize: Long, limit: Long): Boolean {
		val entries = directory.listFiles { file -> file.extension == CACHE_EXTENSION } ?: return false
		var total = entries.sumOf { it.length() }
		val replacedSize = target.takeIf(File::exists)?.length() ?: 0L
		for (entry in entries.sortedBy(File::lastModified)) {
			if (total - replacedSize + newSize <= limit) break
			val entrySize = entry.length()
			if (entry != target && entry.delete()) total -= entrySize
		}
		return total - replacedSize + newSize <= limit
	}

	private fun quotaBytes(): Long {
		val available = StatFs(directory.absolutePath).availableBytes
		return minOf(MAX_CACHE_BYTES, available / 10)
	}

	private fun cacheFile(key: String) = File(directory, "$key.$CACHE_EXTENSION")

	private fun ceilBucketCount(frames: Long, bucketFrames: Long): Int {
		val count = 1 + (frames - 1) / bucketFrames
		if (count !in 1..MAX_BUCKET_COUNT.toLong()) throw IOException("Waveform cache bucket count exceeds limit")
		return count.toInt()
	}

	private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

	private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

	private companion object {
		const val MAGIC = 0x4C575631
		const val SCHEMA_VERSION = 1
		const val CACHE_EXTENSION = "wfm"
		const val TEMP_EXTENSION = "tmp"
		const val FINGERPRINT_WINDOW_BYTES = 32 * 1024
		const val HASH_BUFFER_BYTES = 64 * 1024
		const val MAX_BUCKET_COUNT = 16 * 1024 * 1024
		const val MAX_PEAK_MEMORY_BYTES = 64L * 1024 * 1024
		const val MIN_CACHE_FILE_BYTES = 64L
		const val MAX_CACHE_BYTES = 256L * 1024 * 1024
	}
}
