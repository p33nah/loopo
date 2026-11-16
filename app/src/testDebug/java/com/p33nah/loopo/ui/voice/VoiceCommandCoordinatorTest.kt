package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
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
	fun rateCommandResumesOnlyWhenTheVoiceTurnPausedActivePlayback() = runBlocking {
		val cases = listOf(
			true to listOf(VoiceAction.SetRate(PlaybackRate(80)), VoiceAction.Resume),
			false to listOf(VoiceAction.SetRate(PlaybackRate(80))),
		)
		for ((wasPlaying, expectedActions) in cases) {
			val actions = mutableListOf<VoiceAction>()
			val result = VoiceCommandCoordinator(
				pause = { VoicePauseOutcome.Paused(wasPlaying) },
				transcribe = { TranscriptResult.Text("velocità a ottanta percento") },
				dispatch = { actions += it; true },
			).run()

			assertEquals(VoiceStatus.CommandSent, result)
			assertEquals(expectedActions, actions)
		}
	}

	@Test
	fun absoluteRateCanChainIntoRestart() = runBlocking {
		val actions = mutableListOf<VoiceAction>()
		val result = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(false) },
			transcribe = { TranscriptResult.Text("velocità al settanta e poi riavvia la riproduzione") },
			dispatch = { actions += it; true },
		).run()

		assertEquals(VoiceStatus.CommandSent, result)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70)), VoiceAction.Restart), actions)
	}

	@Test
	fun pausesBeforeListeningThenDispatchesACompleteOrderedTurn() = runBlocking {
		val events = mutableListOf<String>()
		val coordinator = VoiceCommandCoordinator(
			pause = { events += "pause"; VoicePauseOutcome.Paused(true) },
			transcribe = { ready -> ready(); TranscriptResult.Text("velocità al settanta") },
			dispatch = { action -> events += if (action is VoiceAction.SetRate) "rate-${action.rate.percent}" else "play"; true },
		)
		assertEquals(VoiceStatus.CommandSent, coordinator.run())
		assertEquals(listOf("pause", "rate-70", "play"), events)
	}

	@Test
	fun optInInterpreterRunsAfterCaptureAndDispatchesItsValidatedPlanInOrder() = runBlocking {
		val events = mutableListOf<String>()
		val plan = VoicePlan.validated(listOf(VoiceAction.SetRate(PlaybackRate(70)), VoiceAction.Restart))!!
		val result = VoiceCommandCoordinator(
			pause = { events += "pause"; VoicePauseOutcome.Paused(true) },
			transcribe = { events += "transcribe"; TranscriptResult.Text("ricomincia") },
			interpret = { transcript ->
				assertEquals("ricomincia", transcript)
				events += "interpret"
				plan
			},
			dispatch = { action ->
				events += when (action) {
					is VoiceAction.SetRate -> "rate-${action.rate.percent}"
					VoiceAction.Restart -> "restart"
					VoiceAction.Resume -> "resume"
					VoiceAction.StayPaused -> "stay-paused"
					is VoiceAction.AdjustRate -> "adjust-rate"
				}
				true
			},
		).run()

		assertEquals(VoiceStatus.CommandSent, result)
		assertEquals(
			listOf("pause", "transcribe", "interpret", "rate-70", "restart"),
			events,
		)
	}

	@Test
	fun staleSessionAfterOptInInferenceCannotDispatchTheReturnedPlan() = runBlocking {
		var current = true
		var dispatches = 0
		val result = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(false) },
			transcribe = { TranscriptResult.Text("riprendi") },
			interpret = {
				current = false
				VoicePlan.validated(listOf(VoiceAction.Resume))
			},
			dispatch = { dispatches++; true },
			isCurrent = { current },
		).run()

		assertEquals(VoiceStatus.Cancelled, result)
		assertEquals(0, dispatches)
	}

	@Test
	fun optInInterpreterFailureReportsFailureWithoutDispatch() = runBlocking {
		var dispatches = 0
		val result = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(false) },
			transcribe = { TranscriptResult.Text("riprendi") },
			interpret = { throw IllegalStateException("runtime unavailable") },
			dispatch = { dispatches++; true },
		).run()

		assertEquals(VoiceStatus.Failed, result)
		assertEquals(0, dispatches)
	}

	@Test
	fun coroutineCancellationDuringOptInInferencePreventsDispatch() = runBlocking {
		val inferenceStarted = CompletableDeferred<Unit>()
		val inferenceResult = CompletableDeferred<VoicePlan?>()
		var dispatches = 0
		val coordinator = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(false) },
			transcribe = { TranscriptResult.Text("riprendi") },
			interpret = { inferenceStarted.complete(Unit); inferenceResult.await() },
			dispatch = { dispatches++; true },
		)
		val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }

		inferenceStarted.await()
		job.cancelAndJoin()

		assertTrue(job.isCancelled)
		assertEquals(0, dispatches)
	}

	@Test
	fun restartWhilePlayingDispatchesOneStartingAction() = runBlocking {
		val actions = mutableListOf<VoiceAction>()
		val result = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(true) },
			transcribe = { TranscriptResult.Text("ricomincia da capo") },
			dispatch = { actions += it; true },
		).run()

		assertEquals(VoiceStatus.CommandSent, result)
		assertEquals(listOf(VoiceAction.Restart), actions)
	}

	@Test
	fun explicitPauseAndResumeOverrideAutomaticRestoration() = runBlocking {
		val actions = mutableListOf<VoiceAction>()
		val pauseResult = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(true) },
			transcribe = { TranscriptResult.Text("pausa") },
			dispatch = { actions += it; true },
		).run()
		assertEquals(VoiceStatus.Paused, pauseResult)
		assertEquals(listOf(VoiceAction.StayPaused), actions)

		actions.clear()
		val resumeResult = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(false) },
			transcribe = { TranscriptResult.Text("riprendi") },
			dispatch = { actions += it; true },
		).run()
		assertEquals(VoiceStatus.CommandSent, resumeResult)
		assertEquals(listOf(VoiceAction.Resume), actions)

	}

	@Test
	fun failedPauseDoesNotStartRecognitionOrDispatch() = runBlocking {
		var recognitionStarted = false
		var dispatchCount = 0
		val result = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Failed },
			transcribe = { recognitionStarted = true; TranscriptResult.Text("riprendi") },
			dispatch = { dispatchCount++; true },
		).run()
		assertEquals(VoiceStatus.PauseFailed, result)
		assertFalse(recognitionStarted)
		assertEquals(0, dispatchCount)
	}

	@Test
	fun rateCommandsPreserveRateArithmeticAndStayPausedWhenAlreadyPaused() = runBlocking {
		val relativeAction = ItalianVoiceCommandParser.parse("aumenta del 5% la velocità")!!.actions.single() as VoiceAction.AdjustRate
		assertEquals(VoiceAction.AdjustRate(RateDeltaPercent.from(5)!!), relativeAction)
		assertEquals(75, (ItalianVoiceCommandParser.parse("aumenta del cinque per cento la velocità")!!.actions.single() as VoiceAction.AdjustRate).delta.resolve(70)?.percent)
		assertEquals(75, relativeAction.delta.resolve(70)?.percent)
		assertNull(relativeAction.delta.resolve(198))
		val absolute = ItalianVoiceCommandParser.parse("aumenta a 80 la velocità")!!.actions.single()
		assertEquals(VoiceAction.SetRate(PlaybackRate(80)), absolute)
		assertEquals(VoiceAction.SetRate(PlaybackRate(80)), ItalianVoiceCommandParser.parse("aumenta la velocità a 80")?.actions?.single())
		assertEquals(VoiceAction.SetRate(PlaybackRate(80)), ItalianVoiceCommandParser.parse("aumenta la velocità a ottanta per cento")?.actions?.single())

		val actions = mutableListOf<VoiceAction>()
		val result = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(false) }, transcribe = { TranscriptResult.Text("velocità al settanta") },
			dispatch = { actions += it; true },
		).run()
		assertEquals(VoiceStatus.CommandSent, result)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70))), actions)
	}

	@Test
	fun failedAutomaticResumeReportsFailure() = runBlocking {
		val actions = mutableListOf<VoiceAction>()
		val result = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(true) },
			transcribe = { TranscriptResult.Text("velocità al settanta") },
			dispatch = { action -> actions += action; action != VoiceAction.Resume },
		).run()
		assertEquals(VoiceStatus.Failed, result)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70)), VoiceAction.Resume), actions)
	}

	@Test
	fun malformedOrOutOfRangeUtterancesCauseNoDispatch() = runBlocking {
		val invalid = listOf(
			"non riprendere", "forse velocità al settanta",
			"velocità al settanta e poi pausa", "aumenta del 5 la velocità", "velocità al 201",
		)
		for (phrase in invalid) {
			var calls = 0
			val result = VoiceCommandCoordinator(
				pause = { VoicePauseOutcome.Paused(true) }, transcribe = { TranscriptResult.Text(phrase) }, dispatch = { calls++; true },
			).run()
			assertEquals(VoiceStatus.Unrecognized, result)
			assertEquals(0, calls)
		}
		assertNull(ItalianVoiceCommandParser.parse("velocità al 24"))
	}

	@Test
	fun staleOrFailedRateCannotTriggerAutomaticResume() = runBlocking {
		var current = true
		val actions = mutableListOf<VoiceAction>()
		val staleAfterRate = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(true) }, transcribe = { TranscriptResult.Text("velocità al settanta") },
			dispatch = { action -> actions += action; current = false; true }, isCurrent = { current },
		).run()
		assertEquals(VoiceStatus.Cancelled, staleAfterRate)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70))), actions)

		actions.clear()
		val failedRate = VoiceCommandCoordinator(
			pause = { VoicePauseOutcome.Paused(true) }, transcribe = { TranscriptResult.Text("velocità al settanta") },
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
			pause = { VoicePauseOutcome.Paused(true) }, transcribe = { TranscriptResult.Text("velocità al settanta") },
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
			pause = { VoicePauseOutcome.Paused(false) }, transcribe = { suspendCoroutine { deliverTranscript = it } },
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
		assertEquals(VoiceAction.Resume, ItalianVoiceCommandParser.parse("Riprendi.")?.actions?.single())
		assertEquals(VoiceAction.StayPaused, ItalianVoiceCommandParser.parse("pausa")?.actions?.single())
		assertEquals(VoiceAction.SetRate(PlaybackRate(70)), ItalianVoiceCommandParser.parse("rallenta al settanta per cento")?.actions?.single())
		assertEquals(VoiceAction.SetRate(PlaybackRate(125)), ItalianVoiceCommandParser.parse("velocità al 125%")?.actions?.single())
		assertEquals(VoiceAction.SetRate(PlaybackRate(83)), ItalianVoiceCommandParser.parse("velocità al ottantatré")?.actions?.single())
		assertEquals(VoiceAction.SetRate(PlaybackRate(100)), ItalianVoiceCommandParser.parse("velocità al cento")?.actions?.single())
		assertEquals(VoiceAction.SetRate(PlaybackRate(200)), ItalianVoiceCommandParser.parse("velocità al duecento")?.actions?.single())
	}

	@Test
	fun acceptsCommonCommandSynonymsAndFlexibleRatePhrasing() {
		assertEquals(VoiceAction.Resume, ItalianVoiceCommandParser.parse("riparti")?.actions?.single())
		assertEquals(VoiceAction.Resume, ItalianVoiceCommandParser.parse("avvia la riproduzione")?.actions?.single())
		assertEquals(VoiceAction.StayPaused, ItalianVoiceCommandParser.parse("metti in pausa")?.actions?.single())
		assertEquals(VoiceAction.StayPaused, ItalianVoiceCommandParser.parse("interrompi la riproduzione")?.actions?.single())

		assertEquals(
			VoiceAction.SetRate(PlaybackRate(75)),
			ItalianVoiceCommandParser.parse("regola il ritmo sul 75 percento")?.actions?.single(),
		)
		assertEquals(
			VoiceAction.SetRate(PlaybackRate(75)),
			ItalianVoiceCommandParser.parse("imposta il ritmo al settantacinque per cento")?.actions?.single(),
		)
		assertEquals(
			VoiceAction.AdjustRate(RateDeltaPercent.from(5)!!),
			ItalianVoiceCommandParser.parse("incrementa di 5 punti percentuali il ritmo")?.actions?.single(),
		)
		assertEquals(
			VoiceAction.AdjustRate(RateDeltaPercent.from(-10)!!),
			ItalianVoiceCommandParser.parse("riduci il ritmo di dieci punti percentuali")?.actions?.single(),
		)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(75)), VoiceAction.Resume), ItalianVoiceCommandParser.parse("regola il ritmo sul 75 percento e poi avvia la riproduzione")?.actions)
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70)), VoiceAction.Resume), ItalianVoiceCommandParser.parse("rallenta al settanta e poi riprendi")?.actions)
	}

	@Test
	fun parsesExplicitRestartPhrasesWithoutChangingResumeCommands() {
		val restart = listOf(VoiceAction.Restart)
		for (phrase in listOf(
			"ricomincia da capo", "ricomincia", "ricomincia da zero", "ricomincia dall'inizio",
			"ricomincia il brano", "ricomincia il loop", "riproduci dall'inizio", "riproduci da capo",
			"riproduci il brano dall'inizio", "riproduci il loop dall'inizio", "riparti dall'inizio",
			"riprendi dall'inizio", "parti dall'inizio", "avvia dall'inizio", "dall'inizio", "all'inizio", "da capo", "da zero",
			"riparti da capo", "riparti da zero", "riavvia da capo", "riavvia da zero", "riavvia dall'inizio",
			"riavvia il brano", "riavvia il loop", "solo dall'inizio", "solamente dall'inizio",
			"solo da capo", "solamente da capo", "torna all'inizio", "torna a zero", "inizia da capo",
			"fai ripartire dall'inizio",
			"ripeti da capo", "ripeti dall'inizio", "riavvia la riproduzione",
		)) {
			assertEquals(phrase, restart, ItalianVoiceCommandParser.parse(phrase)?.actions)
		}

		for (phrase in listOf("riprendi", "riparti", "parti", "continua", "play", "avvia la riproduzione")) {
			assertEquals(phrase, listOf(VoiceAction.Resume), ItalianVoiceCommandParser.parse(phrase)?.actions)
		}
	}

	@Test
	fun rejectsUnclearSynonymCommandsWithoutActions() {

		for (phrase in listOf(
			"metti in pausa o riparti",
			"riduci il ritmo di cinque",
			"incrementa il ritmo di cinque o dieci punti percentuali",
			"forse regola il ritmo sul 75 percento",
			"non ricominciare", "forse da capo", "ricomincia un po'", "ricomincia e poi pausa",
			"dall'inizio e poi riprendi", "dall'inizio con il loop",
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
		val expected = VoiceAction.SetRate(PlaybackRate(80))
		for (phrase in listOf(
			"aumenta la velocità a 80",
			"aumenta a 80 la velocità",
			"aumenta a ottanta la velocità",
			"aumenta la velocità a ottanta per cento",
			"velocità all’ottanta",
			"porta la velocità all'ottanta per cento",
		)) {
			assertEquals(phrase, listOf(expected), ItalianVoiceCommandParser.parse(phrase)?.actions)
		}
	}

	@Test
	fun acceptsRaiseAndLowerSynonymsForAbsoluteSpeed() {
		val cases = mapOf(
			"abbassa la velocità a 70" to VoiceAction.SetRate(PlaybackRate(70)),
			"alza la velocità a 80" to VoiceAction.SetRate(PlaybackRate(80)),
			"aumenta la velocità a 80" to VoiceAction.SetRate(PlaybackRate(80)),
			"diminuisci la velocità a 70" to VoiceAction.SetRate(PlaybackRate(70)),
		)
		for ((phrase, expected) in cases) {
			assertEquals(phrase, listOf(expected), ItalianVoiceCommandParser.parse(phrase)?.actions)
		}

		val increase = VoiceAction.AdjustRate(RateDeltaPercent.from(5)!!)
		val decrease = VoiceAction.AdjustRate(RateDeltaPercent.from(-5)!!)
		for (phrase in listOf(
			"alza la velocità del cinque per cento",
			"aumenta la velocità del cinque per cento",
		)) {
			assertEquals(phrase, listOf(increase), ItalianVoiceCommandParser.parse(phrase)?.actions)
		}
		for (phrase in listOf(
			"abbassa la velocità del cinque per cento",
			"diminuisci la velocità del cinque per cento",
		)) {
			assertEquals(phrase, listOf(decrease), ItalianVoiceCommandParser.parse(phrase)?.actions)
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
			VoiceAction.AdjustRate(RateDeltaPercent.from(100)!!),
			ItalianVoiceCommandParser.parse("aumenta del cento per cento la velocità")?.actions?.single(),
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
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(80)), VoiceAction.Resume), ItalianVoiceCommandParser.parse("aumenta la velocità a 80 e poi continua")?.actions)
	}
}
