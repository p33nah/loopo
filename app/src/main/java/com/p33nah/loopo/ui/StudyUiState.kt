package com.p33nah.loopo.ui

import com.p33nah.loopo.data.audio.TrackInspection

sealed interface StudyUiState {
	data object Empty : StudyUiState
	data class Loading(val displayName: String) : StudyUiState
	data class Ready(val inspection: TrackInspection.Ready, val sessionWarning: String? = null) : StudyUiState
	data class Error(val reason: TrackInspection.Reason) : StudyUiState
	data class RecoveryRequired(val displayName: String, val message: String) : StudyUiState
}
