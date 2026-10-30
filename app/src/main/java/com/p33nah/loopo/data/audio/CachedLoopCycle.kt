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
	private val channelCount: Int,
	val frameCount: Int,
	private val fadeFrames: Int,
	private val samples: FloatArray,
) {
	private val sourceFrameCount = samples.size / channelCount

	class Cursor internal constructor(internal val inputOffset: Int, internal val outputOffset: Int)

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

	fun cursorAt(sourceFrame: Long): Cursor {
		require(sourceFrame in region.aFrame until region.bFrame)
		val offset = (sourceFrame - region.aFrame).toInt()
		return Cursor(offset, offset)
	}

	fun samplesFrom(sourceFrame: Long, frameCount: Int): FloatArray =
		take(cursorAt(sourceFrame), frameCount).samples

	fun primeAt(sourceFrame: Long, frameCount: Int): Prime {
		val cursor = cursorAt(sourceFrame)
		val window = take(cursor, frameCount)
		return Prime(window.samples, sourceFrame, Cursor(window.next.inputOffset, cursor.outputOffset))
	}

	fun take(cursor: Cursor, requestedFrames: Int): Window {
		require(cursor.inputOffset in 0 until sourceFrameCount && cursor.outputOffset in 0 until sourceFrameCount)
		require(requestedFrames >= 0)
		val output = FloatArray(Math.multiplyExact(requestedFrames, channelCount))
		val segments = ArrayList<Segment>()
		var sourceOffset = cursor.inputOffset
		var copiedFrames = 0
		while (copiedFrames < requestedFrames) {
			val chunkFrames = minOf(requestedFrames - copiedFrames, sourceFrameCount - sourceOffset)
			samples.copyInto(
				output,
				destinationOffset = copiedFrames * channelCount,
				startIndex = sourceOffset * channelCount,
				endIndex = (sourceOffset + chunkFrames) * channelCount,
			)
			copiedFrames += chunkFrames
			sourceOffset += chunkFrames
			if (sourceOffset == sourceFrameCount) sourceOffset = fadeFrames
		}
		var outputOffset = cursor.outputOffset
		var mappedFrames = 0
		while (mappedFrames < requestedFrames) {
			val chunkFrames = minOf(requestedFrames - mappedFrames, sourceFrameCount - outputOffset)
			segments += Segment(mappedFrames, region.aFrame + outputOffset, chunkFrames)
			mappedFrames += chunkFrames
			outputOffset += chunkFrames
			if (outputOffset == sourceFrameCount) outputOffset = fadeFrames
		}
		return Window(output, sourceFrameAt(cursor), requestedFrames, advance(cursor, requestedFrames), segments)
	}

	fun advance(cursor: Cursor, frameCount: Int): Cursor {
		require(cursor.inputOffset in 0 until sourceFrameCount && cursor.outputOffset in 0 until sourceFrameCount)
		require(frameCount >= 0)
		return Cursor(advanceOffset(cursor.inputOffset, frameCount), advanceOffset(cursor.outputOffset, frameCount))
	}

	private fun advanceOffset(offset: Int, frames: Int): Int {
		val next = offset.toLong() + frames
		return if (next < sourceFrameCount) next.toInt() else {
			fadeFrames + ((next - sourceFrameCount) % frameCount).toInt()
		}
	}

	fun sourceFrameAt(cursor: Cursor): Long {
		require(cursor.outputOffset in 0 until sourceFrameCount)
		return region.aFrame + cursor.outputOffset
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
			val cycleFrames = sourceFrames - fadeFrames
			val cycleSamples = sourceSamples.copyOf()
			if (fadeFrames > 0) {
				val tailStart = (sourceFrames - fadeFrames) * channelCount
				val tail = sourceSamples.copyOfRange(tailStart, sourceSamples.size)
				val head = sourceSamples.copyOfRange(0, fadeFrames * channelCount)
				equalPowerCrossfade(tail, head, fadeFrames, channelCount)
					.copyInto(cycleSamples, tailStart)
			}
			return CachedLoopCycle(region, channelCount, cycleFrames, fadeFrames, cycleSamples)
		}
	}
}
