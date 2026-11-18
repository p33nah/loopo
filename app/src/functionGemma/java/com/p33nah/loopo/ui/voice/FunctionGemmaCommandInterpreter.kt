package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate

internal data class VoiceModelToolCall(val name: String, val arguments: Map<String, Any?>)

internal sealed interface VoiceInterpretation {
	data class Accepted(val plan: VoicePlan) : VoiceInterpretation
	data class Rejected(val reason: Rejection) : VoiceInterpretation

	enum class Rejection { NoToolCalls, TooManyToolCalls, UnknownTool, InvalidArguments, InvalidPlan, UnconfirmedTranscript }
}

/** Validates the full structured response and never invokes a tool. */
internal object FunctionGemmaCommandInterpreter {
	private const val MAX_CALLS = 2

	fun interpret(calls: List<VoiceModelToolCall>): VoiceInterpretation {
		if (calls.isEmpty()) return VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.NoToolCalls)
		if (calls.size > MAX_CALLS) return VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.TooManyToolCalls)

		val actions = calls.map { call ->
			when (call.name) {
				"resume" -> noArguments(call)?.let { VoiceAction.Resume }
				"restart" -> noArguments(call)?.let { VoiceAction.Restart }
				"stay_paused" -> noArguments(call)?.let { VoiceAction.StayPaused }
				"set_rate" -> integerArgument(call, "percent")
					?.takeIf { it in PlaybackRate.MIN_PERCENT..PlaybackRate.MAX_PERCENT }
					?.let { VoiceAction.SetRate(PlaybackRate(it)) }
				"adjust_rate" -> integerArgument(call, "delta_percent")
					?.let(RateDeltaPercent::from)
					?.let(VoiceAction::AdjustRate)
				else -> return VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.UnknownTool)
			} ?: return VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.InvalidArguments)
		}
		val plan = VoicePlan.validated(actions)
		return plan?.let(VoiceInterpretation::Accepted)
			?: VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.InvalidPlan)
	}

	fun requireParserAgreement(interpretation: VoiceInterpretation, parserPlan: VoicePlan?): VoiceInterpretation =
		when (interpretation) {
			is VoiceInterpretation.Rejected -> interpretation
			is VoiceInterpretation.Accepted -> if (interpretation.plan.actions == parserPlan?.actions) interpretation
			else VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.UnconfirmedTranscript)
		}

	fun renderLabel(interpretation: VoiceInterpretation): String = when (interpretation) {
		is VoiceInterpretation.Accepted -> "piano valido"
		is VoiceInterpretation.Rejected -> "rifiuto: ${interpretation.reason.name}"
	}

	private fun noArguments(call: VoiceModelToolCall): Unit? = Unit.takeIf { call.arguments.isEmpty() }

	private fun integerArgument(call: VoiceModelToolCall, name: String): Int? {
		if (call.arguments.keys != setOf(name)) return null
		val number = call.arguments[name] as? Number ?: return null
		return try {
			number.toString().toBigDecimalOrNull()?.intValueExact()
		} catch (_: ArithmeticException) {
			null
		}
	}

}
