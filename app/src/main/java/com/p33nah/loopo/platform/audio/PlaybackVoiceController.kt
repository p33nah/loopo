package com.p33nah.loopo.platform.audio

import kotlinx.coroutines.flow.StateFlow

/** Variant implementation keeps microphone capture out of release builds. */
internal interface PlaybackVoiceSession {
	val status: StateFlow<String?>
	val isArmed: Boolean
	fun arm()
	fun cancelTurn()
	fun close()
	fun setStatus(value: String?)
}
