package com.p33nah.loopo.platform.audio

import com.p33nah.loopo.ui.StudyUiState
import com.p33nah.loopo.ui.voice.VoskWakeDetector
import com.p33nah.loopo.ui.voice.VoiceCommand
import com.p33nah.loopo.ui.voice.VoiceCommandCoordinator
import com.p33nah.loopo.ui.voice.VoiceStatus
import com.p33nah.loopo.ui.voice.WakeCommandCoordinator
import com.p33nah.loopo.ui.voice.hasMicrophonePermission
import com.p33nah.loopo.ui.voice.hasOnDeviceRecognizer
import com.p33nah.loopo.ui.voice.transcribeOnDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class PlaybackVoiceController(private val service: PlaybackService) : PlaybackVoiceSession {
	private val mutableStatus = MutableStateFlow<String?>(null)
	private val cancellationRevision = MutableStateFlow(0L)
	private var job: Job? = null
	private var sessionToken = 0L
	@Volatile private var armed = false

	override val status: StateFlow<String?> = mutableStatus
	override val isArmed: Boolean get() = armed

	override fun arm() {
		if (armed) return
		val track = (service.voiceStudy.state.value as? StudyUiState.Ready)?.inspection?.track
		if (track == null || !hasMicrophonePermission(service) || !VoskWakeDetector.isModelAvailable(service) || !hasOnDeviceRecognizer(service)) {
			setStatus(when {
				!VoskWakeDetector.isModelAvailable(service) -> "Risveglio offline non disponibile"
				!hasOnDeviceRecognizer(service) -> "Voce offline in italiano non disponibile"
				else -> "Concedi il microfono per attivare i comandi vocali."
			})
			return
		}
		armed = true
		sessionToken += 1
		val token = sessionToken
		if (!service.promoteForVoice()) {
			armed = false
			setStatus("Ascolto vocale non disponibile")
			return
		}
		setStatus("In ascolto per Ehi Lupo…")
		startWatching(token)
	}

	override fun cancelTurn() {
		cancellationRevision.value += 1
	}

	override fun close() {
		armed = false
		sessionToken += 1
		job?.cancel()
		job = null
	}

	override fun setStatus(value: String?) { mutableStatus.value = value }

	private fun startWatching(token: Long) {
		job?.cancel()
		job = service.voiceScope().launch {
			val wakeDetector = VoskWakeDetector(service.applicationContext)
			val trackState = service.voiceStudy.state.map { state ->
				val ready = state as? StudyUiState.Ready
				ready?.inspection?.track?.uri to service.voiceSession.trackRevision
			}
			try {
			combine(trackState, cancellationRevision, ::Pair)
				.distinctUntilChanged()
				.collectLatest { (track, turnRevision) ->
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
						transcribe = { onListening ->
							if (current()) transcribeOnDevice(service, onListening) else com.p33nah.loopo.ui.voice.TranscriptResult.Cancelled
						},
						dispatch = { command ->
							if (!current()) return@VoiceCommandCoordinator false
						when (command) {
							VoiceCommand.Resume -> service.voiceSession.requestVoicePlay()
							VoiceCommand.StayPaused -> Unit
							is VoiceCommand.SetRate -> service.voiceSession.setVoiceRate(command.rate)
						}
						true
						},
					)
					val coordinator = WakeCommandCoordinator(
						awaitWake = wakeDetector::awaitWake,
						runCommand = commands::run,
					)
					coordinator.listen(::current) { status -> setStatus(status.label()) }
					if (current()) {
						this@launch.cancel(CancellationException("Voice session reached a terminal recognition state"))
					}
				}
			} finally {
				wakeDetector.close()
				if (sessionToken == token && armed) {
					armed = false
					service.voiceBecameIdle()
				}
			}
		}
	}

	private fun VoiceStatus.label(): String = when (this) {
		VoiceStatus.PermissionDenied -> "Consenti il microfono nelle impostazioni."
		VoiceStatus.Pausing -> "Metto in pausa…"
		VoiceStatus.Listening -> "Ti ascolto…"
		VoiceStatus.WakeListening -> "In ascolto per Ehi Lupo…"
		VoiceStatus.WakeFailed -> "Ascolto di Ehi Lupo non riuscito"
		VoiceStatus.WakeUnavailable -> "Risveglio offline non disponibile"
		VoiceStatus.Paused -> "In pausa"
		VoiceStatus.CommandSent -> "Comando inviato"
		VoiceStatus.PauseFailed -> "Pausa audio non riuscita"
		VoiceStatus.TimedOut -> "Nessun comando rilevato"
		VoiceStatus.Unavailable -> "Voce offline in italiano non disponibile"
		VoiceStatus.Unrecognized -> "Comando non riconosciuto"
		VoiceStatus.Failed -> "Riconoscimento non riuscito"
		VoiceStatus.Cancelled -> "Comando annullato"
	}
}
