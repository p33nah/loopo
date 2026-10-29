package com.p33nah.loopo.domain.audio

data class PlaybackSnapshot(
	val positionFrame: Long = 0,
	val isPlaying: Boolean = false,
	val isEnded: Boolean = false,
	val underrunCount: Long = 0,
	val estimatedLatencyMillis: Double? = null,
	val error: PlaybackError? = null,
)

enum class PlaybackError {
	OUTPUT_UNAVAILABLE,
	AUDIO_FOCUS_DENIED,
	DECODER_READ_FAILED,
}
