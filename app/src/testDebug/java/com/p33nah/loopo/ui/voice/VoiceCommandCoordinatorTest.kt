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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandCoordinatorTest {
	@Test
	fun pausesBeforeListeningThenDispatchesACompleteOrderedTurn() = runBlocking {
		val events = mutableListOf<String>()
		val coordinator = VoiceCommandCoordinator(
			pause = { events += "pause"; true },
			transcribe = { ready -> ready(); TranscriptResult.Text("velocità al settanta e poi riprendi") },
				dispatch = { action -> events += if (action is VoiceAction.SetRate) "rate-${action.rate.percent}" else "play"; true },
		)
		assertEquals(VoiceStatus.CommandSent, coordinator.run())
		assertEquals(listOf("pause", "rate-70", "play"), events)
	}

	@Test
	fun failedPauseDoesNotStartRecognitionOrDispatch() = runBlocking {
		var recognitionStarted = false
		var dispatchCount = 0
		val result = VoiceCommandCoordinator(
			pause = { false },
			transcribe = { recognitionStarted = true; TranscriptResult.Text("riprendi") },
			dispatch = { dispatchCount++; true },
		).run()
		assertEquals(VoiceStatus.PauseFailed, result)
		assertFalse(recognitionStarted)
		assertEquals(0, dispatchCount)
	}

	@Test
	fun rateOnlyDoesNotResumeAndIncreaseVariantsKeepTheirMeaning() = runBlocking {
		val relative = ItalianVoiceCommandParser.parse("aumenta del 5% la velocità")!!
		assertEquals(VoiceAction.AdjustRate(RateDeltaPercent.from(5)!!), relative.actions.single())
		assertEquals(75, (ItalianVoiceCommandParser.parse("aumenta del cinque per cento la velocità")!!.actions.single() as VoiceAction.AdjustRate).delta.resolve(70)?.percent)
		assertEquals(75, (relative.actions.single() as VoiceAction.AdjustRate).delta.resolve(70)?.percent)
		assertNull((relative.actions.single() as VoiceAction.AdjustRate).delta.resolve(198))
		val absolute = ItalianVoiceCommandParser.parse("aumenta a 80 la velocità")!!
		assertEquals(VoiceAction.SetRate(PlaybackRate(80)), absolute.actions.single())

		val actions = mutableListOf<VoiceAction>()
		val result = VoiceCommandCoordinator(
			pause = { true }, transcribe = { TranscriptResult.Text("velocità al settanta") },
			dispatch = { actions += it; true },
		).run()
		assertEquals(VoiceStatus.CommandSent, result)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70))), actions)
	}

	@Test
	fun malformedOrOutOfRangeUtterancesCauseNoDispatch() = runBlocking {
		val invalid = listOf(
			"non riprendere", "forse velocità al settanta", "velocità al settanta e poi riprendi e poi pausa",
			"velocità al settanta e poi pausa", "aumenta del 5 la velocità", "velocità al 201",
		)
		for (phrase in invalid) {
			var calls = 0
			val result = VoiceCommandCoordinator(
				pause = { true }, transcribe = { TranscriptResult.Text(phrase) }, dispatch = { calls++; true },
			).run()
			assertEquals(VoiceStatus.Unrecognized, result)
			assertEquals(0, calls)
		}
		assertNull(ItalianVoiceCommandParser.parse("velocità al 24"))
	}

	@Test
	fun staleOrFailedFirstActionCannotContinueToPlay() = runBlocking {
		var current = true
		val actions = mutableListOf<VoiceAction>()
		val staleAfterRate = VoiceCommandCoordinator(
			pause = { true }, transcribe = { TranscriptResult.Text("velocità al settanta e poi riprendi") },
			dispatch = { action -> actions += action; current = false; true }, isCurrent = { current },
		).run()
		assertEquals(VoiceStatus.Cancelled, staleAfterRate)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70))), actions)

		actions.clear()
		val failedRate = VoiceCommandCoordinator(
			pause = { true }, transcribe = { TranscriptResult.Text("velocità al settanta e poi riprendi") },
			dispatch = { actions += it; false },
		).run()
		assertEquals(VoiceStatus.Failed, failedRate)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70))), actions)
	}

	@Test
	fun playWaitsUntilTheRateActionHasCompleted() = runBlocking {
		val rateApplied = CompletableDeferred<Unit>()
		val resume = CompletableDeferred<Unit>()
		val events = mutableListOf<String>()
		val coordinator = VoiceCommandCoordinator(
			pause = { true }, transcribe = { TranscriptResult.Text("velocità al settanta e poi riprendi") },
			dispatch = { action ->
				when (action) {
					is VoiceAction.SetRate -> { events += "rate-start"; rateApplied.await(); events += "rate-done" }
					VoiceAction.Resume -> { events += "play"; resume.complete(Unit) }
					else -> Unit
				}
				true
			},
		)
		val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
		assertEquals(listOf("rate-start"), events)
		rateApplied.complete(Unit)
		job.join()
		resume.await()
		assertEquals(listOf("rate-start", "rate-done", "play"), events)
	}

	@Test
	fun cancelledRecognitionResultDoesNotDispatch() = runBlocking {
		lateinit var deliverTranscript: Continuation<TranscriptResult>
		var calls = 0
		val coordinator = VoiceCommandCoordinator(
			pause = { true }, transcribe = { suspendCoroutine { deliverTranscript = it } },
			dispatch = { calls++; true },
		)
		val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
		job.cancel()
		deliverTranscript.resume(TranscriptResult.Text("riprendi"))
		job.join()
		assertTrue(job.isCancelled)
		assertEquals(0, calls)
	}
}

class ItalianVoiceCommandParserTest {
	@Test
	fun preservesLegacyExactCommandsAndRateForms() {
		assertEquals(listOf(VoiceAction.Resume), ItalianVoiceCommandParser.parse("Riprendi.")?.actions)
		assertEquals(listOf(VoiceAction.StayPaused), ItalianVoiceCommandParser.parse("pausa")?.actions)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70))), ItalianVoiceCommandParser.parse("rallenta al settanta per cento")?.actions)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(125))), ItalianVoiceCommandParser.parse("velocità al 125%")?.actions)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(83))), ItalianVoiceCommandParser.parse("velocità al ottantatré")?.actions)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(200))), ItalianVoiceCommandParser.parse("velocità al duecento")?.actions)
	}

	@Test
	fun rejectsUnsupportedClausesAndOutOfRangeRates() {
		assertNull(ItalianVoiceCommandParser.parse("riprendi e rallenta al settanta"))
		assertNull(ItalianVoiceCommandParser.parse("velocità al 24"))
		assertNull(ItalianVoiceCommandParser.parse("velocità al 201"))
		assertNull(ItalianVoiceCommandParser.parse("aumenta del 5 la velocità"))
	}
}
