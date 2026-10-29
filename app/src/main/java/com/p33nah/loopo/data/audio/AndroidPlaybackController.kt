package com.p33nah.loopo.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.p33nah.loopo.domain.audio.PcmDecodeFailure
import com.p33nah.loopo.domain.audio.PcmDecoder
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmReadResult
import com.p33nah.loopo.domain.audio.PcmSeekResult
import com.p33nah.loopo.domain.audio.PlaybackError
import com.p33nah.loopo.domain.audio.PlaybackSnapshot
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.platform.audio.NativePlaybackBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class AndroidPlaybackController(context: Context) {
	private val appContext = context.applicationContext
	private val decoderFactory = AndroidPcmDecoderFactory(appContext)
	private val audioManager = appContext.getSystemService(AudioManager::class.java)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val transitionMutex = Mutex()
	private val decoderMutex = Mutex()
	private val seekGeneration = AtomicLong(0)
	private val mutableState = MutableStateFlow(PlaybackSnapshot())
	val state = mutableState.asStateFlow()

	private var track: TrackInfo? = null
	private var decoder: PcmDecoder? = null
	private var engineHandle = 0L
	private var pumpJob: Job? = null
	private var monitorJob: Job? = null
	private var focusRequest: AudioFocusRequest? = null
	private var hasAudioFocus = false
	private var focusTemporarilyLost = false
	private var resumeAfterFocusGain = false

	private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
		when (change) {
			AudioManager.AUDIOFOCUS_LOSS -> scope.launch {
				transitionMutex.withLock {
					focusTemporarilyLost = false
					resumeAfterFocusGain = false
					pauseUnsafe(abandonFocus = true)
				}
			}
			AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
			AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> scope.launch {
				transitionMutex.withLock {
					focusTemporarilyLost = true
					resumeAfterFocusGain = mutableState.value.isPlaying
					pauseUnsafe(abandonFocus = false)
				}
			}
			AudioManager.AUDIOFOCUS_GAIN -> scope.launch {
				transitionMutex.withLock {
					focusTemporarilyLost = false
					if (resumeAfterFocusGain) {
						resumeAfterFocusGain = false
						resumeAfterFocusGainUnsafe()
					}
				}
			}
		}
	}

	suspend fun load(newTrack: TrackInfo): PlaybackError? = transitionMutex.withLock {
		shutdownTrack()
		val opened = withContext(Dispatchers.IO) {
			decoderFactory.open(newTrack.container, newTrack.uri)
		}
		val newDecoder = when (opened) {
			is PcmDecoderResult.Failed -> {
				mutableState.value = PlaybackSnapshot(error = PlaybackError.DECODER_READ_FAILED)
				return@withLock PlaybackError.DECODER_READ_FAILED
			}
			is PcmDecoderResult.Ready -> opened.decoder
		}
		if (newDecoder.format.sampleRate != newTrack.sampleRate || newDecoder.format.channelCount != newTrack.channelCount) {
			withContext(Dispatchers.IO) { newDecoder.close() }
			mutableState.value = PlaybackSnapshot(error = PlaybackError.DECODER_READ_FAILED)
			return@withLock PlaybackError.DECODER_READ_FAILED
		}

		val handle = withContext(Dispatchers.IO) {
			NativePlaybackBridge.create(
				newTrack.sampleRate,
				newTrack.channelCount,
				newTrack.sampleRate * RING_CAPACITY_SECONDS,
			)
		}
		if (handle == 0L) {
			withContext(Dispatchers.IO) { newDecoder.close() }
			mutableState.value = PlaybackSnapshot(error = PlaybackError.OUTPUT_UNAVAILABLE)
			return@withLock PlaybackError.OUTPUT_UNAVAILABLE
		}

		track = newTrack
		decoder = newDecoder
		engineHandle = handle
		mutableState.value = PlaybackSnapshot()
		pumpJob = scope.launch { pumpDecoder() }
		null
	}

	suspend fun togglePlayback() = transitionMutex.withLock {
		if (mutableState.value.isPlaying) {
			resumeAfterFocusGain = false
			pauseUnsafe(abandonFocus = true)
		} else {
			if (focusTemporarilyLost) {
				resumeAfterFocusGain = true
				return@withLock
			}
			resumeAfterFocusGain = false
			if (engineHandle == 0L) return@withLock
			if (mutableState.value.isEnded) {
				seekUnsafe(0, resumeAfterSeek = false)
				if (mutableState.value.isEnded) return@withLock
			}
			ensurePumpRunning()
			awaitPrebuffer(engineHandle, mutableState.value.positionFrame)
			if (!requestAudioFocus()) {
				mutableState.update { it.copy(error = PlaybackError.AUDIO_FOCUS_DENIED) }
				return@withLock
			}
			if (NativePlaybackBridge.play(engineHandle)) {
				mutableState.update { it.copy(isPlaying = true, error = null) }
				ensureMonitorRunning()
			} else {
				abandonAudioFocus()
				mutableState.update { it.copy(error = PlaybackError.OUTPUT_UNAVAILABLE) }
			}
		}
	}

	suspend fun seekToFrame(frame: Long) = transitionMutex.withLock {
		resumeAfterFocusGain = false
		seekUnsafe(frame.coerceIn(0, track?.durationFrames ?: 0), resumeAfterSeek = mutableState.value.isPlaying)
	}

	suspend fun unload() = transitionMutex.withLock { shutdownTrack() }

	suspend fun pauseForLifecycle() = transitionMutex.withLock {
		focusTemporarilyLost = false
		resumeAfterFocusGain = false
		pauseUnsafe(abandonFocus = true)
		pumpJob?.cancelAndJoin()
		pumpJob = null
	}

	fun release() {
		scope.launch {
			transitionMutex.withLock { shutdownTrack() }
		}
	}

	private suspend fun seekUnsafe(frame: Long, resumeAfterSeek: Boolean) {
		val handle = engineHandle
		val activeDecoder = decoder ?: return
		if (!withContext(Dispatchers.IO) { NativePlaybackBridge.pause(handle) }) {
			mutableState.update { it.copy(isPlaying = false, error = PlaybackError.OUTPUT_UNAVAILABLE) }
			abandonAudioFocus()
			return
		}
		mutableState.update { it.copy(isPlaying = false) }
		monitorJob?.cancelAndJoin()
		monitorJob = null
		val result = decoderMutex.withLock {
			withContext(Dispatchers.IO) {
				if (NativePlaybackBridge.resetAtFrame(handle, frame)) activeDecoder.seekToFrame(frame)
				else PcmSeekResult.Failed(PcmDecodeFailure.READ_FAILED)
			}
		}
		when (result) {
			is PcmSeekResult.Failed -> {
				abandonAudioFocus()
				mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
				return
			}
			is PcmSeekResult.Seeked -> {
				seekGeneration.incrementAndGet()
				mutableState.update {
					it.copy(positionFrame = result.frame, isEnded = false, error = null)
				}
			}
		}
		if (resumeAfterSeek) {
			ensurePumpRunning()
			awaitPrebuffer(handle, frame)
			if (!requestAudioFocus()) {
				mutableState.update { it.copy(error = PlaybackError.AUDIO_FOCUS_DENIED) }
			} else if (NativePlaybackBridge.play(handle)) {
				mutableState.update { it.copy(isPlaying = true) }
				ensureMonitorRunning()
			} else {
				abandonAudioFocus()
				mutableState.update { it.copy(error = PlaybackError.OUTPUT_UNAVAILABLE) }
			}
		}
	}

	private suspend fun pauseUnsafe(abandonFocus: Boolean) {
		val paused = engineHandle == 0L || withContext(Dispatchers.IO) { NativePlaybackBridge.pause(engineHandle) }
		val position = if (engineHandle != 0L) NativePlaybackBridge.positionFrame(engineHandle) else mutableState.value.positionFrame
		mutableState.update {
			it.copy(
				positionFrame = position,
				isPlaying = false,
				error = if (paused) it.error else PlaybackError.OUTPUT_UNAVAILABLE,
			)
		}
		monitorJob?.cancelAndJoin()
		monitorJob = null
		if (abandonFocus) abandonAudioFocus()
	}

	private suspend fun resumeAfterFocusGainUnsafe() {
		val handle = engineHandle
		if (handle == 0L || mutableState.value.isEnded) return
		ensurePumpRunning()
		awaitPrebuffer(handle, mutableState.value.positionFrame)
		if (NativePlaybackBridge.play(handle)) {
			mutableState.update { it.copy(isPlaying = true, error = null) }
			ensureMonitorRunning()
		} else {
			abandonAudioFocus()
			mutableState.update { it.copy(error = PlaybackError.OUTPUT_UNAVAILABLE) }
		}
	}

	private fun ensurePumpRunning() {
		if (pumpJob?.isActive != true && decoder != null) pumpJob = scope.launch { pumpDecoder() }
	}

	private fun ensureMonitorRunning() {
		if (monitorJob?.isActive != true && engineHandle != 0L) monitorJob = scope.launch { monitorPlayback() }
	}

	private suspend fun shutdownTrack() {
		pumpJob?.cancelAndJoin()
		pumpJob = null
		monitorJob?.cancelAndJoin()
		monitorJob = null
		abandonAudioFocus()
		decoderMutex.withLock {
			withContext(Dispatchers.IO) {
				if (engineHandle != 0L) {
					NativePlaybackBridge.destroy(engineHandle)
					engineHandle = 0L
				}
				decoder?.close()
				decoder = null
			}
		}
		track = null
		mutableState.value = PlaybackSnapshot()
	}

	private suspend fun pumpDecoder() {
		var reachedEnd = false
		var observedSeekGeneration = seekGeneration.get()
		while (true) {
			coroutineContext.ensureActive()
			val currentSeekGeneration = seekGeneration.get()
			if (currentSeekGeneration != observedSeekGeneration) {
				reachedEnd = false
				observedSeekGeneration = currentSeekGeneration
			}
			val handle = engineHandle
			val currentTrack = track ?: return
			val queued = NativePlaybackBridge.queuedFrames(handle)
			val targetQueue = (currentTrack.sampleRate / PREFILL_DIVISOR).coerceAtLeast(MIN_PREFILL_FRAMES)
			if (!reachedEnd && queued < targetQueue) {
				val activeDecoder = decoder ?: return
				val shouldStop = decoderMutex.withLock {
					val writable = NativePlaybackBridge.writeSpace(handle)
					val frameLimit = minOf(MAX_DECODE_BLOCK_FRAMES, writable, targetQueue - queued)
					if (frameLimit <= 0) {
						false
					} else {
						val result = try {
							activeDecoder.readFrames(frameLimit)
						} catch (_: RuntimeException) {
							PcmReadResult.Failed(PcmDecodeFailure.READ_FAILED)
						}
						when (result) {
							is PcmReadResult.Frames -> {
								val block = result.block
								if (block.format.sampleRate != currentTrack.sampleRate || block.format.channelCount != currentTrack.channelCount) {
									mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
									NativePlaybackBridge.setEndOfStream(handle)
									true
								} else {
									val written = NativePlaybackBridge.write(handle, block.samples, 0, block.frameCount, block.format.channelCount)
									if (written == block.frameCount) false else {
										mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
										NativePlaybackBridge.setEndOfStream(handle)
										true
									}
								}
							}
							PcmReadResult.EndOfStream -> {
								NativePlaybackBridge.setEndOfStream(handle)
								true
							}
							is PcmReadResult.Failed -> {
								mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
								NativePlaybackBridge.setEndOfStream(handle)
								true
							}
						}
					}
				}
				if (shouldStop) reachedEnd = true
			} else {
				delay(if (NativePlaybackBridge.isPlaying(handle)) PUMP_DELAY_MS else PAUSED_PUMP_DELAY_MS)
			}
		}
	}

	private suspend fun monitorPlayback() {
		while (true) {
			delay(MONITOR_INTERVAL_MS)
			transitionMutex.withLock {
				val handle = engineHandle
				val currentTrack = track ?: return
				if (handle == 0L) return
				val ended = NativePlaybackBridge.isEnded(handle)
				mutableState.update { current ->
					current.copy(
						positionFrame = if (ended) currentTrack.durationFrames else NativePlaybackBridge.positionFrame(handle),
						isPlaying = NativePlaybackBridge.isPlaying(handle),
						isEnded = ended,
						underrunCount = NativePlaybackBridge.underrunCount(handle),
						estimatedLatencyMillis = NativePlaybackBridge.estimatedLatencyMillis(handle).takeIf { it >= 0.0 },
					)
				}
				if (ended) {
					abandonAudioFocus()
					return
				}
			}
		}
	}

	private suspend fun awaitPrebuffer(handle: Long, startFrame: Long) {
		val currentTrack = track ?: return
		val remainingFrames = (currentTrack.durationFrames - startFrame).coerceAtLeast(0)
		val targetFrames = minOf(
			currentTrack.sampleRate / PREFILL_DIVISOR,
			remainingFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
		)
		if (targetFrames <= 0) return
		withTimeoutOrNull(PREFILL_TIMEOUT_MS) {
			while (NativePlaybackBridge.queuedFrames(handle) < targetFrames) delay(PUMP_DELAY_MS)
		}
	}

	private fun requestAudioFocus(): Boolean {
		if (hasAudioFocus) return true
		val attributes = AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_MEDIA)
			.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
			.build()
		val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
			.setAudioAttributes(attributes)
			.setWillPauseWhenDucked(true)
			.setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
			.build()
		focusRequest = request
		hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
		return hasAudioFocus
	}

	private fun abandonAudioFocus() {
		focusRequest?.let(audioManager::abandonAudioFocusRequest)
		focusRequest = null
		hasAudioFocus = false
	}

	private companion object {
		const val RING_CAPACITY_SECONDS = 2
		const val PREFILL_DIVISOR = 5
		const val MIN_PREFILL_FRAMES = 2_048
		const val MAX_DECODE_BLOCK_FRAMES = 2_048
		const val PUMP_DELAY_MS = 4L
		const val PAUSED_PUMP_DELAY_MS = 250L
		const val MONITOR_INTERVAL_MS = 40L
		const val PREFILL_TIMEOUT_MS = 750L
	}
}
