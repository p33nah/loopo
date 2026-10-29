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
	private val previewBucketFrames: Long? = null,
) {
	init {
		require(previewBucketFrames == null || previewBucketFrames >= bucketFrames)
	}

	private val channels = track.channelCount
	private var capacityLimit = expectedBucketCount(track.durationFrames, bucketFrames).coerceAtLeast(1)
	private val maxBucketCapacity = maxBucketCapacity(channels)
	private val minima = Array(channels) { FloatArray(capacityLimit) }
	private val maxima = Array(channels) { FloatArray(capacityLimit) }
	private val previewCapacityLimit = previewBucketFrames?.let { expectedBucketCount(track.durationFrames, it).coerceAtLeast(1) } ?: 0
	private val previewMinima = Array(channels) { FloatArray(previewCapacityLimit) }
	private val previewMaxima = Array(channels) { FloatArray(previewCapacityLimit) }
	private val currentMinimum = FloatArray(channels) { Float.POSITIVE_INFINITY }
	private val currentMaximum = FloatArray(channels) { Float.NEGATIVE_INFINITY }
	private val previewCurrentMinimum = FloatArray(channels) { Float.POSITIVE_INFINITY }
	private val previewCurrentMaximum = FloatArray(channels) { Float.NEGATIVE_INFINITY }
	private var completedBuckets = 0
	private var framesInBucket = 0L
	private var previewCompletedBuckets = 0
	private var previewFramesInBucket = 0L
	var processedFrames = 0L
		private set

	fun add(block: PcmBlock, frameLimit: Int = block.frameCount) {
		check(block.format.sampleRate == track.sampleRate && block.format.channelCount == channels)
		check(frameLimit in 0..block.frameCount)
		for (frame in 0 until frameLimit) {
			val sampleOffset = frame * channels
			for (channel in 0 until channels) {
				val rawSample = block.samples[sampleOffset + channel]
				val sample = if (rawSample.isFinite()) rawSample.coerceIn(-1f, 1f) else 0f
				currentMinimum[channel] = min(currentMinimum[channel], sample)
				currentMaximum[channel] = max(currentMaximum[channel], sample)
				if (previewBucketFrames != null) {
					previewCurrentMinimum[channel] = min(previewCurrentMinimum[channel], sample)
					previewCurrentMaximum[channel] = max(previewCurrentMaximum[channel], sample)
				}
			}
			framesInBucket++
			processedFrames++
			if (framesInBucket == bucketFrames) appendBucket()
			if (previewBucketFrames != null && ++previewFramesInBucket == previewBucketFrames) appendPreviewBucket()
		}
	}

	fun snapshot(includePartialBucket: Boolean = false): WaveformData {
		val count = completedBuckets + if (includePartialBucket && framesInBucket > 0) 1 else 0
		return snapshotFrom(
			baseFrames = bucketFrames,
			baseMinima = minima,
			baseMaxima = maxima,
			count = count,
			completed = completedBuckets,
			partialMinimum = currentMinimum,
			partialMaximum = currentMaximum,
			coveredFrames = processedFrames,
			isComplete = includePartialBucket,
		)
	}

	fun previewSnapshot(): WaveformData? {
		val framesPerBucket = previewBucketFrames ?: return null
		if (previewCompletedBuckets == 0) return null
		return snapshotFrom(
			baseFrames = framesPerBucket,
			baseMinima = previewMinima,
			baseMaxima = previewMaxima,
			count = previewCompletedBuckets,
			completed = previewCompletedBuckets,
			partialMinimum = null,
			partialMaximum = null,
			coveredFrames = processedFrames,
			isComplete = false,
		)
	}

	private fun snapshotFrom(
		baseFrames: Long,
		baseMinima: Array<FloatArray>,
		baseMaxima: Array<FloatArray>,
		count: Int,
		completed: Int,
		partialMinimum: FloatArray?,
		partialMaximum: FloatArray?,
		coveredFrames: Long,
		isComplete: Boolean,
	): WaveformData {
		val levels = ArrayList<WaveformLevel>()
		var levelChannels = Array(channels) { channel ->
			val minimum = baseMinima[channel].copyOf(count)
			val maximum = baseMaxima[channel].copyOf(count)
			if (count > completed) {
				minimum[completed] = partialMinimum!![channel]
				maximum[completed] = partialMaximum!![channel]
			}
			WaveformChannelPeaks(minimum, maximum)
		}
		var levelFrames = baseFrames
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
			coveredFrames = coveredFrames.coerceAtMost(track.durationFrames),
			sampleRate = track.sampleRate,
			channelCount = channels,
			levels = levels,
			isComplete = isComplete,
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

	private fun appendPreviewBucket() {
		check(previewCompletedBuckets < previewCapacityLimit)
		for (channel in 0 until channels) {
			previewMinima[channel][previewCompletedBuckets] = previewCurrentMinimum[channel]
			previewMaxima[channel][previewCompletedBuckets] = previewCurrentMaximum[channel]
			previewCurrentMinimum[channel] = Float.POSITIVE_INFINITY
			previewCurrentMaximum[channel] = Float.NEGATIVE_INFINITY
		}
		previewCompletedBuckets++
		previewFramesInBucket = 0
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
