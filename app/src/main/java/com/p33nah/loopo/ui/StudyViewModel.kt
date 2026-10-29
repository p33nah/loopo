package com.p33nah.loopo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.p33nah.loopo.data.audio.AndroidTrackInspector
import com.p33nah.loopo.data.audio.AndroidPcmDecoderFactory
import com.p33nah.loopo.data.audio.AndroidPlaybackController
import com.p33nah.loopo.data.audio.TrackInspection
import com.p33nah.loopo.data.waveform.AndroidWaveformRepository
import com.p33nah.loopo.domain.audio.PcmDecodeFailure
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmReadResult
import com.p33nah.loopo.domain.audio.PlaybackError
import com.p33nah.loopo.domain.waveform.WaveformState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudyViewModel(application: Application) : AndroidViewModel(application) {
	private val inspector = AndroidTrackInspector(application)
	private val decoderFactory = AndroidPcmDecoderFactory(application)
	private val playbackController = AndroidPlaybackController(application)
	private val waveformRepository = AndroidWaveformRepository(application)
	private val mutableState = MutableStateFlow<StudyUiState>(StudyUiState.Empty)
	private val mutableWaveformState = MutableStateFlow<WaveformState>(WaveformState.Empty)
	private var waveformJob: Job? = null
	val state = mutableState.asStateFlow()
	val playbackState = playbackController.state
	val waveformState = mutableWaveformState.asStateFlow()

	fun openTrack(uri: Uri, hasPersistedAccess: Boolean) {
		waveformJob?.cancel()
		mutableWaveformState.value = WaveformState.Empty
		mutableState.value = StudyUiState.Loading(uri.lastPathSegment ?: "Audio")
		viewModelScope.launch {
			playbackController.unload()
			val inspection = withContext(Dispatchers.IO) {
				when (val inspected = inspector.inspect(uri, hasPersistedAccess)) {
					is TrackInspection.Failed -> inspected
					is TrackInspection.Ready -> probeDecoder(inspected)
				}
			}
			mutableState.value = when (inspection) {
				is TrackInspection.Ready -> {
					when (playbackController.load(inspection.track)) {
						null -> {
							startWaveformScan(inspection.track)
							StudyUiState.Ready(inspection)
						}
						PlaybackError.OUTPUT_UNAVAILABLE -> StudyUiState.Error(TrackInspection.Reason.AUDIO_OUTPUT_UNAVAILABLE)
						PlaybackError.AUDIO_FOCUS_DENIED,
						PlaybackError.DECODER_READ_FAILED -> StudyUiState.Error(TrackInspection.Reason.READ_FAILED)
					}
				}
				is TrackInspection.Failed -> StudyUiState.Error(inspection.reason)
			}
		}
	}

	private fun startWaveformScan(track: com.p33nah.loopo.domain.model.TrackInfo) {
		waveformJob = viewModelScope.launch {
			waveformRepository.observe(track)
				.flowOn(Dispatchers.IO)
				.collect { state ->
					mutableWaveformState.value = state
				}
		}
	}

	fun togglePlayback() {
		viewModelScope.launch { playbackController.togglePlayback() }
	}

	fun seekToFrame(frame: Long) {
		viewModelScope.launch { playbackController.seekToFrame(frame) }
	}

	fun pauseForLifecycle() {
		viewModelScope.launch { playbackController.pauseForLifecycle() }
	}

	override fun onCleared() {
		waveformJob?.cancel()
		playbackController.release()
		super.onCleared()
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
