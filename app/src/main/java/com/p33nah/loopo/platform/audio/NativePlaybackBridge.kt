package com.p33nah.loopo.platform.audio

internal object NativePlaybackBridge {
	const val NO_STREAM_FAILURE = 0

	init {
		System.loadLibrary("loopo_native")
	}

	external fun create(sampleRate: Int, channelCount: Int, capacityFrames: Int): Long
	external fun destroy(handle: Long)
	external fun prerollFrames(handle: Long, playbackRate: Float): Int
	external fun prime(handle: Long, samples: FloatArray, frameCount: Int, channelCount: Int, playbackRate: Float): Boolean
	external fun write(
		handle: Long,
		samples: FloatArray,
		offsetFrames: Int,
		inputFrames: Int,
		outputFrames: Int,
		channelCount: Int,
		sourceStartFrame: Long,
		playbackRatePercent: Int,
		loopStartFrame: Long,
		loopEndFrame: Long,
	): Int
	external fun finish(handle: Long, playbackRatePercent: Int, outputRemainderUnits: Int, endFrame: Long): Boolean
	external fun writeSpace(handle: Long): Int
	external fun queuedFrames(handle: Long): Int
	external fun resetAtFrame(handle: Long, sourceFrame: Long): Boolean
	external fun setEndOfStream(handle: Long)
	external fun play(handle: Long): Boolean
	external fun pause(handle: Long): Boolean
	external fun positionFrame(handle: Long): Long
	external fun estimatedLatencyMillis(handle: Long): Double
	external fun underrunCount(handle: Long): Long
	external fun outputDeviceId(handle: Long): Int
	external fun takeStreamFailure(handle: Long): Int
	external fun outputNeedsReopen(handle: Long): Boolean
	external fun reopenOutput(handle: Long): Boolean
	external fun isPlaying(handle: Long): Boolean
	external fun isEnded(handle: Long): Boolean
}
