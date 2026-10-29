package com.p33nah.loopo.data.waveform

import com.p33nah.loopo.domain.audio.PcmBlock
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.waveform.WaveformChannelPeaks
import com.p33nah.loopo.domain.waveform.WaveformData
import com.p33nah.loopo.domain.waveform.WaveformLevel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal class WaveformPyramidBuilder(
	private val track: TrackInfo,
	private val bucketFrames: Long = baseBucketFrames(track),
) {
	private val channels = track.channelCount
	private var capacityLimit = expectedBucketCount(track.durationFrames, bucketFrames).coerceAtLeast(1)
	private val maxBucketCapacity = maxBucketCapacity(channels)
	private val minima = Array(channels) { FloatArray(capacityLimit) }
	private val maxima = Array(channels) { FloatArray(capacityLimit) }
	private val currentMinimum = FloatArray(channels) { Float.POSITIVE_INFINITY }
	private val currentMaximum = FloatArray(channels) { Float.NEGATIVE_INFINITY }
	private var completedBuckets = 0
	private var framesInBucket = 0L
	var processedFrames = 0L
		private set

	fun add(block: PcmBlock, frameLimit: Int = block.frameCount) {
		check(block.format.sampleRate == track.sampleRate && block.format.channelCount == channels)
		check(frameLimit in 0..block.frameCount)
		for (frame in 0 until frameLimit) {
			val sampleOffset = frame * channels
			for (channel in 0 until channels) {
				val sample = block.samples[sampleOffset + channel].takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
				currentMinimum[channel] = min(currentMinimum[channel], sample)
				currentMaximum[channel] = max(currentMaximum[channel], sample)
			}
			framesInBucket++
			processedFrames++
			if (framesInBucket == bucketFrames) appendBucket()
		}
	}

	fun snapshot(includePartialBucket: Boolean = false): WaveformData {
		val count = completedBuckets + if (includePartialBucket && framesInBucket > 0) 1 else 0
		val levels = ArrayList<WaveformLevel>()
		var levelFrames = bucketFrames
		var levelChannels = Array(channels) { channel ->
			val minimum = minima[channel].copyOf(count)
			val maximum = maxima[channel].copyOf(count)
			if (count > completedBuckets) {
				minimum[completedBuckets] = currentMinimum[channel]
				maximum[completedBuckets] = currentMaximum[channel]
			}
			WaveformChannelPeaks(minimum, maximum)
		}
		var levelCount = count
		levels += WaveformLevel(levelFrames, levelChannels.toList())
		while (levelCount > 1) {
			val parentCount = (levelCount + 1) / 2
			levelChannels = Array(channels) { channel ->
				val child = levelChannels[channel]
				val parentMinimum = FloatArray(parentCount)
				val parentMaximum = FloatArray(parentCount)
				for (parent in 0 until parentCount) {
					val first = parent * 2
					val second = min(first + 1, levelCount - 1)
					parentMinimum[parent] = min(child.minimum[first], child.minimum[second])
					parentMaximum[parent] = max(child.maximum[first], child.maximum[second])
				}
				WaveformChannelPeaks(parentMinimum, parentMaximum)
			}
			levelFrames *= 2
			levelCount = parentCount
			levels += WaveformLevel(levelFrames, levelChannels.toList())
		}
		return WaveformData(
			trackId = track.id,
			durationFrames = track.durationFrames,
			coveredFrames = processedFrames.coerceAtMost(track.durationFrames),
			sampleRate = track.sampleRate,
			channelCount = channels,
			levels = levels,
			isComplete = includePartialBucket,
		)
	}

	private fun appendBucket() {
		if (completedBuckets == capacityLimit) {
			check(completedBuckets < maxBucketCapacity) { "Waveform duration exceeded its bounded peak capacity" }
			capacityLimit = min(maxBucketCapacity, max(capacityLimit * 2, completedBuckets + 1))
			for (channel in 0 until channels) {
				minima[channel] = minima[channel].copyOf(capacityLimit)
				maxima[channel] = maxima[channel].copyOf(capacityLimit)
			}
		}
		for (channel in 0 until channels) {
			minima[channel][completedBuckets] = currentMinimum[channel]
			maxima[channel][completedBuckets] = currentMaximum[channel]
			currentMinimum[channel] = Float.POSITIVE_INFINITY
			currentMaximum[channel] = Float.NEGATIVE_INFINITY
		}
		completedBuckets++
		framesInBucket = 0
	}

	companion object {
		private const val MIN_BUCKET_FRAMES = 64L
		private const val MAX_PYRAMID_BYTES = 64L * 1024 * 1024

		fun baseBucketFrames(track: TrackInfo): Long {
			val maxBaseBuckets = maxBucketCapacity(track.channelCount)
			val required = ceil(track.durationFrames.toDouble() / maxBaseBuckets).toLong().coerceAtLeast(MIN_BUCKET_FRAMES)
			var bucketFrames = MIN_BUCKET_FRAMES
			while (bucketFrames < required) bucketFrames *= 2
			return bucketFrames
		}

		private fun maxBucketCapacity(channelCount: Int): Int {
			val bytesPerBaseBucket = channelCount * Float.SIZE_BYTES * 2L * 3L
			return (MAX_PYRAMID_BYTES / bytesPerBaseBucket).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		}

		fun expectedBucketCount(durationFrames: Long, bucketFrames: Long): Int {
			val count = 1 + (durationFrames - 1) / bucketFrames
			return count.toInt().coerceAtLeast(1)
		}
	}
}
