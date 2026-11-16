package com.p33nah.loopo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopRegionTest {
	@Test
	fun restartFrameUsesMarkerAOnlyWhenLoopingIsEnabled() {
		val enabled = LoopRegion(aFrame = 24_000, bFrame = 84_000, enabled = true, crossfadeMs = 10)
		val disabled = enabled.copy(enabled = false)

		assertEquals(24_000L, enabled.restartFrame())
		assertEquals(0L, disabled.restartFrame())
	}
}
