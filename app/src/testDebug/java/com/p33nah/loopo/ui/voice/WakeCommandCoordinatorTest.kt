package com.p33nah.loopo.ui.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCommandCoordinatorTest {
	@Test
	fun closesWakeCaptureBeforePausingAndStartingCommandRecognition() = runBlocking {
		val events = mutableListOf<String>()
		val voice = VoiceCommandCoordinator(
			pause = { events += "pause"; true },
			transcribe = {
				assertEquals("pause", events.last())
				events += "command recognition"
				TranscriptResult.Text("riprendi")
			},
			dispatch = { events += "play"; true },
		)
		val wake = WakeCommandCoordinator(
			awaitWake = {
				events += "wake capture closed"
				WakeDetection.Detected
			},
			runCommand = { voice.run(it) },
		)

		val result = wake.runTurn(isCurrent = { true })

		assertEquals(VoiceStatus.CommandSent, result)
		assertEquals(listOf("wake capture closed", "pause", "command recognition", "play"), events)
	}

	@Test
	fun sessionThatBecomesObsoleteDuringWakeDoesNotPauseOrDispatch() = runBlocking {
		var current = true
		var pauseRuns = 0
		var dispatchRuns = 0
		val voice = VoiceCommandCoordinator(
			pause = { pauseRuns++; true },
			transcribe = { TranscriptResult.Text("riprendi") },
			dispatch = { dispatchRuns++; true },
		)
		val wake = WakeCommandCoordinator(
			awaitWake = {
				current = false
				WakeDetection.Detected
			},
			runCommand = { voice.run(it) },
		)

		assertEquals(VoiceStatus.Cancelled, wake.runTurn(isCurrent = { current }))
		assertEquals(0, pauseRuns)
		assertEquals(0, dispatchRuns)
	}

	@Test
	fun microphoneFailureDoesNotStartCommandFlow() = runBlocking {
		val wake = WakeCommandCoordinator(
			awaitWake = { WakeDetection.Failed },
			runCommand = { error("Command recognition requires a wake phrase") },
		)

		assertEquals(VoiceStatus.Failed, wake.runTurn(isCurrent = { true }))
	}

	@Test
	fun cancellationWhileWaitingForWakeNeverStartsCommandFlow() = runBlocking {
		val waiting = CompletableDeferred<WakeDetection>()
		var commandRuns = 0
		val wake = WakeCommandCoordinator(
			awaitWake = { waiting.await() },
			runCommand = { commandRuns++; VoiceStatus.CommandSent },
		)
		val job = launch(start = CoroutineStart.UNDISPATCHED) { wake.runTurn(isCurrent = { true }) }

		job.cancelAndJoin()

		assertTrue(job.isCancelled)
		assertEquals(0, commandRuns)
	}
}
