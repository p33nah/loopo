package com.p33nah.loopo.data.waveform

import android.net.Uri
import com.p33nah.loopo.domain.audio.PcmBlock
import com.p33nah.loopo.domain.audio.PcmFormat
import com.p33nah.loopo.domain.model.AudioContainer
import com.p33nah.loopo.domain.model.TrackId
import com.p33nah.loopo.domain.model.TrackInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaveformPyramidBuilderTest {
	private val track = TrackInfo(
		id = TrackId("waveform"),
		uri = Uri.parse("content://audio/waveform"),
		displayName = "test.wav",
		container = AudioContainer.WAV,
		durationFrames = 7,
		sampleRate = 100,
		channelCount = 2,
		sourceMimeType = "audio/wav",
	)
	private val samples = floatArrayOf(
		-2f, 0.25f,
		Float.NaN, 0.5f,
		0.5f, Float.POSITIVE_INFINITY,
		0.1f, -0.4f,
		-0.3f, 0.8f,
		0.9f, -0.1f,
		0.2f, 0.3f,
	)

	@Test
	fun pyramidPeaksAreIndependentOfDecoderChunkingAndIncludeFinalPartialBucket() {
		val singleChunk = WaveformPyramidBuilder(track, bucketFrames = 2)
		singleChunk.add(block(0, 7))
		val splitChunks = WaveformPyramidBuilder(track, bucketFrames = 2)
		splitChunks.add(block(0, 2))
		splitChunks.add(block(2, 3))
		splitChunks.add(block(5, 2))

		val actual = singleChunk.snapshot(includePartialBucket = true)
		val chunked = splitChunks.snapshot(includePartialBucket = true)
		assertEquals(7L, actual.coveredFrames)
		assertEquals(4, actual.levels.first().bucketCount)
		assertArrayEquals(floatArrayOf(-1f, 0.1f, -0.3f, 0.2f), actual.levels.first().channels[0].minimum, 0f)
		assertArrayEquals(floatArrayOf(0f, 0.5f, 0.9f, 0.2f), actual.levels.first().channels[0].maximum, 0f)
		assertArrayEquals(floatArrayOf(0.25f, -0.4f, -0.1f, 0.3f), actual.levels.first().channels[1].minimum, 0f)
		assertArrayEquals(floatArrayOf(0.5f, 0f, 0.8f, 0.3f), actual.levels.first().channels[1].maximum, 0f)
		assertEquals(actual.levels.size, chunked.levels.size)
		actual.levels.zip(chunked.levels).forEach { (left, right) ->
			assertEquals(left.bucketFrames, right.bucketFrames)
			left.channels.zip(right.channels).forEach { (leftChannel, rightChannel) ->
				assertArrayEquals(leftChannel.minimum, rightChannel.minimum, 0f)
				assertArrayEquals(leftChannel.maximum, rightChannel.maximum, 0f)
			}
		}
	}

	@Test
	fun frameLimitAndFormatValidationBoundWhatTheBuilderConsumes() {
		val builder = WaveformPyramidBuilder(track, bucketFrames = 2)
		builder.add(block(0, 3), frameLimit = 2)
		assertEquals(2L, builder.processedFrames)
		assertEquals(1, builder.snapshot().levels.first().bucketCount)
		assertThrows(IllegalStateException::class.java) {
			builder.add(PcmBlock(PcmFormat(200, 2), 1, floatArrayOf(0f, 0f)))
		}
	}

	private fun block(startFrame: Int, frameCount: Int): PcmBlock = PcmBlock(
		format = PcmFormat(track.sampleRate, track.channelCount),
		frameCount = frameCount,
		samples = samples.copyOfRange(startFrame * track.channelCount, (startFrame + frameCount) * track.channelCount),
	)
}
