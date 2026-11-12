package com.p33nah.loopo.platform.audio

import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.ui.StudyUiState
import com.p33nah.loopo.ui.voice.TranscriptResult
import com.p33nah.loopo.ui.voice.VoiceAction
import com.p33nah.loopo.ui.voice.VoiceCommandCoordinator
import com.p33nah.loopo.ui.voice.VoiceStatus
import com.p33nah.loopo.ui.voice.WakeCommandCoordinator
import com.p33nah.loopo.ui.voice.WakeDetection
import com.p33nah.loopo.ui.voice.VoskWakeDetector
import com.p33nah.loopo.ui.voice.hasMicrophonePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PlaybackVoiceController(private val service: PlaybackService) : PlaybackVoiceSession {
	private val mutableStatus = MutableStateFlow(VoiceUiState.Idle)
	private val cancellationRevision = MutableStateFlow(0L)
	private var job: Job? = null
	private var feedbackExpiryJob: Job? = null
	private var sessionToken = 0L
	@Volatile private var armed = false

	override val status: StateFlow<VoiceUiState> = mutableStatus
	override val isArmed: Boolean get() = armed

	override fun arm() {
		if (armed) return
		val track = (service.voiceStudy.state.value as? StudyUiState.Ready)?.inspection?.track
		if (track == null || !hasMicrophonePermission(service)) {
			setFeedback(VoicePhase.Feedback, "Concedi il microfono per attivare i comandi vocali.", VoiceTone.Error)
			return
		}
		armed = true
		sessionToken += 1
		val token = sessionToken
		if (!service.promoteForVoice()) {
			armed = false
			setFeedback(VoicePhase.Feedback, "Ascolto vocale non disponibile", VoiceTone.Error)
			return
		}
		mutableStatus.value = VoiceUiState(
			VoicePhase.DownloadingModel,
			"Preparo il riconoscimento vocale…",
			resultMessage = "Preparo il riconoscimento vocale…",
			enabled = true,
		)
		startWatching(token)
	}

	override fun cancelTurn() {
		cancellationRevision.value += 1
		clearTransientResult()
	}

	override fun close() {
		armed = false
		sessionToken += 1
		job?.cancel()
		job = null
		feedbackExpiryJob?.cancel()
		feedbackExpiryJob = null
		mutableStatus.value = VoiceUiState.Idle
	}

	override fun setStatus(value: String?) {
		if (value == null) {
			feedbackExpiryJob?.cancel()
			feedbackExpiryJob = null
			mutableStatus.value = VoiceUiState.Idle
		} else {
			setFeedback(VoicePhase.Feedback, value, VoiceTone.Error)
		}
	}

	private fun startWatching(token: Long) {
		job?.cancel()
		job = service.voiceScope().launch {
			val wakeDetector = VoskWakeDetector(
				service.applicationContext,
				onModelProgress = { received, total ->
					val detail = if (total > 0) "Scarico il modello vocale (circa 50 MB): ${(received * 100 / total).toInt()}%" else "Scarico il modello vocale (circa 50 MB)…"
					mutableStatus.value = VoiceUiState(
						VoicePhase.DownloadingModel,
						detail,
						resultMessage = detail,
						enabled = true,
					)
				},
				onModelError = { message -> setFeedback(VoicePhase.Feedback, message, VoiceTone.Error) },
			)
			val trackState = service.voiceStudy.state.map { state ->
				val ready = state as? StudyUiState.Ready
				ready?.inspection?.track?.uri to service.voiceSession.trackRevision
			}
			try {
				combine(trackState, cancellationRevision, ::Pair).distinctUntilChanged().collectLatest { (track, turnRevision) ->
					if (!armed || track.first == null || !hasMicrophonePermission(service)) {
						armed = false
						setStatus(null)
						service.voiceBecameIdle()
						this@launch.cancel()
						return@collectLatest
					}
					val uri = track.first
					val generation = track.second
					fun current(): Boolean = armed && hasMicrophonePermission(service) &&
						cancellationRevision.value == turnRevision &&
						service.voiceSession.trackRevision == generation &&
						(service.voiceStudy.state.value as? StudyUiState.Ready)?.inspection?.track?.uri == uri

					val commands = VoiceCommandCoordinator(
						pause = { service.voiceSession.pauseForVoice(::current) },
						transcribe = { onListening -> if (current()) wakeDetector.transcribe(onListening) else TranscriptResult.Cancelled },
						dispatch = { action -> execute(action, ::current) },
						isCurrent = ::current,
						onTranscript = ::showTranscript,
					)
					val coordinator = WakeCommandCoordinator(
						awaitWake = wakeDetector::awaitWake,
						runCommand = commands::run,
					)
					coordinator.listen(::current) { status -> showStatus(status) }
					if (current()) this@launch.cancel(CancellationException("Voice session reached a terminal recognition state"))
				}
			} finally {
				withContext(NonCancellable + Dispatchers.IO) { wakeDetector.close() }
				if (sessionToken == token && armed) {
					armed = false
					val lastStatus = mutableStatus.value
					mutableStatus.value = if (lastStatus.phase == VoicePhase.Feedback) lastStatus.copy(enabled = false)
					else VoiceUiState.Idle
					service.voiceBecameIdle()
				}
			}
		}
	}

	private suspend fun execute(action: VoiceAction, isCurrent: () -> Boolean): Boolean {
		if (!isCurrent()) return false
		return when (action) {
			VoiceAction.Resume -> service.voiceSession.requestVoicePlay(isCurrent)
			VoiceAction.StayPaused -> true
			is VoiceAction.SetRate -> service.voiceSession.setVoiceRate(action.rate, isCurrent)
			is VoiceAction.AdjustRate -> {
				val current = service.voiceStudy.playbackState.value.playbackRate.percent
				val target = action.delta.resolve(current)
				if (target == null) {
					setFeedback(VoicePhase.Feedback, "La velocità richiesta esce dall’intervallo 25–200%.", VoiceTone.Error)
					false
				} else service.voiceSession.setVoiceRate(target, isCurrent)
			}
		}
	}

	private fun showTranscript(transcript: String) {
		mutableStatus.value = mutableStatus.value.copy(recognizedTranscript = transcript.take(160))
	}

	private fun showStatus(status: VoiceStatus) {
		val previous = mutableStatus.value
		val message = when (status) {
			VoiceStatus.PermissionDenied -> "Consenti il microfono nelle impostazioni."
			VoiceStatus.Pausing -> "Metto in pausa…"
			VoiceStatus.Listening -> "Ti ascolto…"
			VoiceStatus.Applying -> "Applico il comando…"
			VoiceStatus.WakeListening -> "In ascolto per Ehi Lupo…"
			VoiceStatus.WakeFailed -> "Ascolto non riuscito. Tocca il lupo per riprovare."
			VoiceStatus.WakeUnavailable -> "Riconoscimento vocale offline non disponibile. Riprova."
			VoiceStatus.Paused -> "Comando pausa applicato"
			VoiceStatus.CommandSent -> "Comando applicato"
			VoiceStatus.PauseFailed -> "Pausa audio non riuscita"
			VoiceStatus.TimedOut -> "Nessun comando rilevato. Riprova dopo Ehi Lupo."
			VoiceStatus.Unavailable -> "Riconoscimento vocale offline non disponibile."
			VoiceStatus.Unrecognized -> "Comando non riconosciuto."
			VoiceStatus.Failed -> "Il comando non è stato applicato. La riproduzione resta in pausa."
			VoiceStatus.Cancelled -> "Comando annullato."
		}
		val transcript = if (status == VoiceStatus.Pausing) null else previous.recognizedTranscript
		val isError = status in setOf(VoiceStatus.PermissionDenied, VoiceStatus.WakeFailed, VoiceStatus.WakeUnavailable,
			VoiceStatus.PauseFailed, VoiceStatus.TimedOut, VoiceStatus.Unavailable, VoiceStatus.Unrecognized,
			VoiceStatus.Failed, VoiceStatus.Cancelled)
		val resultMessage = when (status) {
			VoiceStatus.CommandSent, VoiceStatus.Paused -> transcript?.let { "Ho sentito: «$it». $message." } ?: message
			VoiceStatus.Unrecognized, VoiceStatus.Failed, VoiceStatus.TimedOut -> transcript?.let { "$message Ho sentito: «$it»." } ?: message
			VoiceStatus.WakeFailed, VoiceStatus.WakeUnavailable, VoiceStatus.PauseFailed,
			VoiceStatus.Unavailable, VoiceStatus.PermissionDenied, VoiceStatus.Cancelled -> message
			else -> null
		}
		val phase = when (status) {
			VoiceStatus.WakeListening -> VoicePhase.WakeListening
			VoiceStatus.Pausing -> VoicePhase.Pausing
			VoiceStatus.Listening -> VoicePhase.Listening
			VoiceStatus.Applying -> VoicePhase.Applying
			VoiceStatus.Paused, VoiceStatus.CommandSent, VoiceStatus.PauseFailed, VoiceStatus.TimedOut,
			VoiceStatus.WakeUnavailable, VoiceStatus.WakeFailed, VoiceStatus.Unavailable,
			VoiceStatus.PermissionDenied, VoiceStatus.Unrecognized, VoiceStatus.Failed, VoiceStatus.Cancelled -> VoicePhase.Feedback
		}
		val tone = if (isError) VoiceTone.Error else if (status == VoiceStatus.CommandSent || status == VoiceStatus.Paused) VoiceTone.Success else VoiceTone.Neutral
		mutableStatus.value = previous.withPhase(
			phase = phase,
			message = message,
			tone = if (phase == VoicePhase.Feedback) tone else VoiceTone.Neutral,
		).copy(
			enabled = armed,
			recognizedTranscript = transcript,
			resultMessage = if (status == VoiceStatus.WakeListening && previous.phase != VoicePhase.DownloadingModel) previous.resultMessage else resultMessage,
			resultTone = if (status == VoiceStatus.WakeListening) previous.resultTone else tone,
		)
		when {
			resultMessage != null -> showTransientResult(resultMessage)
			status == VoiceStatus.WakeListening -> Unit
			else -> clearTransientResult()
		}
	}

	private fun setFeedback(phase: VoicePhase, message: String, tone: VoiceTone = VoiceTone.Neutral, transcript: String? = null) {
		mutableStatus.value = VoiceUiState(
			phase = phase,
			message = message,
			tone = tone,
			recognizedTranscript = transcript,
			resultMessage = message,
			resultTone = tone,
			enabled = armed,
		)
		showTransientResult(message)
	}

	private fun showTransientResult(message: String) {
		feedbackExpiryJob?.cancel()
		feedbackExpiryJob = service.voiceScope().launch {
			delay(RESULT_DURATION_MS)
			val current = mutableStatus.value
			if (current.resultMessage == message) mutableStatus.value = current.clearResult()
		}
	}

	private fun clearTransientResult() {
		feedbackExpiryJob?.cancel()
		feedbackExpiryJob = null
		mutableStatus.value = mutableStatus.value.clearResult()
	}

	private companion object { const val RESULT_DURATION_MS = 4_000L }
}
