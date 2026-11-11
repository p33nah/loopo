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
	fun listensAgainAfterCommandResultsAndStopsAfterAMicrophoneFailure() = runBlocking {
		val detections = listOf(WakeDetection.Detected, WakeDetection.Detected, WakeDetection.Failed).iterator()
		val commands = listOf(VoiceStatus.CommandSent, VoiceStatus.Failed).iterator()
		val statuses = mutableListOf<VoiceStatus>()
		val wake = WakeCommandCoordinator(
			awaitWake = { onListening ->
				onListening()
				detections.next()
			},
			runCommand = { commands.next() },
		)

		wake.listen(isCurrent = { true }, onStatus = { statuses += it })

		assertEquals(
			listOf(
				VoiceStatus.WakeListening, VoiceStatus.CommandSent,
				VoiceStatus.WakeListening, VoiceStatus.Failed,
				VoiceStatus.WakeListening, VoiceStatus.WakeFailed,
			),
			statuses,
		)
	}

	@Test
	fun microphoneFailureDoesNotStartCommandFlow() = runBlocking {
		val wake = WakeCommandCoordinator(
			awaitWake = { WakeDetection.Failed },
			runCommand = { error("Command recognition requires a wake phrase") },
		)

		assertEquals(VoiceStatus.WakeFailed, wake.runTurn(isCurrent = { true }))
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

	@Test
	fun pausedTrackCanResumeFromOneWakeTurn() = runBlocking {
		var playing = false
		var wakeCount = 0
		var commandCount = 0
		val command = VoiceCommandCoordinator(
			pause = { playing = false; true },
			transcribe = { TranscriptResult.Text("riprendi") },
			dispatch = { parsed ->
				commandCount++
				if (parsed == VoiceCommand.Resume) playing = true
				true
			},
		)
		val wake = WakeCommandCoordinator(
			awaitWake = { wakeCount++; WakeDetection.Detected },
			runCommand = command::run,
		)

		val result = wake.runTurn(isCurrent = { true })

		assertEquals(VoiceStatus.CommandSent, result)
		assertEquals(1, wakeCount)
		assertEquals(1, commandCount)
		assertTrue(playing)
	}

	@Test
	fun aWakeTurnDispatchesAtMostOneCommand() = runBlocking {
		var commandCount = 0
		val command = VoiceCommandCoordinator(
			pause = { true },
			transcribe = { TranscriptResult.Text("pausa") },
			dispatch = { commandCount++; true },
		)
		val wake = WakeCommandCoordinator(
			awaitWake = { WakeDetection.Detected },
			runCommand = command::run,
		)

		wake.runTurn(isCurrent = { true })

		assertEquals(1, commandCount)
	}
}
