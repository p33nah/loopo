package com.p33nah.loopo.data.audio

import com.p33nah.loopo.domain.model.LoopRegion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun equalPowerCrossfade(tail: FloatArray, head: FloatArray, frameCount: Int, channelCount: Int): FloatArray {
	require(frameCount >= 0 && channelCount > 0)
	require(tail.size == frameCount * channelCount && head.size == tail.size)
	val mixed = FloatArray(tail.size)
	for (frame in 0 until frameCount) {
		val progress = if (frameCount <= 1) 1.0 else frame.toDouble() / (frameCount - 1)
		val angle = progress * PI / 2.0
		val tailGain = cos(angle).toFloat()
		val headGain = sin(angle).toFloat()
		for (channel in 0 until channelCount) {
			val sample = frame * channelCount + channel
			mixed[sample] = (tail[sample] * tailGain + head[sample] * headGain).coerceIn(-1f, 1f)
		}
	}
	return mixed
}

internal class CachedLoopCycle private constructor(
	val region: LoopRegion,
	val channelCount: Int,
	val frameCount: Int,
	private val prefixFrames: Int,
	val fadeFrames: Int,
	private val samples: FloatArray,
) {
	class Cursor internal constructor(internal val offset: Int)

	class Segment internal constructor(
		val sampleOffsetFrames: Int,
		val sourceStartFrame: Long,
		val frameCount: Int,
	)

	class Prime internal constructor(
		val samples: FloatArray,
		val sourceStartFrame: Long,
		val next: Cursor,
	)

	class Window internal constructor(
		val samples: FloatArray,
		val sourceStartFrame: Long,
		val frameCount: Int,
		val next: Cursor,
		val segments: List<Segment>,
	)

	/** Maps a source position to the equivalent phase in the crossfaded cycle. */
	fun cursorAt(sourceFrame: Long): Cursor {
		require(sourceFrame in region.aFrame until region.bFrame)
		val fadeStart = region.aFrame + fadeFrames
		val fadeEnd = region.bFrame - fadeFrames
		val offset = when {
			sourceFrame < fadeStart -> prefixFrames + (sourceFrame - region.aFrame).toInt()
			sourceFrame < fadeEnd -> (sourceFrame - fadeStart).toInt()
			else -> prefixFrames + (sourceFrame - fadeEnd).toInt()
		}
		return Cursor(offset % frameCount)
	}

	fun samplesFrom(sourceFrame: Long, frameCount: Int): FloatArray =
		take(cursorAt(sourceFrame), frameCount).samples

	fun primeAt(sourceFrame: Long, frameCount: Int): Prime {
		val cursor = cursorAt(sourceFrame)
		val window = take(cursor, frameCount)
		return Prime(window.samples, sourceFrame, window.next)
	}

	fun take(cursor: Cursor, requestedFrames: Int): Window {
		require(cursor.offset in 0 until frameCount)
		require(requestedFrames >= 0)
		val output = FloatArray(Math.multiplyExact(requestedFrames, channelCount))
		val segments = ArrayList<Segment>()
		var sourceOffset = cursor.offset
		var copiedFrames = 0
		while (copiedFrames < requestedFrames) {
			val contiguousFrames = if (sourceOffset < prefixFrames) {
				prefixFrames - sourceOffset
			} else {
				frameCount - sourceOffset
			}
			val chunkFrames = minOf(requestedFrames - copiedFrames, contiguousFrames)
			samples.copyInto(
				output,
				destinationOffset = copiedFrames * channelCount,
				startIndex = sourceOffset * channelCount,
				endIndex = (sourceOffset + chunkFrames) * channelCount,
			)
			segments += Segment(copiedFrames, sourceFrameAt(Cursor(sourceOffset)), chunkFrames)
			copiedFrames += chunkFrames
			sourceOffset += chunkFrames
			if (sourceOffset == frameCount) sourceOffset = 0
		}
		return Window(output, sourceFrameAt(cursor), requestedFrames, advance(cursor, requestedFrames), segments)
	}

	fun advance(cursor: Cursor, frameCount: Int): Cursor {
		require(cursor.offset in 0 until this.frameCount)
		require(frameCount >= 0)
		return Cursor(((cursor.offset.toLong() + frameCount) % this.frameCount).toInt())
	}

	fun sourceFrameAt(cursor: Cursor): Long {
		require(cursor.offset in 0 until frameCount)
		return if (cursor.offset < prefixFrames) {
			region.aFrame + fadeFrames + cursor.offset
		} else {
			region.bFrame - fadeFrames + cursor.offset - prefixFrames
		}
	}

	companion object {
		fun create(
			region: LoopRegion,
			sourceSamples: FloatArray,
			channelCount: Int,
			fadeFrames: Int,
		): CachedLoopCycle? {
			if (channelCount !in 1..2 || fadeFrames < 0) return null
			val sourceFrameCount = region.bFrame - region.aFrame
			if (sourceFrameCount > Int.MAX_VALUE || fadeFrames.toLong() > sourceFrameCount / 4) return null
			val expectedSamples = sourceFrameCount * channelCount
			if (expectedSamples != sourceSamples.size.toLong()) return null

			val sourceFrames = sourceFrameCount.toInt()
			val prefixFrames = sourceFrames - fadeFrames * 2
			val cycleFrames = prefixFrames + fadeFrames
			if (prefixFrames < 0 || cycleFrames <= 0) return null
			val cycleSamples = FloatArray(cycleFrames * channelCount)
			val prefixSamples = prefixFrames * channelCount
			if (prefixSamples > 0) {
				sourceSamples.copyInto(
					cycleSamples,
					endIndex = (fadeFrames + prefixFrames) * channelCount,
					startIndex = fadeFrames * channelCount,
				)
			}
			if (fadeFrames > 0) {
				val tailStart = (sourceFrames - fadeFrames) * channelCount
				val tail = sourceSamples.copyOfRange(tailStart, sourceSamples.size)
				val head = sourceSamples.copyOfRange(0, fadeFrames * channelCount)
				equalPowerCrossfade(tail, head, fadeFrames, channelCount)
					.copyInto(cycleSamples, prefixSamples)
			}
			return CachedLoopCycle(region, channelCount, cycleFrames, prefixFrames, fadeFrames, cycleSamples)
		}
	}
}
