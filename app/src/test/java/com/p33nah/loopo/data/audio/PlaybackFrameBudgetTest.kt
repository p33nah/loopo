package com.p33nah.loopo.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackFrameBudgetTest {
	@Test
	fun exactBudgetCarriesSourceUnitsAcrossAllSupportedRates() {
		val chunks = listOf(1, 1, 2_048, 3, 127, 1, 761, 2_048)
		for (ratePercent in 25..200) {
			var remainderUnits = 0
			var totalInputFrames = 0L
			var totalOutputFrames = 0L
			repeat(8) {
				for (inputFrames in chunks) {
					val budget = PlaybackFrameBudgetCalculator.forInput(
						inputFrames,
						ratePercent,
						remainderUnits,
					)
					remainderUnits = budget.remainderUnits
					totalInputFrames += inputFrames
					totalOutputFrames += budget.outputFrames
				}
			}
			assertEquals(totalInputFrames * 100 / ratePercent, totalOutputFrames)
			assertEquals((totalInputFrames * 100 % ratePercent).toInt(), remainderUnits)
		}
	}

	@Test
	fun oneFrameAtTwoTimesCarriesUntilAnOutputFrameIsDue() {
		val first = PlaybackFrameBudgetCalculator.forInput(1, 200, 0)
		assertEquals(0, first.outputFrames)
		assertEquals(100, first.remainderUnits)

		val second = PlaybackFrameBudgetCalculator.forInput(1, 200, first.remainderUnits)
		assertEquals(1, second.outputFrames)
		assertEquals(0, second.remainderUnits)
	}

	@Test
	fun exactSeventyFivePercentSequenceKeepsTheFinalBudgetInteger() {
		val chunks = listOf(1, 2_048, 1_454, 809, 1, 2_048, 1_330, 1_245, 1, 2_048, 758, 2_001, 1, 2_048, 762, 5)
		var remainderUnits = 0
		var totalInputFrames = 0L
		var totalOutputFrames = 0L
		var finalBudget = PlaybackFrameBudget(0, 0)
		for (inputFrames in chunks) {
			finalBudget = PlaybackFrameBudgetCalculator.forInput(inputFrames, 75, remainderUnits)
			remainderUnits = finalBudget.remainderUnits
			totalInputFrames += inputFrames
			totalOutputFrames += finalBudget.outputFrames
		}
		assertEquals(7, finalBudget.outputFrames)
		assertEquals(0, finalBudget.remainderUnits)
		assertEquals(totalInputFrames * 100 / 75, totalOutputFrames)
	}

	@Test
	fun inputLimitReservesOneOutputFrameAtEveryRateBoundary() {
		for (ratePercent in 25..200) {
			for (remainderUnits in listOf(0, ratePercent / 2, ratePercent - 1).distinct()) {
				for (outputCapacity in 2..32) {
					val inputFrames = PlaybackFrameBudgetCalculator.maxInputFrames(
						outputCapacity,
						ratePercent,
						remainderUnits,
						2_048,
					)
					assertTrue(inputFrames in 0..2_048)
					if (inputFrames > 0) {
						val outputFrames = PlaybackFrameBudgetCalculator.forInput(
							inputFrames,
							ratePercent,
							remainderUnits,
						).outputFrames
						assertTrue(outputFrames <= outputCapacity - 1)
					}
				}
			}
		}
	}

	@Test
	fun largeBudgetsRejectOutputNarrowingAndClampInputBeforeNarrowing() {
		assertThrows(IllegalArgumentException::class.java) {
			PlaybackFrameBudgetCalculator.forInput(Int.MAX_VALUE, 25, 0)
		}
		assertEquals(
			2_048,
			PlaybackFrameBudgetCalculator.maxInputFrames(Int.MAX_VALUE, 200, 0, 2_048),
		)
	}
}
