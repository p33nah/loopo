package com.p33nah.loopo.domain.audio

import java.io.Closeable

data class PcmFormat(
	val sampleRate: Int,
	val channelCount: Int,
) {
	init {
		require(sampleRate > 0)
		require(channelCount in 1..2)
	}
}

data class PcmBlock(
	val format: PcmFormat,
	val frameCount: Int,
	val samples: FloatArray,
) {
	init {
		require(frameCount > 0)
		require(samples.size == frameCount * format.channelCount)
	}
}

sealed interface PcmReadResult {
	data class Frames(val block: PcmBlock) : PcmReadResult
	data object EndOfStream : PcmReadResult
	data class Failed(val reason: PcmDecodeFailure) : PcmReadResult
}

sealed interface PcmDecoderResult {
	data class Ready(val decoder: PcmDecoder) : PcmDecoderResult
	data class Failed(val reason: PcmDecodeFailure) : PcmDecoderResult
}

sealed interface PcmSeekResult {
	data class Seeked(val frame: Long) : PcmSeekResult
	data class Failed(val reason: PcmDecodeFailure) : PcmSeekResult
}

enum class PcmDecodeFailure {
	UNSUPPORTED_FORMAT,
	UNSUPPORTED_CHANNEL_LAYOUT,
	UNSUPPORTED_PCM_ENCODING,
	INVALID_FILE,
	DECODER_UNAVAILABLE,
	READ_FAILED,
}

interface PcmDecoder : Closeable {
	val format: PcmFormat
	fun readFrames(maxFrames: Int): PcmReadResult
	fun seekToFrame(frame: Long): PcmSeekResult
}
