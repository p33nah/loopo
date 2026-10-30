package com.p33nah.loopo.platform.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePlaybackBridgeTest {
	@Test
	fun invalidEngineDimensionsAreRejectedBeforeNativeAllocation() {
		assertEquals(0L, NativePlaybackBridge.create(1_000_000_000, 2, 2_000_000_000))
		assertEquals(0L, NativePlaybackBridge.create(Int.MAX_VALUE, 2, Int.MAX_VALUE))
	}

	@Test
	fun resetAfterPlaybackPauseFlushesOutputAndPreservesExactSourcePosition() {
		val handle = NativePlaybackBridge.create(48_000, 1, 96_000)
		assertTrue("Native output must open", handle != 0L)
		try {
			assertTrue("Native output must reset before playback", NativePlaybackBridge.resetAtFrame(handle, 0))
			val preroll = NativePlaybackBridge.prerollFrames(handle, 1f)
			assertTrue(NativePlaybackBridge.prime(handle, FloatArray(preroll), preroll, 1, 1f))
			assertEquals(2_048, NativePlaybackBridge.write(handle, FloatArray(2_048), 0, 2_048, 2_048, 1, 0, 1.0, -1, -1))
			assertTrue("Native output must start", NativePlaybackBridge.play(handle))
			assertTrue("Native output must pause", NativePlaybackBridge.pause(handle))
			assertTrue("Native output must reset after pause", NativePlaybackBridge.resetAtFrame(handle, 55_000))
			assertEquals(55_000L, NativePlaybackBridge.positionFrame(handle))
			assertEquals(0, NativePlaybackBridge.queuedFrames(handle))
		} finally {
			NativePlaybackBridge.destroy(handle)
		}
	}
}
