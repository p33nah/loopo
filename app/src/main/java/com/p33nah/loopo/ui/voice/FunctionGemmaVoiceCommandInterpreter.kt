package com.p33nah.loopo.ui.voice

import com.p33nah.loopo.domain.model.PlaybackRate

/** One structured tool call returned by a FunctionGemma compatible runtime. */
internal data class VoiceModelToolCall(
	val name: String,
	val arguments: Map<String, Any?>,
)

internal sealed interface VoiceInterpretation {
	data class Accepted(val plan: VoicePlan) : VoiceInterpretation
	data class Rejected(val reason: Rejection) : VoiceInterpretation

	enum class Rejection {
		NoToolCalls,
		TooManyToolCalls,
		UnknownTool,
		InvalidArguments,
		InvalidPlan,
		UnconfirmedTranscript,
	}
}

/**
 * Converts the runtime's structured FunctionGemma tool calls into the app's validated voice plan.
 * This boundary never executes a tool or dispatches a playback action.
 */
internal object FunctionGemmaVoiceCommandInterpreter {
	private const val MAX_TOOL_CALLS = 2

	fun interpret(toolCalls: List<VoiceModelToolCall>): VoiceInterpretation {
		if (toolCalls.isEmpty()) return VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.NoToolCalls)
		if (toolCalls.size > MAX_TOOL_CALLS) {
			return VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.TooManyToolCalls)
		}

		val actions = toolCalls.map { call ->
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
		return if (plan == null) {
			VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.InvalidPlan)
		} else {
			VoiceInterpretation.Accepted(plan)
		}
	}

	fun requireParserAgreement(
		interpretation: VoiceInterpretation,
		parserPlan: VoicePlan?,
	): VoiceInterpretation = when (interpretation) {
		is VoiceInterpretation.Rejected -> interpretation
		is VoiceInterpretation.Accepted -> if (interpretation.plan.actions == parserPlan?.actions) {
			interpretation
		} else {
			VoiceInterpretation.Rejected(VoiceInterpretation.Rejection.UnconfirmedTranscript)
		}
	}

	private fun noArguments(call: VoiceModelToolCall): Unit? =
		Unit.takeIf { call.arguments.isEmpty() }

	private fun integerArgument(call: VoiceModelToolCall, expectedName: String): Int? {
		if (call.arguments.keys != setOf(expectedName)) return null
		return call.arguments[expectedName].asInteger()
	}

	private fun Any?.asInteger(): Int? {
		val number = this as? Number ?: return null
		val decimal = number.toString().toBigDecimalOrNull() ?: return null
		return try {
			decimal.intValueExact()
		} catch (_: ArithmeticException) {
			null
		}
	}
}

/** In-memory trace for a standalone dry run. Callers decide whether and how to display it. */
internal data class VoiceInterpreterTrace(
	val transcript: String,
	val toolCalls: List<VoiceModelToolCall>,
	val interpretation: VoiceInterpretation,
	val inferenceDurationNanos: Long,
)

/**
 * Runs an injected model adapter and records its structured interpretation without playback side
 * effects. This can be exercised independently of the wake listener and its pause-before-capture
 * behavior.
 */
internal class FunctionGemmaVoiceDryRun(
	private val requestToolCalls: suspend (transcript: String) -> List<VoiceModelToolCall>,
	private val onTrace: (VoiceInterpreterTrace) -> Unit = {},
) {
	suspend fun run(transcript: String): VoiceInterpretation {
		val startedAtNanos = System.nanoTime()
		val calls = requestToolCalls(transcript)
		val inferenceDurationNanos = System.nanoTime() - startedAtNanos
		val result = FunctionGemmaVoiceCommandInterpreter.interpret(calls)
		onTrace(VoiceInterpreterTrace(transcript, calls.toList(), result, inferenceDurationNanos))
		return result
	}
}
