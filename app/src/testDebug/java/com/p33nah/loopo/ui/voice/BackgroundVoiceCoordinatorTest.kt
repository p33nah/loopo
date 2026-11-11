package com.p33nah.loopo.ui.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundVoiceCoordinatorTest {
	@Test
	fun exactWakePausesWithoutRunningTheForegroundCommandFlow() = runBlocking {
		var pauseCount = 0
		val coordinator = BackgroundVoiceCoordinator(
			awaitWake = { WakeDetection.Detected },
			pause = {
				pauseCount++
				true
			},
		)

		assertEquals(VoiceStatus.Paused, coordinator.runTurn { true })
		assertEquals(1, pauseCount)
	}

	@Test
	fun staleTrackAfterWakeCannotPausePlayback() = runBlocking {
		var current = true
		val coordinator = BackgroundVoiceCoordinator(
			awaitWake = {
				current = false
				WakeDetection.Detected
			},
			pause = { error("An obsolete wake must not pause playback") },
		)

		assertEquals(VoiceStatus.Cancelled, coordinator.runTurn { current })
	}

	@Test
	fun ineligibleTurnDoesNotOpenTheMicrophone() = runBlocking {
		val coordinator = BackgroundVoiceCoordinator(
			awaitWake = { error("An ineligible turn must not capture audio") },
			pause = { error("An ineligible turn must not pause playback") },
		)

		assertEquals(VoiceStatus.Cancelled, coordinator.runTurn { false })
	}

	@Test
	fun failedWakeDoesNotPauseOrRetry() = runBlocking {
		var captureCount = 0
		val coordinator = BackgroundVoiceCoordinator(
			awaitWake = {
				captureCount++
				WakeDetection.Failed
			},
			pause = { error("A failed wake must not pause playback") },
		)

		assertEquals(VoiceStatus.WakeFailed, coordinator.runTurn { true })
		assertEquals(1, captureCount)
	}
}
