package com.p33nah.loopo.platform.audio

import kotlinx.coroutines.flow.StateFlow

enum class VoicePhase { Idle, WakeListening, Pausing, Listening, Applying, Feedback }
enum class VoiceTone { Neutral, Success, Error }

data class VoiceUiState(
	val phase: VoicePhase,
	val message: String? = null,
	val tone: VoiceTone = VoiceTone.Neutral,
	val recognizedTranscript: String? = null,
) {
	val showCommandListeningBorder: Boolean get() = phase == VoicePhase.Listening

	companion object { val Idle = VoiceUiState(VoicePhase.Idle) }
}

/** Variant implementation keeps microphone capture out of release builds. */
internal interface PlaybackVoiceSession {
	val status: StateFlow<VoiceUiState>
	val isArmed: Boolean
	fun arm()
	fun cancelTurn()
	fun close()
	fun setStatus(value: String?)
}
