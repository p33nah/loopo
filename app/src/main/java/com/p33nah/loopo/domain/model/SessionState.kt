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

	fun moveATo(frame: Long, durationFrames: Long, sampleRate: Int): LoopRegion {
		val minSpan = minSpanFrames(durationFrames, sampleRate)
		return copy(aFrame = frame.coerceIn(0, (bFrame - minSpan).coerceAtLeast(0)))
	}

	fun moveBTo(frame: Long, durationFrames: Long, sampleRate: Int): LoopRegion {
		val minSpan = minSpanFrames(durationFrames, sampleRate)
		val minB = (aFrame + minSpan).coerceAtMost(durationFrames)
		return copy(bFrame = frame.coerceIn(minB, durationFrames))
	}

	fun effectiveCrossfadeFrames(sampleRate: Int): Long = minOf(
		crossfadeMs.toLong() * sampleRate.coerceAtLeast(1) / 1_000,
		(bFrame - aFrame) / MAX_CROSSFADE_LOOP_DIVISOR,
	)

	private fun minSpanFrames(durationFrames: Long, sampleRate: Int): Long = minOf(
		durationFrames.coerceAtLeast(1),
		(sampleRate / 100).coerceAtLeast(1).toLong(),
	)

	companion object {
		private const val MAX_CROSSFADE_LOOP_DIVISOR = 4
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
