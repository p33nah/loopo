package com.p33nah.loopo.platform.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePlaybackBridgeTest {
	@Test
	fun invalidEngineDimensionsAreRejectedBeforeNativeAllocation() {
		assertEquals(0L, NativePlaybackBridge.create(1_000_000_000, 2, 2_000_000_000))
		assertEquals(0L, NativePlaybackBridge.create(Int.MAX_VALUE, 2, Int.MAX_VALUE))
	}
}
