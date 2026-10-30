package com.p33nah.loopo.data.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.p33nah.loopo.domain.audio.PcmDecodeFailure
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmReadResult
import com.p33nah.loopo.domain.audio.PcmSeekResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WavPcmDecoderTest {
	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private val resolver get() = context.contentResolver

	@Test
	fun decodesSupportedPcmWidthsToNormalizedSamplesAndSeeksByFrame() {
		val fixtures = listOf(
			Triple(8, byteArrayOf(0, 128.toByte(), 0xff.toByte()), floatArrayOf(-1f, 0f, 127 / 128f)),
			Triple(16, byteArrayOf(0, 0x80.toByte(), 0, 0, 0xff.toByte(), 0x7f), floatArrayOf(-1f, 0f, 32767 / 32768f)),
			Triple(24, byteArrayOf(0, 0, 0x80.toByte(), 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0x7f), floatArrayOf(-1f, 0f, 8_388_607 / 8_388_608f)),
			Triple(32, byteArrayOf(0, 0, 0, 0x80.toByte(), 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f), floatArrayOf(-1f, 0f, 2_147_483_647 / 2_147_483_648f)),
		)

		fixtures.forEach { (width, samples, expected) ->
			val file = WavFixture.write(context, "pcm-$width.wav", WavFixture.pcmWave(48_000, 1, width, samples))
			val decoder = (WavPcmDecoder.open(resolver, Uri.fromFile(file)) as PcmDecoderResult.Ready).decoder
			decoder.use {
				assertEquals(48_000, it.format.sampleRate)
				assertEquals(1, it.format.channelCount)
				val decoded = it.readFrames(3) as PcmReadResult.Frames
				assertEquals(3, decoded.block.frameCount)
				assertArrayEquals(expected, decoded.block.samples, 0.000001f)
				assertEquals(PcmSeekResult.Seeked(2), it.seekToFrame(2))
				val last = it.readFrames(1) as PcmReadResult.Frames
				assertArrayEquals(floatArrayOf(expected.last()), last.block.samples, 0.000001f)
			}
		}
	}

	@Test
	fun malformedWaveAndOutOfRangeReadAndSeekAreReportedAsInvalid() {
		val badHeader = WavFixture.write(context, "not-a-wave.wav", byteArrayOf(1, 2, 3, 4))
		val malformed = WavPcmDecoder.open(resolver, Uri.fromFile(badHeader)) as PcmDecoderResult.Failed
		assertEquals(PcmDecodeFailure.INVALID_FILE, malformed.reason)

		val unsupported = WavFixture.write(context, "unsupported.wav", WavFixture.pcmWave(48_000, 1, 20, byteArrayOf(0, 0)))
		val unsupportedResult = WavPcmDecoder.open(resolver, Uri.fromFile(unsupported)) as PcmDecoderResult.Failed
		assertEquals(PcmDecodeFailure.UNSUPPORTED_PCM_ENCODING, unsupportedResult.reason)

		val file = WavFixture.write(context, "range.wav", WavFixture.pcmWave(48_000, 1, 16, byteArrayOf(0, 0, 1, 0)))
		val decoder = (WavPcmDecoder.open(resolver, Uri.fromFile(file)) as PcmDecoderResult.Ready).decoder
		decoder.use {
			assertEquals(PcmDecodeFailure.READ_FAILED, (it.readFrames(0) as PcmReadResult.Failed).reason)
			assertEquals(PcmDecodeFailure.INVALID_FILE, (it.seekToFrame(-1) as PcmSeekResult.Failed).reason)
			assertEquals(PcmDecodeFailure.INVALID_FILE, (it.seekToFrame(3) as PcmSeekResult.Failed).reason)
		}
	}
}
