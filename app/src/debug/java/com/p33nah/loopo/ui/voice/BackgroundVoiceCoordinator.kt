package com.p33nah.loopo.ui.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class BackgroundVoiceCoordinator(
	private val awaitWake: suspend () -> WakeDetection,
	private val pause: suspend () -> Boolean,
) {
	suspend fun runTurn(isCurrent: () -> Boolean): VoiceStatus {
		if (!isCurrent()) return VoiceStatus.Cancelled
		val wake = awaitWake()
		currentCoroutineContext().ensureActive()
		if (!isCurrent()) return VoiceStatus.Cancelled
		return when (wake) {
			WakeDetection.Detected -> if (pause()) VoiceStatus.Paused else VoiceStatus.PauseFailed
			WakeDetection.Unavailable -> VoiceStatus.WakeUnavailable
			WakeDetection.PermissionDenied -> VoiceStatus.PermissionDenied
			WakeDetection.Failed -> VoiceStatus.WakeFailed
		}
	}
}
