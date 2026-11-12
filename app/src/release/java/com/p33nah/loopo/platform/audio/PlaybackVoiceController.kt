package com.p33nah.loopo.platform.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class PlaybackVoiceController(service: PlaybackService) : PlaybackVoiceSession {
	private val empty = MutableStateFlow(VoiceUiState.Idle)
	override val status: StateFlow<VoiceUiState> = empty
	override val isArmed: Boolean = false
	override fun arm() = Unit
	override fun cancelTurn() = Unit
	override fun close() = Unit
	override fun setStatus(value: String?) = Unit
}
