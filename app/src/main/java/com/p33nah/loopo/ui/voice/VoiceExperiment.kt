package com.p33nah.loopo.ui.voice

import androidx.compose.runtime.Composable

internal interface VoiceExperiment {
	@Composable
	fun Controls(armed: Boolean)

	suspend fun open(): VoiceExperimentSession
}

internal interface VoiceExperimentSession : AutoCloseable {
	suspend fun interpret(transcript: String): VoicePlan?
	fun report(timing: VoiceTurnTiming)
}

internal class VoiceExperimentUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class VoiceTurnTiming(
	val captureMs: Long?,
	val interpretationMs: Long?,
	val dispatchMs: Long?,
	val totalMs: Long,
	val outcome: VoiceStatus,
)
