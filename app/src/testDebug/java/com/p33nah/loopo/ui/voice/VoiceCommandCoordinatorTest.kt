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
	fun appliesReportedAbsoluteRateAndResumePhrasesAsCompleteTurns() = runBlocking {
		val cases = listOf(
			"velocità a ottanta percento e poi riprendi" to listOf(VoiceAction.SetRate(PlaybackRate(80)), VoiceAction.Resume),
			"velocità a settantacinque" to listOf(VoiceAction.SetRate(PlaybackRate(75))),
			"velocità a 80 e poi parti" to listOf(VoiceAction.SetRate(PlaybackRate(80)), VoiceAction.Resume),
			"velocità a 75 e poi fai play" to listOf(VoiceAction.SetRate(PlaybackRate(75)), VoiceAction.Resume),
		)
		for ((phrase, expectedActions) in cases) {
			val actions = mutableListOf<VoiceAction>()
			val result = VoiceCommandCoordinator(
				pause = { true },
				transcribe = { TranscriptResult.Text(phrase) },
				dispatch = { actions += it; true },
			).run()

			assertEquals(phrase, VoiceStatus.CommandSent, result)
			assertEquals(phrase, expectedActions, actions)
		}
	}

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
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(80))), ItalianVoiceCommandParser.parse("aumenta la velocità a 80")?.actions)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(80))), ItalianVoiceCommandParser.parse("aumenta la velocità a ottanta per cento")?.actions)

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
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(100))), ItalianVoiceCommandParser.parse("velocità al cento")?.actions)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(200))), ItalianVoiceCommandParser.parse("velocità al duecento")?.actions)
	}

	@Test
	fun acceptsCommonCommandSynonymsAndFlexibleRatePhrasing() {
		assertEquals(listOf(VoiceAction.Resume), ItalianVoiceCommandParser.parse("riparti")?.actions)
		assertEquals(listOf(VoiceAction.Resume), ItalianVoiceCommandParser.parse("avvia la riproduzione")?.actions)
		assertEquals(listOf(VoiceAction.StayPaused), ItalianVoiceCommandParser.parse("metti in pausa")?.actions)
		assertEquals(listOf(VoiceAction.StayPaused), ItalianVoiceCommandParser.parse("interrompi la riproduzione")?.actions)

		assertEquals(
			listOf(VoiceAction.SetRate(PlaybackRate(75))),
			ItalianVoiceCommandParser.parse("regola il ritmo sul 75 percento")?.actions,
		)
		assertEquals(
			listOf(VoiceAction.SetRate(PlaybackRate(75))),
			ItalianVoiceCommandParser.parse("imposta il ritmo al settantacinque per cento")?.actions,
		)
		assertEquals(
			listOf(VoiceAction.AdjustRate(RateDeltaPercent.from(5)!!)),
			ItalianVoiceCommandParser.parse("incrementa di 5 punti percentuali il ritmo")?.actions,
		)
		assertEquals(
			listOf(VoiceAction.AdjustRate(RateDeltaPercent.from(-10)!!)),
			ItalianVoiceCommandParser.parse("riduci il ritmo di dieci punti percentuali")?.actions,
		)
		assertEquals(
			listOf(VoiceAction.SetRate(PlaybackRate(75)), VoiceAction.Resume),
			ItalianVoiceCommandParser.parse("regola il ritmo sul 75 percento e poi avvia la riproduzione")?.actions,
		)
	}

	@Test
	fun rejectsUnclearSynonymCommandsWithoutDispatchablePlans() {
		for (phrase in listOf(
			"metti in pausa o riparti",
			"riduci il ritmo di cinque",
			"incrementa il ritmo di cinque o dieci punti percentuali",
			"forse regola il ritmo sul 75 percento",
		)) {
			assertNull(phrase, ItalianVoiceCommandParser.parse(phrase))
		}
	}

	@Test
	fun rejectsUnsupportedClausesAndOutOfRangeRates() {
		assertNull(ItalianVoiceCommandParser.parse("riprendi e rallenta al settanta"))
		assertNull(ItalianVoiceCommandParser.parse("velocità al 24"))
		assertNull(ItalianVoiceCommandParser.parse("velocità al 201"))
		assertNull(ItalianVoiceCommandParser.parse("aumenta del 5 la velocità"))
	}

	@Test
	fun parsesAbsoluteSpeedByMeaningAcrossWordOrders() {
		val expected = listOf(VoiceAction.SetRate(PlaybackRate(80)))
		for (phrase in listOf(
			"aumenta la velocità a 80",
			"aumenta a 80 la velocità",
			"aumenta a ottanta la velocità",
			"aumenta la velocità a ottanta per cento",
			"velocità all’ottanta",
			"porta la velocità all'ottanta per cento",
		)) {
			assertEquals(phrase, expected, ItalianVoiceCommandParser.parse(phrase)?.actions)
		}
	}

	@Test
	fun parsesRelativeSpeedInEitherOrderAndKeepsPercentUnit() {
		val increase = VoiceAction.AdjustRate(RateDeltaPercent.from(5)!!)
		val decrease = VoiceAction.AdjustRate(RateDeltaPercent.from(-10)!!)
		for (phrase in listOf("aumenta del 5% la velocità", "aumenta la velocità del cinque per cento")) {
			assertEquals(phrase, listOf(increase), ItalianVoiceCommandParser.parse(phrase)?.actions)
		}
		assertEquals(
			listOf(VoiceAction.AdjustRate(RateDeltaPercent.from(100)!!)),
			ItalianVoiceCommandParser.parse("aumenta del cento per cento la velocità")?.actions,
		)
		for (phrase in listOf("diminuisci di 10 per cento la velocità", "diminuisci la velocità del dieci per cento")) {
			assertEquals(phrase, listOf(decrease), ItalianVoiceCommandParser.parse(phrase)?.actions)
		}
	}

	@Test
	fun rejectsAmbiguousOrUnsafeSpeedUtterancesAsAWhole() {
		for (phrase in listOf(
			"non aumentare la velocità a 80",
			"aumenta la velocità forse a 80",
			"aumenta la velocità a 80 o 90",
			"aumenta la velocità a 80 e diminuisci di 5 per cento",
			"aumenta la velocità a 80 e poi pausa",
			"aumenta la velocità a 80 riprendi",
			"aumenta la velocità a 80 con il loop",
			"aumenta la velocità del 5",
			"aumenta 80 la velocità",
			"velocità a la 80",
			"velocità a 80 la la",
			"velocità 80",
			"velocità al -80",
			"aumenta del +5% la velocità",
			"velocità al 201",
		)) {
			assertNull(phrase, ItalianVoiceCommandParser.parse(phrase))
		}
		assertEquals(
			listOf(VoiceAction.SetRate(PlaybackRate(80)), VoiceAction.Resume),
			ItalianVoiceCommandParser.parse("aumenta la velocità a 80 e poi continua")?.actions,
		)
	}
}
