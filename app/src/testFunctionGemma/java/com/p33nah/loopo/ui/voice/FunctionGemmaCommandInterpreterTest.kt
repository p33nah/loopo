package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionGemmaCommandInterpreterTest {
	@Test
	fun exactIntegerCallsProduceTypedActionsAndKeepTheirOrder() {
		val result = FunctionGemmaCommandInterpreter.interpret(listOf(
			VoiceModelToolCall("set_rate", mapOf("percent" to BigDecimal("70.0"))),
			VoiceModelToolCall("restart", emptyMap()),
		))
		assertEquals(listOf(VoiceAction.SetRate(PlaybackRate(70)), VoiceAction.Restart),
			(result as VoiceInterpretation.Accepted).plan.actions)
	}

	@Test
	fun malformedNumbersUnsupportedToolsAndUnsupportedOrderAreRejected() {
		val resume = VoiceModelToolCall("resume", emptyMap())
		for (calls in listOf(
			listOf(VoiceModelToolCall("set_rate", mapOf("percent" to BigDecimal("70.1")))),
			listOf(VoiceModelToolCall("set_rate", mapOf("percent" to 201))),
			listOf(VoiceModelToolCall("adjust_rate", mapOf("delta_percent" to 0))),
			listOf(VoiceModelToolCall("seek", mapOf("seconds" to 30))),
			listOf(resume, VoiceModelToolCall("set_rate", mapOf("percent" to 70))),
		)) {
			assertTrue(FunctionGemmaCommandInterpreter.interpret(calls) is VoiceInterpretation.Rejected)
		}
	}

	@Test
	fun parserAgreementBlocksWrongButWellFormedNumericParameters() {
		val plan = FunctionGemmaCommandInterpreter.interpret(listOf(VoiceModelToolCall("set_rate", mapOf("percent" to 71))))
		assertTrue(plan is VoiceInterpretation.Accepted)
		val parser = ItalianVoiceCommandParser.parse("imposta la velocità al settanta per cento")
		assertEquals(
			VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.UnconfirmedTranscript),
			FunctionGemmaCommandInterpreter.requireParserAgreement(plan, parser),
		)
	}
}
