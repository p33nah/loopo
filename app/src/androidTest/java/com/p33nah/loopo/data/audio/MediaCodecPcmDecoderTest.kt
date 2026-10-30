package com.p33nah.loopo.data.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmReadResult
import com.p33nah.loopo.domain.audio.PcmSeekResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class MediaCodecPcmDecoderTest {
	@Test
	fun decodesGeneratedMp3AndFlacThroughPlatformCodecAndSeeks() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val context = instrumentation.targetContext
		listOf("sine-440hz.mp3", "sine-440hz.flac").forEach { assetName ->
			val file = File(context.cacheDir, assetName)
			instrumentation.context.assets.open(assetName).use { input ->
				file.outputStream().use { output -> input.copyTo(output) }
			}
			val opened = MediaCodecPcmDecoder.open(context, Uri.fromFile(file))
			val decoder = (opened as? PcmDecoderResult.Ready)?.decoder
				?: error("Platform decoder could not open generated $assetName: $opened")
			decoder.use {
				assertEquals(48_000, it.format.sampleRate)
				assertEquals(1, it.format.channelCount)
				var totalFrames = 0
				var peak = 0f
				while (true) {
					when (val result = it.readFrames(2_048)) {
						is PcmReadResult.Frames -> {
							totalFrames += result.block.frameCount
							result.block.samples.forEach { sample ->
								assertTrue("$assetName contains a non-finite decoded sample", sample.isFinite())
								peak = max(peak, abs(sample))
							}
						}
						PcmReadResult.EndOfStream -> break
						is PcmReadResult.Failed -> error("$assetName decode failed: ${result.reason}")
					}
				}
				assertTrue("Decoded frame count for $assetName was $totalFrames", totalFrames in 40_000..52_000)
				assertTrue("Generated sine samples for $assetName were silent", peak > 0.05f)
				assertEquals(PcmSeekResult.Seeked(24_000), it.seekToFrame(24_000))
				assertTrue("$assetName yielded no samples after seeking", it.readFrames(2_048) is PcmReadResult.Frames)
			}
		}
	}
}
