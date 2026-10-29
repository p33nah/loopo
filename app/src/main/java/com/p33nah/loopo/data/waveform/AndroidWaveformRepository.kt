package com.p33nah.loopo.data.waveform

import android.content.Context
import com.p33nah.loopo.data.audio.AndroidPcmDecoderFactory
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmReadResult
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.waveform.WaveformPhase
import com.p33nah.loopo.domain.waveform.WaveformRepository
import com.p33nah.loopo.domain.waveform.WaveformState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.math.max

class AndroidWaveformRepository(context: Context) : WaveformRepository {
	private val appContext = context.applicationContext
	private val decoderFactory = AndroidPcmDecoderFactory(appContext)
	private val cache = WaveformCache(appContext.contentResolver, File(appContext.cacheDir, "audio"))

	override fun observe(track: TrackInfo): Flow<WaveformState> = flow {
		emit(
			WaveformState.Loading(
				trackId = track.id,
				phase = WaveformPhase.VERIFYING_SOURCE,
				processedFrames = 0,
				totalFrames = track.durationFrames,
				preview = null,
			),
		)
		val sourceKey = runCatching { cache.sourceKey(track) }.getOrNull()
		if (sourceKey != null) {
			cache.read(sourceKey, track)?.let { cached ->
				emit(WaveformState.Ready(cached))
				return@flow
			}
		}

		val decoder = when (val result = decoderFactory.open(track.container, track.uri)) {
			is PcmDecoderResult.Failed -> {
				emit(WaveformState.Failed(track.id))
				return@flow
			}
			is PcmDecoderResult.Ready -> result.decoder
		}
		val bucketFrames = WaveformPyramidBuilder.baseBucketFrames(track)
		val previewBucketFrames = max(bucketFrames * PREVIEW_BUCKET_MULTIPLIER, MIN_PREVIEW_BUCKET_FRAMES)
		val pyramid = WaveformPyramidBuilder(track, bucketFrames)
		val previewPyramid = WaveformPyramidBuilder(track, previewBucketFrames)
		var nextProgressFrame = max(1, track.sampleRate / PROGRESS_INTERVAL_DIVISOR).toLong()
		try {
			while (true) {
				currentCoroutineContext().ensureActive()
				when (val result = decoder.readFrames(DECODE_BLOCK_FRAMES)) {
					is PcmReadResult.Frames -> {
						val block = result.block
						if (block.format.sampleRate != track.sampleRate || block.format.channelCount != track.channelCount) {
							emit(WaveformState.Failed(track.id))
							return@flow
						}
						val framesRemaining = (track.durationFrames - pyramid.processedFrames).coerceAtLeast(0)
						val framesToAdd = minOf(block.frameCount.toLong(), framesRemaining).toInt()
						if (framesToAdd == 0) break
						pyramid.add(block, framesToAdd)
						previewPyramid.add(block, framesToAdd)
						if (pyramid.processedFrames >= nextProgressFrame) {
							val preview = previewPyramid.snapshot().takeIf { it.levels.first().bucketCount > 0 }
							emit(
								WaveformState.Loading(
									trackId = track.id,
									phase = WaveformPhase.DECODING,
									processedFrames = pyramid.processedFrames.coerceAtMost(track.durationFrames),
									totalFrames = track.durationFrames,
									preview = preview,
								),
							)
							nextProgressFrame = pyramid.processedFrames + max(1, track.sampleRate / PROGRESS_INTERVAL_DIVISOR)
						}
						if (pyramid.processedFrames >= track.durationFrames) break
					}
					PcmReadResult.EndOfStream -> break
					is PcmReadResult.Failed -> {
						emit(WaveformState.Failed(track.id))
						return@flow
					}
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			emit(WaveformState.Failed(track.id))
			return@flow
		} finally {
			decoder.close()
		}

		val waveform = pyramid.snapshot(includePartialBucket = true)
		if (sourceKey != null) cache.write(sourceKey, waveform)
		emit(WaveformState.Ready(waveform))
	}.catch { error ->
		if (error is CancellationException) throw error
		emit(WaveformState.Failed(track.id))
	}

	private companion object {
		const val DECODE_BLOCK_FRAMES = 8_192
		const val MIN_PREVIEW_BUCKET_FRAMES = 8_192L
		const val PREVIEW_BUCKET_MULTIPLIER = 128L
		const val PROGRESS_INTERVAL_DIVISOR = 2
	}
}
