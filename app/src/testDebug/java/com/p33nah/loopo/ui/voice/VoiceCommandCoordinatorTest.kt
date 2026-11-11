package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandCoordinatorTest {
	@Test
	fun recognitionAndDispatchWaitForPauseConfirmation() = runBlocking {
		val pause = CompletableDeferred<Boolean>()
		val statuses = mutableListOf<VoiceStatus>()
		val commands = mutableListOf<VoiceCommand>()
		val coordinator = VoiceCommandCoordinator(
			pause = { pause.await() },
			transcribe = { ready ->
				ready()
				TranscriptResult.Text("riprendi")
			},
			dispatch = commands::add,
		)
		val job = launch(start = CoroutineStart.UNDISPATCHED) {
			statuses += coordinator.run(statuses::add)
		}
		assertEquals(listOf(VoiceStatus.Pausing), statuses)
		assertTrue(commands.isEmpty())

		pause.complete(true)
		job.join()

		assertEquals(listOf(VoiceStatus.Pausing, VoiceStatus.Listening, VoiceStatus.CommandSent), statuses)
		assertEquals(listOf(VoiceCommand.Resume), commands)
	}

	@Test
	fun failedPauseKeepsTheMicrophoneClosed() = runBlocking {
		val result = VoiceCommandCoordinator(
			pause = { false },
			transcribe = { error("Recognition must not start") },
			dispatch = { error("No command is available") },
		).run()

		assertEquals(VoiceStatus.PauseFailed, result)
	}

	@Test
	fun timeoutUnavailableAndUnknownLeaveThePlayerPaused() = runBlocking {
		val cases = listOf(
			TranscriptResult.TimedOut to VoiceStatus.TimedOut,
			TranscriptResult.Unavailable to VoiceStatus.Unavailable,
			TranscriptResult.Text("forse riprendi") to VoiceStatus.Unrecognized,
		)
		for ((transcript, status) in cases) {
			val result = VoiceCommandCoordinator(
				pause = { true },
				transcribe = { transcript },
				dispatch = { error("The transcript must not cause a playback command") },
			).run()

			assertEquals(status, result)
		}
	}

	@Test
	fun cancellationDiscardsAResultThatArrivesLate() = runBlocking {
		lateinit var deliverTranscript: Continuation<TranscriptResult>
		val commands = mutableListOf<VoiceCommand>()
		val coordinator = VoiceCommandCoordinator(
			pause = { true },
			transcribe = { ready ->
				ready()
				suspendCoroutine { deliverTranscript = it }
			},
			dispatch = commands::add,
		)
		val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
		job.cancel()
		deliverTranscript.resume(TranscriptResult.Text("riprendi"))
		job.join()

		assertTrue(job.isCancelled)
		assertTrue(commands.isEmpty())
	}
}

class ItalianVoiceCommandParserTest {
	@Test
	fun acceptsExactCommandsAndSupportedRates() {
		assertEquals(VoiceCommand.Resume, ItalianVoiceCommandParser.parse("Riprendi."))
		assertEquals(VoiceCommand.StayPaused, ItalianVoiceCommandParser.parse("pausa"))
		assertEquals(VoiceCommand.SetRate(PlaybackRate(70)), ItalianVoiceCommandParser.parse("rallenta al settanta per cento"))
		assertEquals(VoiceCommand.SetRate(PlaybackRate(125)), ItalianVoiceCommandParser.parse("velocità al 125%"))
		assertEquals(VoiceCommand.SetRate(PlaybackRate(83)), ItalianVoiceCommandParser.parse("velocità al ottantatré"))
		assertEquals(VoiceCommand.SetRate(PlaybackRate(200)), ItalianVoiceCommandParser.parse("velocità al duecento"))
	}

	@Test
	fun rejectsNegationCompoundCommandsAndOutOfRangeRates() {
		assertNull(ItalianVoiceCommandParser.parse("non riprendere"))
		assertNull(ItalianVoiceCommandParser.parse("riprendi e rallenta al settanta"))
		assertNull(ItalianVoiceCommandParser.parse("rallenta al 24"))
		assertNull(ItalianVoiceCommandParser.parse("velocità al 201"))
	}
}
