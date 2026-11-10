package com.p33nah.loopo.data.audio

internal data class PlaybackFrameBudget(val outputFrames: Int, val remainderUnits: Int)

internal object PlaybackFrameBudgetCalculator {
	fun forInput(inputFrames: Int, playbackRatePercent: Int, remainderUnits: Int): PlaybackFrameBudget {
		require(inputFrames > 0)
		require(playbackRatePercent in 25..200)
		require(remainderUnits in 0 until playbackRatePercent)
		val sourceUnits = inputFrames.toLong() * 100 + remainderUnits
		val outputFrames = sourceUnits / playbackRatePercent
		require(outputFrames <= Int.MAX_VALUE)
		return PlaybackFrameBudget(
			outputFrames = outputFrames.toInt(),
			remainderUnits = (sourceUnits % playbackRatePercent).toInt(),
		)
	}

	fun maxInputFrames(
		outputCapacity: Int,
		playbackRatePercent: Int,
		remainderUnits: Int,
		maxInputFrames: Int,
	): Int {
		require(playbackRatePercent in 25..200)
		require(remainderUnits in 0 until playbackRatePercent)
		require(maxInputFrames >= 0)
		if (outputCapacity <= 1) return 0
		val availableSourceUnits = (outputCapacity - 1L) * playbackRatePercent - remainderUnits
		val availableInputFrames = (availableSourceUnits / 100L).coerceIn(0L, maxInputFrames.toLong())
		return availableInputFrames.toInt()
	}
}
