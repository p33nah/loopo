package com.p33nah.loopo.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.p33nah.loopo.domain.audio.PcmBlock
import com.p33nah.loopo.domain.audio.PcmDecodeFailure
import com.p33nah.loopo.domain.audio.PcmDecoder
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.audio.PcmFormat
import com.p33nah.loopo.domain.audio.PcmReadResult
import com.p33nah.loopo.domain.audio.PcmSeekResult
import java.io.IOException
import java.nio.ByteOrder

class MediaCodecPcmDecoder private constructor(
	private val extractor: MediaExtractor,
	private val codec: MediaCodec,
	override var format: PcmFormat,
	private var pcmEncoding: Int,
) : PcmDecoder {
	private val info = MediaCodec.BufferInfo()
	private var inputEnded = false
	private var outputEnded = false
	private var seekTargetFrame: Long? = null
	private var pendingSamples = FloatArray(0)
	private var pendingOffset = 0
	private var pendingCount = 0
	private var closed = false

	override fun readFrames(maxFrames: Int): PcmReadResult {
		if (closed || maxFrames <= 0) return PcmReadResult.Failed(PcmDecodeFailure.READ_FAILED)
		val wantedSamples = maxFrames * format.channelCount
		while (pendingCount < wantedSamples && !outputEnded) {
			when (val result = decodeNextOutput()) {
				is DecodeResult.Samples -> append(result.samples)
				DecodeResult.End -> outputEnded = true
				is DecodeResult.Failed -> return PcmReadResult.Failed(result.reason)
				DecodeResult.NoOutput -> Unit
			}
		}
		val available = pendingCount
		if (available == 0) return PcmReadResult.EndOfStream
		val frameCount = minOf(maxFrames, available / format.channelCount)
		if (frameCount <= 0) return PcmReadResult.Failed(PcmDecodeFailure.READ_FAILED)
		val sampleCount = frameCount * format.channelCount
		val samples = FloatArray(sampleCount)
		System.arraycopy(pendingSamples, pendingOffset, samples, 0, sampleCount)
		pendingOffset += sampleCount
		pendingCount -= sampleCount
		if (pendingCount == 0) {
			pendingOffset = 0
		}
		return PcmReadResult.Frames(PcmBlock(format, frameCount, samples))
	}

	override fun seekToFrame(frame: Long): PcmSeekResult {
		if (closed || frame < 0) return PcmSeekResult.Failed(PcmDecodeFailure.INVALID_FILE)
		return try {
			val timeUs = (frame * 1_000_000L) / format.sampleRate
			extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
			codec.flush()
			inputEnded = false
			outputEnded = false
			pendingOffset = 0
			pendingCount = 0
			seekTargetFrame = frame
			PcmSeekResult.Seeked(frame)
		} catch (_: IllegalStateException) {
			PcmSeekResult.Failed(PcmDecodeFailure.READ_FAILED)
		} catch (_: IllegalArgumentException) {
			PcmSeekResult.Failed(PcmDecodeFailure.INVALID_FILE)
		}
	}

	private fun decodeNextOutput(): DecodeResult {
		var idleCycles = 0
		while (idleCycles < MAX_IDLE_CYCLES) {
			if (!inputEnded) {
				val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
				if (inputIndex >= 0) {
					val inputBuffer = codec.getInputBuffer(inputIndex)
						?: return DecodeResult.Failed(PcmDecodeFailure.READ_FAILED)
					inputBuffer.clear()
					val sampleSize = extractor.readSampleData(inputBuffer, 0)
					if (sampleSize < 0) {
						codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
						inputEnded = true
					} else {
						codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
						extractor.advance()
					}
				}
			}

			when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
				MediaCodec.INFO_TRY_AGAIN_LATER -> idleCycles++
				MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
					val outputFormat = codec.outputFormat
					val sampleRate = outputFormat.integerOrNull(MediaFormat.KEY_SAMPLE_RATE)
					val channels = outputFormat.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
					if (sampleRate == null || sampleRate <= 0 || channels == null || channels !in 1..2) {
						return DecodeResult.Failed(PcmDecodeFailure.UNSUPPORTED_CHANNEL_LAYOUT)
					}
					format = PcmFormat(sampleRate, channels)
					pcmEncoding = outputFormat.integerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
					idleCycles = 0
				}
				MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
				else -> {
					idleCycles = 0
					val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
					val samples = try {
						if (info.size == 0) FloatArray(0) else {
							val buffer = codec.getOutputBuffer(outputIndex)
								?: return DecodeResult.Failed(PcmDecodeFailure.READ_FAILED)
							val bytesPerSample = when (pcmEncoding) {
								AudioFormat.ENCODING_PCM_16BIT -> 2
								AudioFormat.ENCODING_PCM_FLOAT -> 4
								else -> return DecodeResult.Failed(PcmDecodeFailure.UNSUPPORTED_PCM_ENCODING)
							}
							val bytesPerFrame = bytesPerSample * format.channelCount
							if (info.size % bytesPerFrame != 0) return DecodeResult.Failed(PcmDecodeFailure.READ_FAILED)
							val totalFrames = info.size / bytesPerFrame
							val targetFrame = seekTargetFrame
							val outputStartFrame = ((info.presentationTimeUs * format.sampleRate) + 500_000) / 1_000_000
							val framesToDrop = if (targetFrame == null) 0 else
								(targetFrame - outputStartFrame).coerceIn(0, totalFrames.toLong()).toInt()
							if (targetFrame != null && outputStartFrame + totalFrames <= targetFrame) {
								FloatArray(0)
							} else {
								val remainingFrames = totalFrames - framesToDrop
								buffer.position(info.offset + framesToDrop * bytesPerFrame)
								buffer.limit(info.offset + info.size)
								buffer.order(ByteOrder.LITTLE_ENDIAN)
								val samples = when (pcmEncoding) {
									AudioFormat.ENCODING_PCM_16BIT -> FloatArray(remainingFrames * format.channelCount) { buffer.short / 32768f }
									AudioFormat.ENCODING_PCM_FLOAT -> FloatArray(remainingFrames * format.channelCount) {
									buffer.float.let { sample -> if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f }
								}
									else -> error("PCM encoding checked above")
								}
								if (targetFrame != null) seekTargetFrame = null
								samples
							}
						}
					} finally {
						codec.releaseOutputBuffer(outputIndex, false)
					}
					if (samples.size % format.channelCount != 0) return DecodeResult.Failed(PcmDecodeFailure.READ_FAILED)
					if (isEos) {
						inputEnded = true
						outputEnded = true
					}
					if (samples.isNotEmpty()) return DecodeResult.Samples(samples)
					if (isEos) return DecodeResult.End
				}
			}
		}
		return DecodeResult.Failed(PcmDecodeFailure.READ_FAILED)
	}

	private fun append(samples: FloatArray) {
		if (samples.isEmpty()) return
		if (pendingOffset + pendingCount + samples.size > pendingSamples.size) {
			if (pendingOffset > 0) {
				System.arraycopy(pendingSamples, pendingOffset, pendingSamples, 0, pendingCount)
				pendingOffset = 0
			}
			val requiredCapacity = pendingCount + samples.size
			if (requiredCapacity > pendingSamples.size) {
				val doubledCapacity = (pendingSamples.size * 2).coerceAtLeast(MIN_PENDING_SAMPLES)
				pendingSamples = pendingSamples.copyOf(maxOf(requiredCapacity, doubledCapacity))
			}
		}
		System.arraycopy(samples, 0, pendingSamples, pendingOffset + pendingCount, samples.size)
		pendingCount += samples.size
	}

	override fun close() {
		if (!closed) {
			closed = true
			try {
				codec.stop()
			} catch (_: IllegalStateException) {
				// The codec may not have reached a started state after a platform error.
			}
			codec.release()
			extractor.release()
		}
	}

	private sealed interface DecodeResult {
		data class Samples(val samples: FloatArray) : DecodeResult
		data class Failed(val reason: PcmDecodeFailure) : DecodeResult
		data object End : DecodeResult
		data object NoOutput : DecodeResult
	}

	companion object {
		private const val TIMEOUT_US = 10_000L
		private const val MAX_IDLE_CYCLES = 500
		private const val MIN_PENDING_SAMPLES = 4_096

		fun open(context: Context, uri: Uri): PcmDecoderResult {
			val extractor = MediaExtractor()
			var codec: MediaCodec? = null
			return try {
				extractor.setDataSource(context.applicationContext, uri, null)
				var audioTrack = -1
				var inputFormat: MediaFormat? = null
				for (index in 0 until extractor.trackCount) {
					val candidate = extractor.getTrackFormat(index)
					if (candidate.stringOrNull(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
						audioTrack = index
						inputFormat = candidate
						break
					}
				}
				val trackFormat = inputFormat ?: run {
					extractor.release()
					return PcmDecoderResult.Failed(PcmDecodeFailure.UNSUPPORTED_FORMAT)
				}
				val mime = trackFormat.getString(MediaFormat.KEY_MIME)
					?: run {
						extractor.release()
						return PcmDecoderResult.Failed(PcmDecodeFailure.UNSUPPORTED_FORMAT)
					}
				extractor.selectTrack(audioTrack)
				codec = MediaCodec.createDecoderByType(mime)
				codec.configure(trackFormat, null, null, 0)
				codec.start()
				val sampleRate = trackFormat.integerOrNull(MediaFormat.KEY_SAMPLE_RATE)
					?: return failOpen(codec, extractor, PcmDecodeFailure.INVALID_FILE)
				val channels = trackFormat.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
					?: return failOpen(codec, extractor, PcmDecodeFailure.INVALID_FILE)
				if (sampleRate <= 0) return failOpen(codec, extractor, PcmDecodeFailure.INVALID_FILE)
				if (channels !in 1..2) return failOpen(codec, extractor, PcmDecodeFailure.UNSUPPORTED_CHANNEL_LAYOUT)
				PcmDecoderResult.Ready(
					MediaCodecPcmDecoder(
						extractor,
						codec,
						PcmFormat(sampleRate, channels),
						trackFormat.integerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT,
					),
				)
			} catch (_: SecurityException) {
				codec?.release()
				extractor.release()
				PcmDecoderResult.Failed(PcmDecodeFailure.READ_FAILED)
			} catch (_: IOException) {
				codec?.release()
				extractor.release()
				PcmDecoderResult.Failed(PcmDecodeFailure.DECODER_UNAVAILABLE)
			} catch (_: IllegalArgumentException) {
				codec?.release()
				extractor.release()
				PcmDecoderResult.Failed(PcmDecodeFailure.DECODER_UNAVAILABLE)
			} catch (_: IllegalStateException) {
				codec?.release()
				extractor.release()
				PcmDecoderResult.Failed(PcmDecodeFailure.DECODER_UNAVAILABLE)
			}
		}

		private fun failOpen(codec: MediaCodec, extractor: MediaExtractor, reason: PcmDecodeFailure): PcmDecoderResult {
			codec.release()
			extractor.release()
			return PcmDecoderResult.Failed(reason)
		}

		private fun MediaFormat.integerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
		private fun MediaFormat.stringOrNull(key: String): String? = if (containsKey(key)) getString(key) else null
	}
}
