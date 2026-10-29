package com.p33nah.loopo.platform.audio

internal object NativePlaybackBridge {
	init {
		System.loadLibrary("loopo_native")
	}

	external fun create(sampleRate: Int, channelCount: Int, capacityFrames: Int): Long
	external fun destroy(handle: Long)
	external fun write(handle: Long, samples: FloatArray, offsetFrames: Int, frameCount: Int, channelCount: Int): Int
	external fun writeSpace(handle: Long): Int
	external fun queuedFrames(handle: Long): Int
	external fun resetAtFrame(handle: Long, sourceFrame: Long): Boolean
	external fun setEndOfStream(handle: Long)
	external fun play(handle: Long): Boolean
	external fun pause(handle: Long): Boolean
	external fun positionFrame(handle: Long): Long
	external fun estimatedLatencyMillis(handle: Long): Double
	external fun underrunCount(handle: Long): Long
	external fun isPlaying(handle: Long): Boolean
	external fun isEnded(handle: Long): Boolean
}
