package com.p33nah.loopo.domain.audio

import com.p33nah.loopo.domain.model.LoopRegion

data class PlaybackSnapshot(
	val positionFrame: Long = 0,
	val isPlaying: Boolean = false,
	val isEnded: Boolean = false,
	val underrunCount: Long = 0,
	val estimatedLatencyMillis: Double? = null,
	val error: PlaybackError? = null,
	val loopRegion: LoopRegion? = null,
	val effectiveCrossfadeFrames: Long = 0,
	val isLoopCommandPending: Boolean = false,
)

enum class PlaybackError {
	OUTPUT_UNAVAILABLE,
	AUDIO_FOCUS_DENIED,
	DECODER_READ_FAILED,
}
