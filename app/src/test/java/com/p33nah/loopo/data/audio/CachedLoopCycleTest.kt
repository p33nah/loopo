package com.p33nah.loopo.data.audio

import com.p33nah.loopo.domain.model.LoopRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CachedLoopCycleTest {
	@Test
	fun seekPrerollStartsAtRequestedHeadPhaseAndContinuesIntoFirstWrite() {
		val cycle = cycle(region = LoopRegion(100, 112, true, 0), fadeFrames = 2)
		assertArrayEquals(
			floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 1f).map { it / 16f }.toFloatArray(),
			cycle.take(cycle.cursorAt(102), cycle.frameCount).samples,
			0f,
		)
		val requestedFrame = 103L
		val prerollFrames = 3
		val priming = cycle.primeAt(requestedFrame, prerollFrames)
		val nextWrite = cycle.take(priming.next, 4)

		assertArrayEquals(floatArrayOf(3f, 4f, 5f).map { it / 16f }.toFloatArray(), priming.samples, 0f)
		assertEquals(requestedFrame, priming.sourceStartFrame)
		assertEquals(requestedFrame, cycle.sourceFrameAt(priming.next))
		assertArrayEquals(floatArrayOf(6f, 7f, 8f, 9f).map { it / 16f }.toFloatArray(), nextWrite.samples, 0f)
		assertEquals(requestedFrame, nextWrite.sourceStartFrame)
		assertEquals(listOf(103L, 104L, 105L, 106L), nextWrite.segments.flatMap { segment ->
			(segment.sourceStartFrame until segment.sourceStartFrame + segment.frameCount).toList()
		})
	}

	@Test
	fun wrappedReadReturnsExactSamplesAndContiguousSourceMetadataSegments() {
		val cycle = cycle(region = LoopRegion(100, 112, true, 0), fadeFrames = 2)
		val window = cycle.take(cycle.cursorAt(108), 6)

		assertArrayEquals(floatArrayOf(8f, 9f, 10f, 1f, 2f, 3f).map { it / 16f }.toFloatArray(), window.samples, 0.00001f)
		assertEquals(listOf(108L, 109L, 110L, 111L, 102L, 103L), window.segments.flatMap { segment ->
			(segment.sourceStartFrame until segment.sourceStartFrame + segment.frameCount).toList()
		})
		assertEquals(window.samples.toList(), window.segments.flatMap { segment ->
			window.samples.slice(segment.sampleOffsetFrames until segment.sampleOffsetFrames + segment.frameCount)
		})
	}

	@Test
	fun seekInsideHeadReturnsTheRequestedSamplesBeforeSteadyLoopWrap() {
		val cycle = cycle(region = LoopRegion(100, 112, true, 0), fadeFrames = 2)
		val window = cycle.take(cycle.cursorAt(100), 4)

		assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f).map { it / 16f }.toFloatArray(), window.samples, 0.00001f)
		assertEquals(100L, window.sourceStartFrame)
		assertEquals(104L, cycle.sourceFrameAt(window.next))
	}

	@Test
	fun cycleRetainsEqualPowerCrossfadeAndClampsSamplesForEachChannel() {
		val region = LoopRegion(0, 12, true, 0)
		val source = floatArrayOf(
			1f, -1f, 1f, -1f, 1f, -1f, 1f, -1f,
			1f, -1f, 2f, -2f, 2f, -2f, 2f, -2f,
			2f, -2f, 2f, -2f, 2f, -2f, 2f, -2f,
		)
		val cycle = CachedLoopCycle.create(region, source, channelCount = 2, fadeFrames = 2)
		assertNotNull(cycle)
		cycle!!

		val blended = cycle.take(cycle.cursorAt(10), 2).samples
		assertArrayEquals(floatArrayOf(1f, -1f, 1f, -1f), blended, 0.00001f)
		val oneFrameFade = CachedLoopCycle.create(region, source, channelCount = 2, fadeFrames = 1)
		assertNotNull(oneFrameFade)
	}

	@Test
	fun fadeInteriorUsesEqualPowerGainsAndClipsOutput() {
		val region = LoopRegion(0, 20, true, 0)
		val source = FloatArray(20) { index -> if (index < 4 || index >= 16) 0.5f else 0f }
		val cycle = CachedLoopCycle.create(region, source, channelCount = 1, fadeFrames = 4)
		assertNotNull(cycle)
		cycle!!

		val fade = cycle.take(cycle.cursorAt(16), 4).samples
		val interior = 0.25f * (sqrt(3f) + 1f)
		assertArrayEquals(floatArrayOf(0.5f, interior, interior, 0.5f), fade, 0.00001f)
		val clippingSource = FloatArray(20) { 1f }
		val clipped = CachedLoopCycle.create(region, clippingSource, 1, 4)!!
		assertTrue(clipped.take(clipped.cursorAt(16), 4).samples.all { it == 1f })
	}

	@Test
	fun prerollSpanningSeveralCyclesDoesNotAdvanceTheAudiblePlayhead() {
		val cycle = cycle(LoopRegion(100, 104, true, 0), fadeFrames = 1)
		val prime = cycle.primeAt(101, 13)
		assertArrayEquals(
			floatArrayOf(1f, 2f, 0f, 1f, 2f, 0f, 1f, 2f, 0f, 1f, 2f, 0f, 1f).map { it / 16f }.toFloatArray(),
			prime.samples, 0.000001f,
		)
		val write = cycle.take(prime.next, 6)
		assertArrayEquals(floatArrayOf(2f, 0f, 1f, 2f, 0f, 1f).map { it / 16f }.toFloatArray(), write.samples, 0.000001f)
		assertEquals(listOf(101L, 102L, 103L, 101L, 102L, 103L), write.segments.flatMap { segment ->
			(segment.sourceStartFrame until segment.sourceStartFrame + segment.frameCount).toList()
		})
	}

	@Test
	fun oneFrameLoopRemainsReadableThroughPrerollAndRepeatedWraps() {
		val cycle = CachedLoopCycle.create(LoopRegion(5, 6, true, 0), floatArrayOf(0.25f), 1, 0)!!
		val prime = cycle.primeAt(5, 20)
		val write = cycle.take(prime.next, 10)
		assertArrayEquals(FloatArray(20) { 0.25f }, prime.samples, 0f)
		assertArrayEquals(FloatArray(10) { 0.25f }, write.samples, 0f)
		assertEquals(List(10) { 5L }, write.segments.flatMap { segment ->
			(segment.sourceStartFrame until segment.sourceStartFrame + segment.frameCount).toList()
		})
	}

	@Test
	fun zeroFadeCycleHasLoopLengthAndRejectsMalformedShapes() {
		val region = LoopRegion(4, 12, true, 0)
		val cycle = CachedLoopCycle.create(region, FloatArray(8) { it.toFloat() }, 1, 0)
		assertNotNull(cycle)
		cycle!!
		assertEquals(8, cycle.frameCount)
		assertArrayEquals(floatArrayOf(6f, 7f, 0f, 1f), cycle.take(cycle.cursorAt(10), 4).samples, 0f)
		assertNull(CachedLoopCycle.create(region, FloatArray(7), 1, 0))
		assertNull(CachedLoopCycle.create(region, FloatArray(8), 3, 0))
		assertNull(CachedLoopCycle.create(region, FloatArray(8), 1, 3))
	}

	private fun cycle(region: LoopRegion, fadeFrames: Int): CachedLoopCycle =
		CachedLoopCycle.create(region, FloatArray((region.bFrame - region.aFrame).toInt()) { it / 16f }, 1, fadeFrames)!!
}
