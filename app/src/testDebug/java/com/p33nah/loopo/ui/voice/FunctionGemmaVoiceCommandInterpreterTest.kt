package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionGemmaVoiceCommandInterpreterTest {
	@Test
	fun mapsAllowedStructuredCallsIntoValidatedVoiceActions() {
		val result = FunctionGemmaVoiceCommandInterpreter.interpret(
			listOf(
				VoiceModelToolCall("set_rate", mapOf("percent" to 70L)),
				VoiceModelToolCall("restart", emptyMap()),
			),
		)

		assertAccepted(listOf(VoiceAction.SetRate(PlaybackRate(70)), VoiceAction.Restart), result)
	}

	@Test
	fun acceptsPlaybackCommandsAndIntegralJsonNumbers() {
		assertAction("resume", emptyMap(), VoiceAction.Resume)
		assertAction("restart", emptyMap(), VoiceAction.Restart)
		assertAction("stay_paused", emptyMap(), VoiceAction.StayPaused)
		assertAction("set_rate", mapOf("percent" to 125.0), VoiceAction.SetRate(PlaybackRate(125)))
		assertAction("adjust_rate", mapOf("delta_percent" to -5), VoiceAction.AdjustRate(RateDeltaPercent.from(-5)!!))
	}

	@Test
	fun acceptsExactIntegersFromGenericJsonNumberRepresentations() {
		assertAction("set_rate", mapOf("percent" to BigDecimal("70")), VoiceAction.SetRate(PlaybackRate(70)))
		assertAction(
			"adjust_rate",
			mapOf("delta_percent" to BigDecimal("-7")),
			VoiceAction.AdjustRate(RateDeltaPercent.from(-7)!!),
		)
		for (value in listOf(BigDecimal("70.01"), BigDecimal("4294967366"), Double.NaN, null)) {
			assertRejected(
				listOf(VoiceModelToolCall("set_rate", mapOf("percent" to value))),
				VoiceInterpretation.Rejection.InvalidArguments,
			)
		}
	}

	@Test
	fun rejectsUnknownToolsAndUnexpectedArguments() {
		assertRejected(
			listOf(VoiceModelToolCall("pause_everything", emptyMap())),
			VoiceInterpretation.Rejection.UnknownTool,
		)
		assertRejected(
			listOf(VoiceModelToolCall("resume", mapOf("force" to true))),
			VoiceInterpretation.Rejection.InvalidArguments,
		)
		assertRejected(
			listOf(VoiceModelToolCall("set_rate", mapOf("percent" to 70, "units" to "%"))),
			VoiceInterpretation.Rejection.InvalidArguments,
		)
	}

	@Test
	fun rejectsMissingNonIntegralAndOutOfRangeArguments() {
		for (call in listOf(
			VoiceModelToolCall("set_rate", emptyMap()),
			VoiceModelToolCall("set_rate", mapOf("percent" to 70.5)),
			VoiceModelToolCall("set_rate", mapOf("percent" to "70")),
			VoiceModelToolCall("set_rate", mapOf("percent" to 201)),
			VoiceModelToolCall("adjust_rate", mapOf("delta_percent" to 176)),
			VoiceModelToolCall("adjust_rate", mapOf("delta_percent" to 0)),
		)) {
			assertRejected(listOf(call), VoiceInterpretation.Rejection.InvalidArguments)
		}
	}

	@Test
	fun rejectsEmptyOrUnsupportedActionSequencesAsAWhole() {
		assertRejected(emptyList(), VoiceInterpretation.Rejection.NoToolCalls)
		assertRejected(
			listOf(
				VoiceModelToolCall("resume", emptyMap()),
				VoiceModelToolCall("restart", emptyMap()),
				VoiceModelToolCall("stay_paused", emptyMap()),
			),
			VoiceInterpretation.Rejection.TooManyToolCalls,
		)
		assertRejected(
			listOf(VoiceModelToolCall("resume", emptyMap()), VoiceModelToolCall("restart", emptyMap())),
			VoiceInterpretation.Rejection.InvalidPlan,
		)
		assertRejected(
			listOf(VoiceModelToolCall("restart", emptyMap()), VoiceModelToolCall("set_rate", mapOf("percent" to 70))),
			VoiceInterpretation.Rejection.InvalidPlan,
		)
	}

	@Test
	fun standaloneDryRunTracesDecisionWithoutPlaybackIntegration() = runBlocking {
		var requestCount = 0
		var trace: VoiceInterpreterTrace? = null
		val dryRun = FunctionGemmaVoiceDryRun(
			requestToolCalls = { transcript ->
				requestCount++
				assertEquals("riprendi", transcript)
				listOf(VoiceModelToolCall("resume", emptyMap()))
			},
			onTrace = { trace = it },
		)

		val result = dryRun.run("riprendi")

		assertTrue(result is VoiceInterpretation.Accepted)
		assertEquals(1, requestCount)
		assertEquals("riprendi", trace?.transcript)
		assertEquals(listOf(VoiceModelToolCall("resume", emptyMap())), trace?.toolCalls)
		assertEquals(result, trace?.interpretation)
		assertTrue((trace?.inferenceDurationNanos ?: -1) >= 0)
	}

	private fun assertAction(name: String, arguments: Map<String, Any?>, expected: VoiceAction) {
		val result = FunctionGemmaVoiceCommandInterpreter.interpret(listOf(VoiceModelToolCall(name, arguments)))
		assertAccepted(listOf(expected), result)
	}

	private fun assertAccepted(expected: List<VoiceAction>, result: VoiceInterpretation) {
		val actual = when (result) {
			is VoiceInterpretation.Accepted -> result.plan.actions
			is VoiceInterpretation.Rejected -> throw AssertionError("Expected acceptance, got ${result.reason}")
		}
		assertEquals(expected, actual)
	}

	private fun assertRejected(calls: List<VoiceModelToolCall>, reason: VoiceInterpretation.Rejection) {
		val result = FunctionGemmaVoiceCommandInterpreter.interpret(calls)
		assertEquals(VoiceInterpretation.Rejected(reason), result)
	}
}
