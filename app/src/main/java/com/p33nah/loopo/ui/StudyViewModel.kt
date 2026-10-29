package com.p33nah.loopo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.p33nah.loopo.data.audio.AndroidTrackInspector
import com.p33nah.loopo.data.audio.AndroidPcmDecoderFactory
import com.p33nah.loopo.data.audio.TrackInspection
import com.p33nah.loopo.domain.audio.PcmDecodeFailure
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudyViewModel(application: Application) : AndroidViewModel(application) {
	private val inspector = AndroidTrackInspector(application)
	private val decoderFactory = AndroidPcmDecoderFactory(application)
	private val mutableState = MutableStateFlow<StudyUiState>(StudyUiState.Empty)
	val state = mutableState.asStateFlow()

	fun openTrack(uri: Uri, hasPersistedAccess: Boolean) {
		mutableState.value = StudyUiState.Loading(uri.lastPathSegment ?: "Audio")
		viewModelScope.launch {
			val inspection = withContext(Dispatchers.IO) {
				when (val inspected = inspector.inspect(uri, hasPersistedAccess)) {
					is TrackInspection.Failed -> inspected
					is TrackInspection.Ready -> probeDecoder(inspected)
				}
			}
			mutableState.value = when (inspection) {
				is TrackInspection.Ready -> StudyUiState.Ready(inspection)
				is TrackInspection.Failed -> StudyUiState.Error(inspection.reason)
			}
		}
	}

	private fun probeDecoder(inspection: TrackInspection.Ready): TrackInspection =
		when (val opened = decoderFactory.open(inspection.track.container, inspection.track.uri)) {
			is PcmDecoderResult.Failed -> TrackInspection.Failed(opened.reason.toInspectionReason())
			is PcmDecoderResult.Ready -> opened.decoder.use { decoder ->
				val readResult = decoder.readFrames(PROBE_FRAME_COUNT)
				when (readResult) {
					is PcmReadResult.Frames -> inspection
					PcmReadResult.EndOfStream -> TrackInspection.Failed(TrackInspection.Reason.INVALID_METADATA)
					is PcmReadResult.Failed -> TrackInspection.Failed(readResult.reason.toInspectionReason())
				}
			}
		}

private fun PcmDecodeFailure.toInspectionReason() = when (this) {
		PcmDecodeFailure.UNSUPPORTED_FORMAT,
		PcmDecodeFailure.UNSUPPORTED_PCM_ENCODING -> TrackInspection.Reason.UNSUPPORTED
		PcmDecodeFailure.UNSUPPORTED_CHANNEL_LAYOUT -> TrackInspection.Reason.UNSUPPORTED_CHANNEL_LAYOUT
		PcmDecodeFailure.INVALID_FILE -> TrackInspection.Reason.INVALID_METADATA
		PcmDecodeFailure.DECODER_UNAVAILABLE -> TrackInspection.Reason.DECODER_UNAVAILABLE
		PcmDecodeFailure.READ_FAILED -> TrackInspection.Reason.READ_FAILED
	}

	private companion object {
		const val PROBE_FRAME_COUNT = 4_096
	}
}
