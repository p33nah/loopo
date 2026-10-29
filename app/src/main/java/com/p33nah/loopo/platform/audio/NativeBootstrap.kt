package com.p33nah.loopo.platform.audio

internal object NativeBootstrap {
	init {
		System.loadLibrary("loopo_native")
	}

	external fun apiVersion(): Int
}
