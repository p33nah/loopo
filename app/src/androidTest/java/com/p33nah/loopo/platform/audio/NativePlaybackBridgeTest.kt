package com.p33nah.loopo.platform.audio

import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
	fun outputIdentityAndErrorStateMatchCurrentAndroidDevices() {
		val handle = NativePlaybackBridge.create(48_000, 1, 96_000)
		assertTrue("Native output must open", handle != 0L)
		try {
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			val audioManager = context.getSystemService(AudioManager::class.java)
			val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
			val outputIds = outputs.map { it.id }.toSet()
			val deviceId = NativePlaybackBridge.outputDeviceId(handle)
			Log.i("LoopoRouteTest", "Oboe output id=$deviceId; Android outputs=${outputs.map { "${it.id}:${it.type}" }}")

			assertTrue("Unknown device IDs use -1", deviceId == -1 || deviceId in outputIds)
			assertEquals(NativePlaybackBridge.NO_STREAM_FAILURE, NativePlaybackBridge.takeStreamFailure(handle))
			assertFalse(NativePlaybackBridge.outputNeedsReopen(handle))
			assertTrue(NativePlaybackBridge.reopenOutput(handle))
		} finally {
			NativePlaybackBridge.destroy(handle)
		}
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
