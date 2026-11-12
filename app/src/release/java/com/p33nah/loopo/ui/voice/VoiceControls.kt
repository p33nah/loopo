package com.p33nah.loopo.ui.voice

import android.net.Uri
import androidx.compose.runtime.Composable
import com.p33nah.loopo.platform.audio.PlaybackService
import com.p33nah.loopo.platform.audio.VoiceUiState

@Composable
internal fun VoiceStudy(
	session: PlaybackService.Session,
	trackUri: Uri?,
	isSessionActive: () -> Boolean,
	content: @Composable (cancelVoice: () -> Unit, voiceControls: (@Composable () -> Unit)?, feedback: VoiceUiState) -> Unit,
) {
	content({}, null, VoiceUiState.Idle)
}
