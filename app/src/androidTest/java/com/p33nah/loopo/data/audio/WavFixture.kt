package com.p33nah.loopo.data.audio

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

internal object WavFixture {
	fun pcmFile(context: Context, name: String, sampleRate: Int = 48_000, bitsPerSample: Int = 16, frames: Int = 192_000): File {
		val samples = when (bitsPerSample) {
			8 -> ByteArray(frames) { 128.toByte() }
			16 -> ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
				repeat(frames) { frame ->
					putShort((sin(2.0 * PI * 440 * frame / sampleRate) * 12_000).toInt().toShort())
				}
			}.array()
			24 -> ByteArray(frames * 3)
			32 -> ByteArray(frames * 4)
			else -> error("Unsupported test fixture width")
		}
		return write(context, name, pcmWave(sampleRate, 1, bitsPerSample, samples))
	}

	fun pcmWave(sampleRate: Int, channels: Int, bitsPerSample: Int, samples: ByteArray): ByteArray {
		val bytesPerSample = bitsPerSample / 8
		val blockAlign = channels * bytesPerSample
		val output = ByteArrayOutputStream(44 + samples.size)
		val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
		header.put("RIFF".toByteArray(Charsets.US_ASCII))
			.putInt(36 + samples.size)
			.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
			.putInt(16)
			.putShort(1)
			.putShort(channels.toShort())
			.putInt(sampleRate)
			.putInt(sampleRate * blockAlign)
			.putShort(blockAlign.toShort())
			.putShort(bitsPerSample.toShort())
			.put("data".toByteArray(Charsets.US_ASCII))
			.putInt(samples.size)
		output.write(header.array())
		output.write(samples)
		return output.toByteArray()
	}

	fun write(context: Context, name: String, bytes: ByteArray): File = File(context.cacheDir, name).apply {
		writeBytes(bytes)
	}
}
