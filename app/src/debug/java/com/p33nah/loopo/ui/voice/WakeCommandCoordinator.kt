package com.p33nah.loopo.ui.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal sealed interface WakeDetection {
	data object Detected : WakeDetection
	data object Unavailable : WakeDetection
	data object PermissionDenied : WakeDetection
	data object Failed : WakeDetection
}

internal class WakeCommandCoordinator(
	private val awaitWake: suspend (onListening: () -> Unit) -> WakeDetection,
	private val runCommand: suspend (onStatus: (VoiceStatus) -> Unit) -> VoiceStatus,
) {
	suspend fun listen(isCurrent: () -> Boolean, onStatus: (VoiceStatus) -> Unit) {
		while (isCurrent()) {
			val outcome = runTurn(isCurrent, onStatus)
			if (!isCurrent()) return
			onStatus(outcome)
			when (outcome) {
				VoiceStatus.Paused, VoiceStatus.CommandSent, VoiceStatus.TimedOut, VoiceStatus.Unrecognized,
				VoiceStatus.Failed, VoiceStatus.PauseFailed -> delay(1_000)
				else -> return
			}
		}
	}

	suspend fun runTurn(
		isCurrent: () -> Boolean,
		onStatus: (VoiceStatus) -> Unit = {},
	): VoiceStatus {
		if (!isCurrent()) return VoiceStatus.Cancelled
		val detection = awaitWake { onStatus(VoiceStatus.WakeListening) }
		currentCoroutineContext().ensureActive()
		if (!isCurrent()) return VoiceStatus.Cancelled

		return when (detection) {
			WakeDetection.Detected -> runCommand(onStatus)
			WakeDetection.Unavailable -> VoiceStatus.WakeUnavailable
			WakeDetection.PermissionDenied -> VoiceStatus.PermissionDenied
			WakeDetection.Failed -> VoiceStatus.WakeFailed
		}
	}
}
