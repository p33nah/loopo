package com.p33nah.loopo.domain.model

data class LoopRegion(
	val aFrame: Long,
	val bFrame: Long,
	val enabled: Boolean,
	val crossfadeMs: Int,
) {
	init {
		require(aFrame >= 0)
		require(aFrame < bFrame)
		require(crossfadeMs in 0..100)
	}
}

data class SessionState(
	val trackId: TrackId,
	val durationFrames: Long,
	val positionFrame: Long,
	val playbackRate: Float,
	val loop: LoopRegion,
	val viewStartFrame: Long,
	val viewDurationFrames: Long,
	val schemaVersion: Int = 1,
) {
	init {
		require(durationFrames > 0)
		require(positionFrame in 0..durationFrames)
		require(playbackRate.isFinite() && playbackRate in 0.25f..2f)
		require(loop.bFrame <= durationFrames)
		require(viewStartFrame in 0 until durationFrames)
		require(viewDurationFrames in 1..durationFrames)
		require(viewStartFrame <= durationFrames - viewDurationFrames)
		require(schemaVersion > 0)
	}
}
