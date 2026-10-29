package com.p33nah.loopo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.p33nah.loopo.data.audio.AndroidTrackInspector
import com.p33nah.loopo.data.audio.TrackInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudyViewModel(application: Application) : AndroidViewModel(application) {
	private val inspector = AndroidTrackInspector(application)
	private val mutableState = MutableStateFlow<StudyUiState>(StudyUiState.Empty)
	val state = mutableState.asStateFlow()

	fun openTrack(uri: Uri, hasPersistedAccess: Boolean) {
		mutableState.value = StudyUiState.Loading(uri.lastPathSegment ?: "Audio")
		viewModelScope.launch {
			val inspection = withContext(Dispatchers.IO) {
				inspector.inspect(uri, hasPersistedAccess)
			}
			mutableState.value = when (inspection) {
				is TrackInspection.Ready -> StudyUiState.Ready(inspection)
				is TrackInspection.Failed -> StudyUiState.Error(inspection.reason)
			}
		}
	}
}
