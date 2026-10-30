package com.p33nah.loopo.platform.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
			assertEquals(2_048, NativePlaybackBridge.write(handle, FloatArray(2_048), 0, 2_048, 2_048, 1, 0, 100, -1, -1))
			assertTrue("Native output must start", NativePlaybackBridge.play(handle))
			assertTrue("Native output must pause", NativePlaybackBridge.pause(handle))
			assertTrue("Native output must reset after pause", NativePlaybackBridge.resetAtFrame(handle, 55_000))
			assertEquals(55_000L, NativePlaybackBridge.positionFrame(handle))
			assertEquals(0, NativePlaybackBridge.queuedFrames(handle))
		} finally {
			NativePlaybackBridge.destroy(handle)
		}
	}

	@Test
	fun zeroOutputWriteConsumesValidInputAndRejectsMalformedRequests() {
		val handle = NativePlaybackBridge.create(48_000, 1, 96_000)
		assertTrue("Native output must open", handle != 0L)
		try {
			val samples = floatArrayOf(0.25f, -0.25f)
			assertEquals(0, NativePlaybackBridge.write(handle, samples, 0, 1, 0, 1, 0, 200, -1, -1))
			assertFalse(NativePlaybackBridge.finish(handle, 200, 0, 0))
			assertTrue(NativePlaybackBridge.resetAtFrame(handle, 0))
			val preroll = NativePlaybackBridge.prerollFrames(handle, 2f)
			assertTrue(NativePlaybackBridge.prime(handle, FloatArray(preroll + 2), preroll, 1, 2f))

			assertEquals(0, NativePlaybackBridge.write(handle, samples, 0, 1, 0, 1, 0, 25, -1, -1))
			assertEquals(0, NativePlaybackBridge.write(handle, samples, 0, 1, 0, 1, 0, 200, 10, 10))
			assertEquals(0, NativePlaybackBridge.write(handle, samples, 0, 1, -1, 1, 0, 200, -1, -1))
			assertFalse(NativePlaybackBridge.finish(handle, 200, 200, 0))
			assertEquals(1, NativePlaybackBridge.write(handle, samples, 0, 1, 0, 1, 0, 200, 0, 2))
			assertEquals(0, NativePlaybackBridge.queuedFrames(handle))
			assertTrue(NativePlaybackBridge.finish(handle, 200, 100, 1))
			assertTrue(NativePlaybackBridge.queuedFrames(handle) > 0)
		} finally {
			NativePlaybackBridge.destroy(handle)
		}
	}
}
