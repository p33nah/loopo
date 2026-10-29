package com.p33nah.loopo.ui

import com.p33nah.loopo.data.audio.TrackInspection

sealed interface StudyUiState {
	data object Empty : StudyUiState
	data class Loading(val displayName: String) : StudyUiState
	data class Ready(val inspection: TrackInspection.Ready) : StudyUiState
	data class Error(val reason: TrackInspection.Reason) : StudyUiState
}
