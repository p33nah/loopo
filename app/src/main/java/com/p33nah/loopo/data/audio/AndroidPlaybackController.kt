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
import com.p33nah.loopo.domain.audio.PlaybackRestoreSettings
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.PlaybackRate
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
	private var decoderPositionFrame = 0L
	private var playbackRate = PlaybackRate.NORMAL
	private var stretcherNeedsPrime = true
	private var stretchedOutputRemainder = 0.0
	private var stretchedOutputSourceFrame = 0L
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
		decoderPositionFrame = 0
		playbackRate = PlaybackRate.NORMAL
		stretcherNeedsPrime = true
		stretchedOutputRemainder = 0.0
		stretchedOutputSourceFrame = 0L
		engineHandle = handle
		val loopRegion = LoopRegion(0, newTrack.durationFrames, enabled = false, crossfadeMs = DEFAULT_CROSSFADE_MS)
		mutableState.value = PlaybackSnapshot(
			loopRegion = loopRegion,
			effectiveCrossfadeFrames = loopRegion.effectiveCrossfadeFrames(newTrack.sampleRate),
			playbackRate = playbackRate,
		)
		pumpJob = scope.launch { pumpDecoder() }
		null
	}

	suspend fun setPlaybackRate(rate: PlaybackRate) = transitionMutex.withLock {
		if (rate == playbackRate || track == null) return@withLock
		val wasPlaying = mutableState.value.isPlaying
		val position = if (engineHandle != 0L) NativePlaybackBridge.positionFrame(engineHandle) else mutableState.value.positionFrame
		playbackRate = rate
		mutableState.update { it.copy(playbackRate = rate) }
		seekUnsafe(position, resumeAfterSeek = wasPlaying)
	}

	suspend fun restorePaused(settings: PlaybackRestoreSettings): Boolean {
		pauseForLifecycle()
		val currentTrack = track ?: return false
		val loop = settings.loop.copy(
			aFrame = settings.loop.aFrame.coerceIn(0, currentTrack.durationFrames - 1),
			bFrame = settings.loop.bFrame.coerceIn(1, currentTrack.durationFrames),
		)
		if (loop.aFrame >= loop.bFrame || !setLoopRegion(loop)) return false
		setPlaybackRate(settings.playbackRate)
		val maxPosition = currentTrack.durationFrames
		val position = settings.positionFrame.coerceIn(0, maxPosition)
		seekToFrame(position)
		return mutableState.value.error == null && !mutableState.value.isPlaying
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
		val currentTrack = track
		val loop = mutableState.value.loopRegion?.takeIf { it.enabled }
		val target = if (currentTrack == null) 0L else {
			val bounded = frame.coerceIn(0, currentTrack.durationFrames)
			if (loop == null) bounded else bounded.coerceIn(loop.aFrame, (loop.bFrame - 1).coerceAtLeast(loop.aFrame))
		}
		seekUnsafe(target, resumeAfterSeek = mutableState.value.isPlaying)
	}

	suspend fun setLoopRegion(region: LoopRegion): Boolean = transitionMutex.withLock {
		val currentTrack = track ?: return@withLock false
		if (region.aFrame < 0 || region.aFrame >= region.bFrame || region.bFrame > currentTrack.durationFrames) return@withLock false
		if (region == mutableState.value.loopRegion) return@withLock true
		val handle = engineHandle
		val activeDecoder = decoder ?: return@withLock false
		val wasPlaying = mutableState.value.isPlaying
		var previousPosition = mutableState.value.positionFrame
		var pumpStopped = false
		var nativeStateReset = false
		mutableState.update { it.copy(isLoopCommandPending = true) }
		var applied = false
		try {
			if (!withContext(Dispatchers.IO) { NativePlaybackBridge.pause(handle) }) return@withLock false
			previousPosition = NativePlaybackBridge.positionFrame(handle).coerceIn(0, currentTrack.durationFrames)
			val nextPosition = if (region.enabled && previousPosition !in region.aFrame until region.bFrame) region.aFrame else previousPosition
			monitorJob?.cancelAndJoin()
			monitorJob = null
			pumpJob?.cancelAndJoin()
			pumpJob = null
			pumpStopped = true
			val seekResult = decoderMutex.withLock {
				withContext(Dispatchers.IO) {
					if (!NativePlaybackBridge.resetAtFrame(handle, nextPosition)) return@withContext null
					nativeStateReset = true
					stretcherNeedsPrime = true
					stretchedOutputRemainder = 0.0
					stretchedOutputSourceFrame = nextPosition
					activeDecoder.seekToFrame(nextPosition)
				}
			}
			val seeked = seekResult as? PcmSeekResult.Seeked ?: return@withLock false
			decoderPositionFrame = seeked.frame
			stretcherNeedsPrime = true
			stretchedOutputRemainder = 0.0
			stretchedOutputSourceFrame = nextPosition
			seekGeneration.incrementAndGet()
			mutableState.update {
				it.copy(
					positionFrame = seeked.frame,
					isEnded = false,
					loopRegion = region,
					effectiveCrossfadeFrames = region.effectiveCrossfadeFrames(currentTrack.sampleRate),
					error = null,
				)
			}
			applied = true
			ensurePumpRunning()
			if (wasPlaying) {
				awaitPrebuffer(handle, seeked.frame)
				if (!NativePlaybackBridge.play(handle)) {
					mutableState.update { it.copy(isPlaying = false, error = PlaybackError.OUTPUT_UNAVAILABLE) }
					return@withLock false
				}
				mutableState.update { it.copy(isPlaying = true) }
				ensureMonitorRunning()
			}
			true
		} finally {
			mutableState.update { it.copy(isLoopCommandPending = false) }
			if (!applied && pumpStopped) {
				var recovered = !nativeStateReset
				if (nativeStateReset) {
					val restored = decoderMutex.withLock {
						withContext(Dispatchers.IO) {
							if (!NativePlaybackBridge.resetAtFrame(handle, previousPosition)) return@withContext null
							activeDecoder.seekToFrame(previousPosition) as? PcmSeekResult.Seeked
						}
					}
					if (restored != null) {
						decoderPositionFrame = restored.frame
						seekGeneration.incrementAndGet()
						mutableState.update { it.copy(positionFrame = restored.frame, isEnded = false) }
						recovered = true
					} else {
						mutableState.update { it.copy(isPlaying = false, error = PlaybackError.DECODER_READ_FAILED) }
						abandonAudioFocus()
					}
				}
				if (recovered) {
					ensurePumpRunning()
					if (wasPlaying && !mutableState.value.isPlaying) {
						awaitPrebuffer(handle, mutableState.value.positionFrame)
						if (NativePlaybackBridge.play(handle)) {
							mutableState.update { it.copy(isPlaying = true) }
							ensureMonitorRunning()
						}
					}
				}
			}
		}
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
				decoderPositionFrame = result.frame
				stretcherNeedsPrime = true
				stretchedOutputRemainder = 0.0
				stretchedOutputSourceFrame = result.frame
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
		decoderPositionFrame = 0
		playbackRate = PlaybackRate.NORMAL
		stretcherNeedsPrime = true
		stretchedOutputRemainder = 0.0
		stretchedOutputSourceFrame = 0L
		mutableState.value = PlaybackSnapshot()
	}

	private suspend fun pumpDecoder() {
		var reachedEnd = false
		var observedSeekGeneration = seekGeneration.get()
		var cachedLoop: CachedLoopCycle? = null
		var cachedLoopOffset = 0
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
				val outputCapacity = minOf(
					NativePlaybackBridge.writeSpace(handle),
					targetQueue - queued,
				)
				val frameLimit = maxInputFrames(outputCapacity, playbackRate)
				if (frameLimit <= 0) {
					delay(PUMP_DELAY_MS)
					continue
				}
				val activeDecoder = decoder ?: return
				val shouldStop = decoderMutex.withLock {
					val loop = mutableState.value.loopRegion?.takeIf { it.enabled }
					val fadeFrames = loop?.effectiveCrossfadeFrames(currentTrack.sampleRate)?.toInt() ?: 0
					val loopFrames = loop?.let { it.bFrame - it.aFrame }
					if (stretcherNeedsPrime) {
						val prerollFrames = NativePlaybackBridge.prerollFrames(handle, playbackRate.factor)
						val isShortLoop = loop != null && loopFrames != null &&
								loopFrames <= currentTrack.sampleRate.toLong() * SHORT_LOOP_CACHE_MAX_SECONDS
						val cycle = if (isShortLoop) cachedLoop?.takeIf { it.region == loop } ?: prepareCachedLoopCycle(
							activeDecoder,
							currentTrack,
							loop,
							fadeFrames,
						) else null
						val preroll = if (cycle == null) {
							readPrerollFrames(activeDecoder, currentTrack, prerollFrames, loop)
						} else {
							loopCycleFrames(cycle, prerollFrames)
						}
						if (preroll == null || !NativePlaybackBridge.prime(
							handle,
							preroll,
							prerollFrames,
							currentTrack.channelCount,
							playbackRate.factor,
						)) {
							mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
							NativePlaybackBridge.setEndOfStream(handle)
							return@withLock true
						}
						if (cycle != null) {
							cachedLoop = cycle
							cachedLoopOffset = prerollFrames % cycle.frameCount
							stretchedOutputSourceFrame = cycle.region.aFrame + cycle.fadeFrames
						}
						stretcherNeedsPrime = false
					}
					if (loop != null && loopFrames != null && loopFrames <= currentTrack.sampleRate.toLong() * SHORT_LOOP_CACHE_MAX_SECONDS) {
						val cycle = cachedLoop?.takeIf { it.region == loop } ?: prepareCachedLoopCycle(
							activeDecoder,
							currentTrack,
							loop,
							fadeFrames,
						)
						if (cycle == null) {
							mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
							NativePlaybackBridge.setEndOfStream(handle)
							true
						} else {
							if (cycle !== cachedLoop) cachedLoopOffset = 0
							cachedLoop = cycle
							val write = writeCachedLoopFrames(
								handle,
								cycle,
								cachedLoopOffset,
								frameLimit,
								currentTrack.channelCount,
							)
							cachedLoopOffset = write.nextOffset
							if (write.framesWritten == frameLimit) false else {
								mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
								NativePlaybackBridge.setEndOfStream(handle)
								true
							}
						}
					} else if (loop != null && decoderPositionFrame >= loop.bFrame) {
						val seeked = activeDecoder.seekToFrame(loop.aFrame) as? PcmSeekResult.Seeked
						if (seeked == null) {
							mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
							NativePlaybackBridge.setEndOfStream(handle)
							true
						} else {
							decoderPositionFrame = seeked.frame
							false
						}
					} else if (loop != null && fadeFrames > 0 && decoderPositionFrame >= loop.bFrame - fadeFrames) {
						val tail = readExactFrames(activeDecoder, currentTrack, fadeFrames, loop)
						val headSeek = activeDecoder.seekToFrame(loop.aFrame) as? PcmSeekResult.Seeked
						if (headSeek != null) decoderPositionFrame = headSeek.frame
						val head = if (headSeek != null) readExactFrames(activeDecoder, currentTrack, fadeFrames, loop) else null
						if (tail == null || headSeek == null || head == null) {
							mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
							NativePlaybackBridge.setEndOfStream(handle)
							true
						} else {
							val mixed = equalPowerCrossfade(tail, head, fadeFrames, currentTrack.channelCount)
							val fadeStart = loop.bFrame - fadeFrames
							val written = writeStretched(
								handle,
								mixed,
								0,
								fadeFrames,
								fadeStart,
								loop,
								currentTrack.channelCount,
							)
							if (written != fadeFrames) {
								mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
								NativePlaybackBridge.setEndOfStream(handle)
								true
							} else {
								decoderPositionFrame = headSeek.frame + fadeFrames
								stretchedOutputSourceFrame = headSeek.frame + fadeFrames
								false
							}
						}
					} else {
						val loopBoundary = if (loop != null) {
							if (fadeFrames > 0) loop.bFrame - fadeFrames else loop.bFrame
						} else currentTrack.durationFrames
						val untilBoundary = (loopBoundary - decoderPositionFrame).coerceAtLeast(1)
						val boundedFrameLimit = minOf(frameLimit.toLong(), untilBoundary).toInt()
						val result = try {
							activeDecoder.readFrames(boundedFrameLimit)
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
									val written = writeStretched(
										handle,
										block.samples,
										0,
										block.frameCount,
										stretchedOutputSourceFrame,
										loop,
										block.format.channelCount,
									)
									if (written == block.frameCount) {
										decoderPositionFrame += block.frameCount
										stretchedOutputSourceFrame = advanceSourceFrame(
											stretchedOutputSourceFrame,
											block.frameCount,
											loop,
											currentTrack.durationFrames,
										)
										false
									} else {
										mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
										NativePlaybackBridge.setEndOfStream(handle)
										true
									}
								}
							}
							PcmReadResult.EndOfStream -> {
								if (!NativePlaybackBridge.finish(
									handle,
									playbackRate.factor,
									stretchedOutputRemainder,
									currentTrack.durationFrames,
								)) {
									mutableState.update { it.copy(error = PlaybackError.DECODER_READ_FAILED) }
									NativePlaybackBridge.setEndOfStream(handle)
								}
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

	private fun maxInputFrames(outputCapacity: Int, rate: PlaybackRate): Int {
		if (outputCapacity <= 1) return 0
		val availableOutput = outputCapacity - 1 - stretchedOutputRemainder
		return minOf(MAX_DECODE_BLOCK_FRAMES, (availableOutput * rate.factor).toInt()).coerceAtLeast(0)
	}

	private fun writeStretched(
		handle: Long,
		samples: FloatArray,
		offsetFrames: Int,
		inputFrames: Int,
		sourceStartFrame: Long,
		loop: LoopRegion?,
		channelCount: Int,
	): Int {
		val requestedOutput = inputFrames / playbackRate.factor + stretchedOutputRemainder
		val outputFrames = requestedOutput.toInt()
		if (outputFrames <= 0) return 0
		val remainder = requestedOutput - outputFrames
		val written = NativePlaybackBridge.write(
			handle,
			samples,
			offsetFrames,
			inputFrames,
			outputFrames,
			channelCount,
			sourceStartFrame,
			inputFrames.toDouble() / outputFrames,
			loop?.aFrame ?: -1L,
			loop?.bFrame ?: -1L,
		)
		if (written == inputFrames) stretchedOutputRemainder = remainder
		return written
	}

	private fun advanceSourceFrame(position: Long, inputFrames: Int, loop: LoopRegion?, durationFrames: Long): Long {
		val next = position + inputFrames
		if (loop == null) return next.coerceAtMost(durationFrames)
		return loop.aFrame + (next - loop.aFrame).coerceAtLeast(0) % (loop.bFrame - loop.aFrame)
	}

	private suspend fun readPrerollFrames(
		activeDecoder: PcmDecoder,
		currentTrack: TrackInfo,
		frameCount: Int,
		loop: LoopRegion?,
	): FloatArray? {
		val samples = FloatArray(frameCount * currentTrack.channelCount)
		var framesRead = 0
		while (framesRead < frameCount) {
			if (loop != null && decoderPositionFrame >= loop.bFrame) {
				val seeked = activeDecoder.seekToFrame(loop.aFrame) as? PcmSeekResult.Seeked ?: return null
				decoderPositionFrame = seeked.frame
			}
			val boundary = loop?.bFrame ?: currentTrack.durationFrames
			if (decoderPositionFrame >= boundary) break
			val blockLimit = minOf(
				MAX_DECODE_BLOCK_FRAMES,
				frameCount - framesRead,
				(boundary - decoderPositionFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
			)
			when (val result = activeDecoder.readFrames(blockLimit)) {
				is PcmReadResult.Frames -> {
					val block = result.block
					if (block.format.sampleRate != currentTrack.sampleRate || block.format.channelCount != currentTrack.channelCount) return null
					block.samples.copyInto(samples, framesRead * currentTrack.channelCount)
					framesRead += block.frameCount
					decoderPositionFrame += block.frameCount
				}
				PcmReadResult.EndOfStream -> {
					if (loop == null) return samples
					if (framesRead == 0 && decoderPositionFrame <= loop.aFrame) return null
					val seeked = activeDecoder.seekToFrame(loop.aFrame) as? PcmSeekResult.Seeked ?: return null
					decoderPositionFrame = seeked.frame
				}
				is PcmReadResult.Failed -> return null
			}
		}
		return samples
	}

	private fun loopCycleFrames(cycle: CachedLoopCycle, frameCount: Int): FloatArray {
		val samples = FloatArray(frameCount * cycle.samples.size / cycle.frameCount)
		val channels = cycle.samples.size / cycle.frameCount
		for (frame in 0 until frameCount) {
			val sourceFrame = frame % cycle.frameCount
			cycle.samples.copyInto(samples, frame * channels, sourceFrame * channels, (sourceFrame + 1) * channels)
		}
		return samples
	}

	private suspend fun readExactFrames(
		activeDecoder: PcmDecoder,
		currentTrack: TrackInfo,
		frameCount: Int,
		loop: LoopRegion? = null,
	): FloatArray? {
		val samples = FloatArray(frameCount * currentTrack.channelCount)
		var framesRead = 0
		var decoderCursorFrame = decoderPositionFrame
		while (framesRead < frameCount) {
			val result = try {
				activeDecoder.readFrames(minOf(MAX_DECODE_BLOCK_FRAMES, frameCount - framesRead))
			} catch (_: RuntimeException) {
				return null
			}
			when (result) {
				is PcmReadResult.Frames -> {
					val block = result.block
					if (block.format.sampleRate != currentTrack.sampleRate || block.format.channelCount != currentTrack.channelCount) {
						return null
					}
					block.samples.copyInto(samples, framesRead * currentTrack.channelCount)
					framesRead += block.frameCount
					decoderCursorFrame += block.frameCount
				}
				PcmReadResult.EndOfStream -> {
					if (loop == null || (framesRead == 0 && decoderCursorFrame <= loop.aFrame)) return null
					val seeked = activeDecoder.seekToFrame(loop.aFrame) as? PcmSeekResult.Seeked ?: return null
					decoderCursorFrame = seeked.frame
				}
				is PcmReadResult.Failed -> {
					return null
				}
			}
		}
		return samples
	}

	private suspend fun prepareCachedLoopCycle(
		activeDecoder: PcmDecoder,
		currentTrack: TrackInfo,
		region: LoopRegion,
		fadeFrames: Int,
	): CachedLoopCycle? {
		val sourceFrames = (region.bFrame - region.aFrame).toInt()
		val seeked = activeDecoder.seekToFrame(region.aFrame) as? PcmSeekResult.Seeked ?: return null
		decoderPositionFrame = seeked.frame
		val source = readExactFrames(activeDecoder, currentTrack, sourceFrames, region) ?: return null
		val prefixFrames = sourceFrames - fadeFrames * 2
		val cycleFrames = prefixFrames + fadeFrames
		val cycleSamples = FloatArray(cycleFrames * currentTrack.channelCount)
		val prefixSamples = prefixFrames * currentTrack.channelCount
		if (prefixSamples > 0) source.copyInto(
			cycleSamples,
			endIndex = prefixSamples,
			startIndex = fadeFrames * currentTrack.channelCount,
		)
		if (fadeFrames > 0) {
			val tailStart = (sourceFrames - fadeFrames) * currentTrack.channelCount
			val tail = source.copyOfRange(tailStart, source.size)
			val head = source.copyOfRange(0, fadeFrames * currentTrack.channelCount)
			equalPowerCrossfade(tail, head, fadeFrames, currentTrack.channelCount)
				.copyInto(cycleSamples, prefixSamples)
		}
		decoderPositionFrame = region.bFrame
		return CachedLoopCycle(region, cycleSamples, prefixFrames, cycleFrames, fadeFrames)
	}

	private fun writeCachedLoopFrames(
		handle: Long,
		cycle: CachedLoopCycle,
		startOffset: Int,
		requestedFrames: Int,
		channelCount: Int,
	): CachedLoopWrite {
		var offset = startOffset
		var remaining = requestedFrames
		var writtenFrames = 0
		while (remaining > 0) {
			val prefixRemaining = if (offset < cycle.prefixFrames) cycle.prefixFrames - offset else 0
			val segmentFrames = if (prefixRemaining > 0) prefixRemaining else cycle.frameCount - offset
			val chunkFrames = minOf(remaining, segmentFrames)
			val sourceStartFrame = if (prefixRemaining > 0) {
				cycle.region.aFrame + cycle.fadeFrames + offset
			} else {
				cycle.region.bFrame - cycle.fadeFrames + offset - cycle.prefixFrames
			}
			val written = writeStretched(
				handle,
				cycle.samples,
				offset,
				chunkFrames,
				sourceStartFrame,
				cycle.region.takeIf { it.enabled },
				channelCount,
			)
			writtenFrames += written
			remaining -= written
			offset = (offset + written) % cycle.frameCount
			if (written != chunkFrames) break
		}
		return CachedLoopWrite(writtenFrames, offset)
	}

	private fun equalPowerCrossfade(tail: FloatArray, head: FloatArray, frameCount: Int, channelCount: Int): FloatArray {
		val mixed = FloatArray(tail.size)
		for (frame in 0 until frameCount) {
			val progress = if (frameCount <= 1) 1.0 else frame.toDouble() / (frameCount - 1)
			val angle = progress * PI / 2.0
			val tailGain = cos(angle).toFloat()
			val headGain = sin(angle).toFloat()
			for (channel in 0 until channelCount) {
				val sample = frame * channelCount + channel
				mixed[sample] = (tail[sample] * tailGain + head[sample] * headGain).coerceIn(-1f, 1f)
			}
		}
		return mixed
	}

	private suspend fun awaitPrebuffer(handle: Long, startFrame: Long) {
		val currentTrack = track ?: return
		val remainingFrames = (currentTrack.durationFrames - startFrame).coerceAtLeast(0)
		val targetFrames = minOf(
			currentTrack.sampleRate / PREFILL_DIVISOR,
			if (mutableState.value.loopRegion?.enabled == true) Int.MAX_VALUE else {
				(remainingFrames / playbackRate.factor).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
			},
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
		const val DEFAULT_CROSSFADE_MS = 20
		const val SHORT_LOOP_CACHE_MAX_SECONDS = 2
	}

	private data class CachedLoopCycle(
		val region: LoopRegion,
		val samples: FloatArray,
		val prefixFrames: Int,
		val frameCount: Int,
		val fadeFrames: Int,
	)

	private data class CachedLoopWrite(val framesWritten: Int, val nextOffset: Int)
}
