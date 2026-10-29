package com.p33nah.loopo.data.audio

import android.content.ContentResolver
import android.net.Uri
import com.p33nah.loopo.domain.audio.PcmBlock
import com.p33nah.loopo.domain.audio.PcmDecodeFailure
import com.p33nah.loopo.domain.audio.PcmDecoder
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmFormat
import com.p33nah.loopo.domain.audio.PcmReadResult
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmDecoder private constructor(
	private val input: InputStream,
	private val encoding: Int,
	private val bitsPerSample: Int,
	private val blockAlign: Int,
	private val dataBytes: Long,
	override val format: PcmFormat,
) : PcmDecoder {
	private var bytesRemaining = dataBytes
	private var closed = false

	val durationFrames: Long = dataBytes / blockAlign

	override fun readFrames(maxFrames: Int): PcmReadResult {
		if (closed) return PcmReadResult.Failed(PcmDecodeFailure.READ_FAILED)
		if (maxFrames <= 0) return PcmReadResult.Failed(PcmDecodeFailure.READ_FAILED)
		if (bytesRemaining == 0L) return PcmReadResult.EndOfStream
		val frames = minOf(maxFrames.toLong(), bytesRemaining / blockAlign).toInt()
		if (frames <= 0) return PcmReadResult.Failed(PcmDecodeFailure.INVALID_FILE)
		val byteCount = frames * blockAlign
		val bytes = ByteArray(byteCount)
		return try {
			input.readFully(bytes)
			bytesRemaining -= byteCount
			val samples = FloatArray(frames * format.channelCount)
			val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
			for (index in samples.indices) {
				samples[index] = when (encoding) {
					PCM -> when (bitsPerSample) {
						8 -> ((buffer.get().toInt() and 0xff) - 128) / 128f
						16 -> buffer.short / 32768f
						24 -> {
							val raw = (buffer.get().toInt() and 0xff) or
								((buffer.get().toInt() and 0xff) shl 8) or
								((buffer.get().toInt() and 0xff) shl 16)
							((if (raw and 0x800000 != 0) raw or -0x1000000 else raw) / 8_388_608f)
						}
						32 -> buffer.int / 2_147_483_648f
						else -> return PcmReadResult.Failed(PcmDecodeFailure.UNSUPPORTED_PCM_ENCODING)
					}
					IEEE_FLOAT -> buffer.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
					else -> return PcmReadResult.Failed(PcmDecodeFailure.UNSUPPORTED_PCM_ENCODING)
				}
			}
			PcmReadResult.Frames(PcmBlock(format, frames, samples))
		} catch (_: IOException) {
			PcmReadResult.Failed(PcmDecodeFailure.READ_FAILED)
		}
	}

	override fun close() {
		if (!closed) {
			closed = true
			input.close()
		}
	}

	companion object {
		private const val PCM = 1
		private const val IEEE_FLOAT = 3
		private const val EXTENSIBLE = 0xfffe
		private const val MAX_CHUNK_SCAN = 64

		fun open(resolver: ContentResolver, uri: Uri): PcmDecoderResult {
			val input = try {
				resolver.openInputStream(uri) ?: return PcmDecoderResult.Failed(PcmDecodeFailure.READ_FAILED)
			} catch (_: SecurityException) {
				return PcmDecoderResult.Failed(PcmDecodeFailure.READ_FAILED)
			} catch (_: IOException) {
				return PcmDecoderResult.Failed(PcmDecodeFailure.READ_FAILED)
			}
			return try {
				val result = parse(input)
				result
			} catch (_: EOFException) {
				input.close()
				PcmDecoderResult.Failed(PcmDecodeFailure.INVALID_FILE)
			} catch (_: IOException) {
				input.close()
				PcmDecoderResult.Failed(PcmDecodeFailure.READ_FAILED)
			} catch (_: IllegalArgumentException) {
				input.close()
				PcmDecoderResult.Failed(PcmDecodeFailure.INVALID_FILE)
			}
		}

		private fun parse(input: InputStream): PcmDecoderResult {
			val header = ByteArray(12)
			input.readFully(header)
			if (header.ascii(0, 4) != "RIFF" || header.ascii(8, 4) != "WAVE") {
				input.close()
				return PcmDecoderResult.Failed(PcmDecodeFailure.INVALID_FILE)
			}
			var encoding = 0
			var channels = 0
			var sampleRate = 0
			var blockAlign = 0
			var bitsPerSample = 0
			var foundFormat = false
			repeat(MAX_CHUNK_SCAN) {
				val chunkHeader = ByteArray(8)
				input.readFully(chunkHeader)
				val id = chunkHeader.ascii(0, 4)
				val size = chunkHeader.u32(4)
				if (size > Int.MAX_VALUE && id != "data") throw IOException("Oversized WAV chunk")
				when (id) {
					"fmt " -> {
						if (size < 16) throw EOFException("Short fmt chunk")
						val bytes = ByteArray(minOf(size, 40L).toInt())
						input.readFully(bytes)
						var formatCode = bytes.u16(0)
						channels = bytes.u16(2)
						sampleRate = bytes.u32(4).toInt()
						blockAlign = bytes.u16(12)
						bitsPerSample = bytes.u16(14)
						if (formatCode == EXTENSIBLE && bytes.size >= 40) {
							formatCode = bytes.u16(24).takeIf { bytes.hasStandardSubFormat(it) } ?: 0
						}
						val extra = size - bytes.size
						input.skipFully(extra + (size and 1L))
						encoding = formatCode
						foundFormat = true
				}
				"data" -> {
					if (!foundFormat) throw EOFException("fmt chunk must precede data")
					if (channels !in 1..2) return failAndClose(input, PcmDecodeFailure.UNSUPPORTED_CHANNEL_LAYOUT)
					val supportedBits = if (encoding == PCM) bitsPerSample in setOf(8, 16, 24, 32) else encoding == IEEE_FLOAT && bitsPerSample == 32
					if (!supportedBits) return failAndClose(input, PcmDecodeFailure.UNSUPPORTED_PCM_ENCODING)
					val expectedAlign = channels * (bitsPerSample / 8)
					if (sampleRate <= 0 || blockAlign != expectedAlign || size == 0L || size % blockAlign != 0L) {
						return failAndClose(input, PcmDecodeFailure.INVALID_FILE)
					}
					return PcmDecoderResult.Ready(
						WavPcmDecoder(input, encoding, bitsPerSample, blockAlign, size, PcmFormat(sampleRate, channels)),
					)
				}
				else -> input.skipFully(size + (size and 1L))
				}
			}
			return failAndClose(input, PcmDecodeFailure.INVALID_FILE)
		}

		private fun failAndClose(input: InputStream, reason: PcmDecodeFailure): PcmDecoderResult {
			input.close()
			return PcmDecoderResult.Failed(reason)
		}

		private fun ByteArray.ascii(offset: Int, length: Int) = String(this, offset, length, Charsets.US_ASCII)
		private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
		private fun ByteArray.u32(offset: Int): Long = (this[offset].toLong() and 0xff) or
			((this[offset + 1].toLong() and 0xff) shl 8) or
			((this[offset + 2].toLong() and 0xff) shl 16) or
			((this[offset + 3].toLong() and 0xff) shl 24)

		private fun ByteArray.hasStandardSubFormat(code: Int): Boolean {
			if (code !in setOf(PCM, IEEE_FLOAT)) return false
			val tail = byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71)
			return this[26] == 0.toByte() && this[27] == 0.toByte() &&
				(0 until tail.size).all { this[28 + it] == tail[it] }
		}

		private fun InputStream.readFully(target: ByteArray) {
			var offset = 0
			while (offset < target.size) {
				val read = read(target, offset, target.size - offset)
				if (read < 0) throw EOFException()
				offset += read
			}
		}

		private fun InputStream.skipFully(count: Long) {
			var remaining = count
			while (remaining > 0) {
				val skipped = skip(remaining)
				if (skipped > 0) remaining -= skipped
				else if (read() < 0) throw EOFException()
				else remaining--
			}
		}
	}
}
