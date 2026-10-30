package com.p33nah.loopo.data.audio

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.p33nah.loopo.domain.audio.PcmDecodeFailure
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmReadResult
import com.p33nah.loopo.domain.audio.PcmSeekResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WavPcmDecoderTest {
	private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

	@Test
	fun parsesLittleEndianPcmAndSeeksToTheRequestedFrame() {
		val decoder = open(wav(encoding = 1, channels = 1, bits = 16, data = byteArrayOf(
			0x00, 0x80.toByte(), 0x00, 0x00, 0xff.toByte(), 0x7f,
		)))
		try {
			assertEquals(3L, decoder.durationFrames)
			assertEquals(PcmSeekResult.Seeked(1), decoder.seekToFrame(1))
			val result = decoder.readFrames(2) as PcmReadResult.Frames
			assertArrayEquals(floatArrayOf(0f, 32767f / 32768f), result.block.samples, 0.000001f)
			assertEquals(PcmReadResult.EndOfStream, decoder.readFrames(1))
		} finally {
			decoder.close()
		}
	}

	@Test
	fun decodesUnsigned8BitSigned24BitAndClampedFloatSamples() {
		val pcm8 = open(wav(1, 1, 8, byteArrayOf(0, 0x80.toByte(), 0xff.toByte())))
		assertArrayEquals(floatArrayOf(-1f, 0f, 127f / 128f), (pcm8.readFrames(3) as PcmReadResult.Frames).block.samples, 0.000001f)
		pcm8.close()

		val pcm24 = open(wav(1, 1, 24, byteArrayOf(
			0x00, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0xff.toByte(), 0xff.toByte(), 0x7f,
		)))
		assertArrayEquals(floatArrayOf(-1f, 0f, 8_388_607f / 8_388_608f), (pcm24.readFrames(3) as PcmReadResult.Frames).block.samples, 0.000001f)
		pcm24.close()

		val floats = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
			.putFloat(Float.NaN).putFloat(2f).putFloat(-2f).array()
		val floatDecoder = open(wav(3, 1, 32, floats))
		assertArrayEquals(floatArrayOf(0f, 1f, -1f), (floatDecoder.readFrames(3) as PcmReadResult.Frames).block.samples, 0f)
		floatDecoder.close()
	}

	@Test
	fun malformedHeaderFailsOnOpenAndTruncatedPayloadFailsOnRead() {
		val malformed = wav(1, 1, 16, byteArrayOf(0, 0)).also { it[32] = 3 }
		val malformedResult = WavPcmDecoder.open(resolver, uriFor(malformed)) as PcmDecoderResult.Failed
		assertEquals(PcmDecodeFailure.INVALID_FILE, malformedResult.reason)

		val truncated = wav(1, 1, 16, byteArrayOf(0, 0)).also { it.putU32(40, 4) }
		val decoder = open(truncated)
		try {
			assertEquals(2L, decoder.durationFrames)
			val read = decoder.readFrames(2) as PcmReadResult.Failed
			assertEquals(PcmDecodeFailure.READ_FAILED, read.reason)
			assertTrue(decoder.seekToFrame(Long.MAX_VALUE) is PcmSeekResult.Failed)
		} finally {
			decoder.close()
		}
	}

	private fun open(bytes: ByteArray): WavPcmDecoder {
		val result = WavPcmDecoder.open(resolver, uriFor(bytes))
		assertTrue("Expected WAV decoder, got $result", result is PcmDecoderResult.Ready)
		return (result as PcmDecoderResult.Ready).decoder as WavPcmDecoder
	}

	private fun uriFor(bytes: ByteArray): Uri {
		val file = File.createTempFile("loopo-decoder-", ".wav")
		file.writeBytes(bytes)
		file.deleteOnExit()
		return Uri.fromFile(file)
	}

	private fun wav(encoding: Int, channels: Int, bits: Int, data: ByteArray): ByteArray {
		val blockAlign = channels * bits / 8
		val buffer = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
		buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data.size)
		buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
		buffer.putShort(encoding.toShort()).putShort(channels.toShort()).putInt(8_000)
		buffer.putInt(8_000 * blockAlign).putShort(blockAlign.toShort()).putShort(bits.toShort())
		buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(data.size).put(data)
		return buffer.array()
	}

	private fun ByteArray.putU32(offset: Int, value: Int) {
		ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
	}
}
