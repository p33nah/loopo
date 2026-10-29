package com.p33nah.loopo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.p33nah.loopo.data.audio.AndroidPcmDecoderFactory
import com.p33nah.loopo.data.audio.AndroidPlaybackController
import com.p33nah.loopo.data.audio.AndroidTrackInspector
import com.p33nah.loopo.data.audio.TrackInspection
import com.p33nah.loopo.data.session.AndroidSessionRepository
import com.p33nah.loopo.data.waveform.AndroidWaveformRepository
import com.p33nah.loopo.domain.audio.PlaybackError
import com.p33nah.loopo.domain.audio.PlaybackRestoreSettings
import com.p33nah.loopo.domain.audio.PcmDecodeFailure
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmReadResult
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.SessionLoadOutcome
import com.p33nah.loopo.domain.model.SessionRepository
import com.p33nah.loopo.domain.model.SessionSaveOutcome
import com.p33nah.loopo.domain.model.SessionSource
import com.p33nah.loopo.domain.model.SessionState
import com.p33nah.loopo.domain.model.SourceMatch
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.waveform.WaveformState
import com.p33nah.loopo.domain.waveform.WaveformViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class StudyViewModel(application: Application) : AndroidViewModel(application) {
	private val inspector = AndroidTrackInspector(application)
	private val decoderFactory = AndroidPcmDecoderFactory(application)
	private val playbackController = AndroidPlaybackController(application)
	private val waveformRepository = AndroidWaveformRepository(application)
	private val sessionRepository: SessionRepository = AndroidSessionRepository(application)
	private val mutableState = MutableStateFlow<StudyUiState>(StudyUiState.Empty)
	private val mutableWaveformState = MutableStateFlow<WaveformState>(WaveformState.Empty)
	private val mutableViewport = MutableStateFlow<WaveformViewport?>(null)
	private var waveformJob: Job? = null
	private var openJob: Job? = null
	private var saveJob: Job? = null
	private var openGeneration = 0L
	private var activeInspection: TrackInspection.Ready? = null
	private var pendingRestore: SessionState? = null
	private var sessionReady = false
	private val saveMutex = Mutex()

	val state = mutableState.asStateFlow()
	val playbackState = playbackController.state
	val waveformState = mutableWaveformState.asStateFlow()
	val viewport = mutableViewport.asStateFlow()

	init {
		restoreLatestSession()
		viewModelScope.launch {
			while (true) {
				delay(CHECKPOINT_INTERVAL_MS)
				if (playbackController.state.value.isPlaying) saveSession()
			}
		}
	}

	fun openTrack(uri: Uri, hasPersistedAccess: Boolean) {
		val generation = ++openGeneration
		openJob?.cancel()
		val previousReady = activeInspection
		mutableState.value = StudyUiState.Loading(uri.lastPathSegment ?: "Audio")
		openJob = viewModelScope.launch {
			playbackController.pauseForLifecycle()
			val inspection = inspect(uri, hasPersistedAccess)
			if (generation != openGeneration) return@launch
			if (inspection !is TrackInspection.Ready) {
				if (previousReady != null) {
					mutableState.value = StudyUiState.Ready(previousReady, "Il file selezionato non è stato aperto.")
				} else {
					mutableState.value = StudyUiState.Error((inspection as TrackInspection.Failed).reason)
				}
				return@launch
			}
			val matchingSession = pendingRestore?.takeIf { it.source.uri == uri.toString() }
			val match = matchingSession?.source?.matches(inspection.track)
			openInspectedTrack(generation, inspection, matchingSession, match)
		}
	}

	fun updateViewport(viewport: WaveformViewport) {
		val track = activeInspection?.track ?: return
		if (viewport.durationFrames != track.durationFrames) return
		mutableViewport.value = viewport
		requestSaveSoon()
	}

	fun togglePlayback() {
		viewModelScope.launch {
			playbackController.togglePlayback()
			requestSaveSoon()
		}
	}

	fun seekToFrame(frame: Long) {
		viewModelScope.launch {
			playbackController.seekToFrame(frame)
			requestSaveSoon()
		}
	}

	fun setLoopRegion(region: LoopRegion) {
		viewModelScope.launch {
			if (playbackController.setLoopRegion(region)) requestSaveSoon()
		}
	}

	fun setPlaybackRate(rate: PlaybackRate) {
		viewModelScope.launch {
			playbackController.setPlaybackRate(rate)
			requestSaveSoon()
		}
	}

	fun pauseForLifecycle() {
		viewModelScope.launch {
			playbackController.pauseForLifecycle()
			saveSession()
		}
	}

	override fun onCleared() {
		waveformJob?.cancel()
		openJob?.cancel()
		playbackController.release()
		super.onCleared()
	}

	private fun restoreLatestSession() {
		viewModelScope.launch {
			when (val outcome = sessionRepository.load()) {
				SessionLoadOutcome.Empty -> {
					sessionReady = true
				}
				SessionLoadOutcome.StorageFailure -> {
					sessionReady = true
					mutableState.value = StudyUiState.RecoveryRequired(
						"Studio precedente", "Non riesco a leggere la sessione salvata. Puoi aprire un brano per iniziare.",
					)
				}
				SessionLoadOutcome.Corrupt -> {
					sessionReady = true
					mutableState.value = StudyUiState.RecoveryRequired(
						"Studio precedente", "La sessione salvata non è valida. Apri un brano per iniziare una nuova sessione.",
					)
				}
				is SessionLoadOutcome.Found -> {
					pendingRestore = outcome.session
					val uri = Uri.parse(outcome.session.source.uri)
					val hasGrant = getApplication<Application>().contentResolver.persistedUriPermissions
						.any { it.uri == uri && it.isReadPermission }
					if (!hasGrant) {
						sessionReady = true
						mutableState.value = StudyUiState.RecoveryRequired(
							outcome.session.source.displayName,
							"L’accesso al file è scaduto. Selezionalo di nuovo per riprendere lo studio.",
						)
					} else {
						val generation = ++openGeneration
						mutableState.value = StudyUiState.Loading(outcome.session.source.displayName)
						val inspection = inspect(uri, hasPersistedAccess = true)
						if (generation != openGeneration) return@launch
						if (inspection !is TrackInspection.Ready) {
							sessionReady = true
							mutableState.value = StudyUiState.RecoveryRequired(
								outcome.session.source.displayName,
								if (inspection is TrackInspection.Failed && inspection.reason == TrackInspection.Reason.ACCESS_DENIED) {
									"L’accesso al file è scaduto. Selezionalo di nuovo per riprendere lo studio."
								} else "Il file salvato non è disponibile. Selezionalo di nuovo o aprine un altro.",
							)
						} else {
							val match = outcome.session.source.matches(inspection.track)
							if (match == SourceMatch.Same) {
								openInspectedTrack(generation, inspection, outcome.session, match)
							} else {
								sessionReady = true
								mutableState.value = StudyUiState.RecoveryRequired(
									outcome.session.source.displayName,
									if (match == SourceMatch.Unverified) "Non posso verificare che il file sia lo stesso. Selezionalo di nuovo per confermare il ripristino."
									else "Il file è cambiato. Selezionalo di nuovo oppure aprine uno diverso per iniziare da capo.",
								)
							}
						}
					}
				}
			}
		}
	}

	private suspend fun inspect(uri: Uri, hasPersistedAccess: Boolean): TrackInspection = withContext(Dispatchers.IO) {
		when (val inspected = inspector.inspect(uri, hasPersistedAccess)) {
			is TrackInspection.Failed -> inspected
			is TrackInspection.Ready -> probeDecoder(inspected)
		}
	}

	private suspend fun openInspectedTrack(
		generation: Long,
		inspection: TrackInspection.Ready,
		restore: SessionState?,
		match: SourceMatch?,
	) {
		if (generation != openGeneration) return
		val loadError = playbackController.load(inspection.track)
		if (generation != openGeneration) return
		if (loadError != null) {
			mutableState.value = when (loadError) {
				PlaybackError.OUTPUT_UNAVAILABLE -> StudyUiState.Error(TrackInspection.Reason.AUDIO_OUTPUT_UNAVAILABLE)
				PlaybackError.AUDIO_FOCUS_DENIED, PlaybackError.DECODER_READ_FAILED -> StudyUiState.Error(TrackInspection.Reason.READ_FAILED)
			}
			return
		}
		activeInspection = inspection
		var warning: String? = null
		if (restore != null && (match == SourceMatch.Same || match == SourceMatch.Unverified)) {
			val restored = playbackController.restorePaused(
				PlaybackRestoreSettings(restore.positionFrame, restore.playbackRate, restore.loop),
			)
			if (restored) {
				mutableViewport.value = restore.viewport
				pendingRestore = null
			} else {
				warning = "Brano aperto, ma non sono riuscito a ripristinare tutti i controlli."
				mutableViewport.value = WaveformViewport.full(inspection.track.durationFrames)
			}
		} else {
			pendingRestore = null
			mutableViewport.value = WaveformViewport.full(inspection.track.durationFrames)
		}
		waveformJob?.cancel()
		mutableWaveformState.value = WaveformState.Empty
		startWaveformScan(inspection.track)
		sessionReady = true
		mutableState.value = StudyUiState.Ready(inspection, warning)
		requestSaveSoon()
	}

	private fun startWaveformScan(track: TrackInfo) {
		waveformJob = viewModelScope.launch {
			waveformRepository.observe(track).flowOn(Dispatchers.IO).collect { state ->
				mutableWaveformState.value = state
			}
		}
	}

	private fun requestSaveSoon() {
		saveJob?.cancel()
		saveJob = viewModelScope.launch {
			delay(SAVE_DEBOUNCE_MS)
			saveSession()
		}
	}

	private suspend fun saveSession() = saveMutex.withLock {
		if (!sessionReady || playbackController.state.value.isLoopCommandPending) return@withLock
		val inspection = activeInspection ?: return@withLock
		val snapshot = playbackController.state.value
		val viewport = mutableViewport.value ?: WaveformViewport.full(inspection.track.durationFrames)
		val loop = snapshot.loopRegion ?: LoopRegion(0, inspection.track.durationFrames, false, DEFAULT_CROSSFADE_MS)
		val session = runCatching {
			SessionState(
				source = SessionSource.from(inspection.track),
				positionFrame = snapshot.positionFrame.coerceIn(0, inspection.track.durationFrames),
				playbackRate = snapshot.playbackRate,
				loop = loop,
				viewport = viewport,
				updatedAtMillis = System.currentTimeMillis(),
			)
		}.getOrNull() ?: return@withLock
		when (sessionRepository.save(session)) {
			SessionSaveOutcome.Saved -> updateSaveWarning(null)
			SessionSaveOutcome.StorageFailure -> updateSaveWarning("Sessione non salvata: spazio o memoria non disponibili.")
		}
	}

	private fun updateSaveWarning(warning: String?) {
		val current = mutableState.value as? StudyUiState.Ready ?: return
		if (current.sessionWarning != warning) mutableState.value = current.copy(sessionWarning = warning)
	}

	private fun probeDecoder(inspection: TrackInspection.Ready): TrackInspection =
		when (val opened = decoderFactory.open(inspection.track.container, inspection.track.uri)) {
			is PcmDecoderResult.Failed -> TrackInspection.Failed(opened.reason.toInspectionReason())
			is PcmDecoderResult.Ready -> opened.decoder.use { decoder ->
				when (val readResult = decoder.readFrames(PROBE_FRAME_COUNT)) {
					is PcmReadResult.Frames -> inspection
					PcmReadResult.EndOfStream -> TrackInspection.Failed(TrackInspection.Reason.INVALID_METADATA)
					is PcmReadResult.Failed -> TrackInspection.Failed(readResult.reason.toInspectionReason())
				}
			}
		}

	private fun PcmDecodeFailure.toInspectionReason() = when (this) {
		PcmDecodeFailure.UNSUPPORTED_FORMAT, PcmDecodeFailure.UNSUPPORTED_PCM_ENCODING -> TrackInspection.Reason.UNSUPPORTED
		PcmDecodeFailure.UNSUPPORTED_CHANNEL_LAYOUT -> TrackInspection.Reason.UNSUPPORTED_CHANNEL_LAYOUT
		PcmDecodeFailure.INVALID_FILE -> TrackInspection.Reason.INVALID_METADATA
		PcmDecodeFailure.DECODER_UNAVAILABLE -> TrackInspection.Reason.DECODER_UNAVAILABLE
		PcmDecodeFailure.READ_FAILED -> TrackInspection.Reason.READ_FAILED
	}

	private companion object {
		const val PROBE_FRAME_COUNT = 4_096
		const val CHECKPOINT_INTERVAL_MS = 1_000L
		const val SAVE_DEBOUNCE_MS = 400L
		const val DEFAULT_CROSSFADE_MS = 20
	}
}
