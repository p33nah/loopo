package com.p33nah.loopo.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTimeConversionsTest {
	@Test
	fun convertsCommonDurationsAndFramePositionsWithoutChangingRounding() {
		assertEquals(48_000L, framesFromDurationMicros(1_000_000L, 48_000))
		assertEquals(24_000L, framesFromDurationMicros(500_000L, 48_000))
		assertEquals(1_000_000L, microsFromFrame(48_000, 48_000))
		assertEquals(20_833L, microsFromFrame(1_000, 48_000))
	}

	@Test
	fun rejectsInvalidRatesAndValuesWhoseFrameOrTimeConversionWouldOverflow() {
		assertEquals(0L, framesFromDurationMicros(0, 48_000))
		assertNull(framesFromDurationMicros(-1, 48_000))
		assertNull(framesFromDurationMicros(1_000_000, 0))
		assertNull(framesFromDurationMicros(Long.MAX_VALUE, Int.MAX_VALUE))
		assertNull(microsFromFrame(-1, 48_000))
		assertNull(microsFromFrame(Long.MAX_VALUE, 1))
	}
}
