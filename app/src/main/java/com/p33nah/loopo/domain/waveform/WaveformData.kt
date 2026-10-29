package com.p33nah.loopo.domain.waveform

import com.p33nah.loopo.domain.model.TrackId
import kotlin.math.roundToLong

class WaveformChannelPeaks(
	val minimum: FloatArray,
	val maximum: FloatArray,
) {
	init {
		require(minimum.size == maximum.size)
	}

	val bucketCount: Int get() = minimum.size
}

data class WaveformLevel(
	val bucketFrames: Long,
	val channels: List<WaveformChannelPeaks>,
) {
	init {
		require(bucketFrames > 0)
		require(channels.isNotEmpty())
		require(channels.all { it.bucketCount == channels.first().bucketCount })
	}

	val bucketCount: Int get() = channels.first().bucketCount
}

data class WaveformData(
	val trackId: TrackId,
	val durationFrames: Long,
	val coveredFrames: Long,
	val sampleRate: Int,
	val channelCount: Int,
	val levels: List<WaveformLevel>,
	val isComplete: Boolean = true,
) {
	init {
		require(durationFrames > 0)
		require(coveredFrames in 0..durationFrames)
		require(sampleRate > 0)
		require(channelCount in 1..2)
		require(!isComplete || coveredFrames > 0)
		require(levels.isNotEmpty())
		require(levels.all { it.channels.size == channelCount })
		require(levels.zipWithNext().all { (current, next) -> next.bucketFrames == current.bucketFrames * 2 })
	}
}

enum class WaveformPhase {
	VERIFYING_SOURCE,
	DECODING,
}

sealed interface WaveformState {
	data object Empty : WaveformState
	data class Loading(
		val trackId: TrackId,
		val phase: WaveformPhase,
		val processedFrames: Long,
		val totalFrames: Long,
		val preview: WaveformData?,
	) : WaveformState
	data class Ready(val data: WaveformData) : WaveformState
	data class Failed(val trackId: TrackId) : WaveformState
}

data class WaveformViewport(
	val startFrame: Long,
	val visibleFrames: Long,
	val durationFrames: Long,
) {
	init {
		require(durationFrames > 0)
		require(visibleFrames in 1..durationFrames)
		require(startFrame in 0..(durationFrames - visibleFrames))
	}

	fun zoomAt(zoomFactor: Float, anchorFraction: Float, minimumVisibleFrames: Long): WaveformViewport {
		if (!zoomFactor.isFinite() || zoomFactor <= 0f) return this
		val minimum = minimumVisibleFrames.coerceIn(1, durationFrames)
		val nextVisible = (visibleFrames / zoomFactor).roundToLong().coerceIn(minimum, durationFrames)
		val anchor = anchorFraction.coerceIn(0f, 1f).toDouble()
		val anchoredFrame = startFrame + (visibleFrames * anchor).roundToLong()
		val nextStart = (anchoredFrame - (nextVisible * anchor).roundToLong())
			.coerceIn(0, durationFrames - nextVisible)
		return copy(startFrame = nextStart, visibleFrames = nextVisible)
	}

	fun panByPixels(deltaPixels: Float, widthPixels: Float): WaveformViewport {
		if (!deltaPixels.isFinite() || !widthPixels.isFinite() || widthPixels <= 0f) return this
		val frameDelta = (deltaPixels * visibleFrames / widthPixels).roundToLong()
		val nextStart = (startFrame - frameDelta).coerceIn(0, durationFrames - visibleFrames)
		return copy(startFrame = nextStart)
	}

	fun frameAt(fraction: Float): Long {
		val x = fraction.coerceIn(0f, 1f).toDouble()
		return (startFrame + (visibleFrames * x).roundToLong()).coerceIn(0, durationFrames)
	}

	companion object {
		fun full(durationFrames: Long) = WaveformViewport(0, durationFrames, durationFrames)
	}
}
