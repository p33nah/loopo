package com.p33nah.loopo.data.audio

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.p33nah.loopo.domain.model.TrackId
import com.p33nah.loopo.domain.model.AudioContainer
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import java.io.IOException

class AndroidTrackInspector(context: Context) {
	private val appContext = context.applicationContext
	private val resolver: ContentResolver = appContext.contentResolver

	fun inspect(uri: Uri, hasPersistedAccess: Boolean): TrackInspection {
		return try {
			val displayName = displayName(uri)
			val extension = displayName.substringAfterLast('.', "").lowercase()
			val resolverMimeType = resolver.getType(uri)
			val container = containerFor(extension, resolverMimeType)
				?: return TrackInspection.Failed(TrackInspection.Reason.UNSUPPORTED)
			val metadata = if (container == AudioContainer.WAV) {
				when (val opened = WavPcmDecoder.open(resolver, uri)) {
					is PcmDecoderResult.Failed -> return TrackInspection.Failed(opened.reason.toInspectionReason())
					is PcmDecoderResult.Ready -> opened.decoder.use { decoder ->
						TrackMetadata(
							mime = resolverMimeType ?: "audio/wav",
							frames = (decoder as WavPcmDecoder).durationFrames,
							sampleRate = decoder.format.sampleRate,
							channels = decoder.format.channelCount,
						)
					}
				}
			} else {
				inspectCompressed(uri) ?: return TrackInspection.Failed(TrackInspection.Reason.NO_AUDIO_TRACK)
			}

			TrackInspection.Ready(
				track = TrackInfo(
					id = TrackId(uri.toString()),
					uri = uri,
					displayName = displayName,
					container = container,
					durationFrames = metadata.frames,
					sampleRate = metadata.sampleRate,
					channelCount = metadata.channels,
					sourceMimeType = metadata.mime,
				),
				hasPersistedAccess = hasPersistedAccess,
			)
		} catch (_: IOException) {
			TrackInspection.Failed(TrackInspection.Reason.READ_FAILED)
		} catch (_: SecurityException) {
			TrackInspection.Failed(TrackInspection.Reason.READ_FAILED)
		} catch (_: IllegalArgumentException) {
			TrackInspection.Failed(TrackInspection.Reason.UNSUPPORTED)
		}
	}

	private fun inspectCompressed(uri: Uri): TrackMetadata? {
		val extractor = MediaExtractor()
		return try {
			extractor.setDataSource(appContext, uri, null)
			val audioFormat = (0 until extractor.trackCount)
				.asSequence()
				.map(extractor::getTrackFormat)
				.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
				?: return null
			val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
			val durationUs = audioFormat.longOrNull(MediaFormat.KEY_DURATION) ?: return null
			val sampleRate = audioFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: return null
			val channels = audioFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: return null
			if (durationUs <= 0 || sampleRate <= 0 || channels !in 1..2) return null
			val frames = ((durationUs * sampleRate) + 500_000) / 1_000_000
			if (frames <= 0) return null
			TrackMetadata(mime, frames, sampleRate, channels)
		} finally {
			extractor.release()
		}
	}

	private fun containerFor(extension: String, mimeType: String?): AudioContainer? = when {
		extension == "wav" || mimeType in wavMimeTypes -> AudioContainer.WAV
		extension == "mp3" || mimeType == "audio/mpeg" -> AudioContainer.MP3
		extension == "flac" || mimeType in flacMimeTypes -> AudioContainer.FLAC
		else -> null
	}

	private data class TrackMetadata(val mime: String, val frames: Long, val sampleRate: Int, val channels: Int)

	private fun com.p33nah.loopo.domain.audio.PcmDecodeFailure.toInspectionReason() = when (this) {
		com.p33nah.loopo.domain.audio.PcmDecodeFailure.UNSUPPORTED_FORMAT,
		com.p33nah.loopo.domain.audio.PcmDecodeFailure.UNSUPPORTED_PCM_ENCODING -> TrackInspection.Reason.UNSUPPORTED
		com.p33nah.loopo.domain.audio.PcmDecodeFailure.UNSUPPORTED_CHANNEL_LAYOUT -> TrackInspection.Reason.UNSUPPORTED_CHANNEL_LAYOUT
		com.p33nah.loopo.domain.audio.PcmDecodeFailure.INVALID_FILE -> TrackInspection.Reason.INVALID_METADATA
		com.p33nah.loopo.domain.audio.PcmDecodeFailure.DECODER_UNAVAILABLE -> TrackInspection.Reason.DECODER_UNAVAILABLE
		com.p33nah.loopo.domain.audio.PcmDecodeFailure.READ_FAILED -> TrackInspection.Reason.READ_FAILED
	}

	private fun displayName(uri: Uri): String = resolver.query(
		uri,
		arrayOf(OpenableColumns.DISPLAY_NAME),
		null,
		null,
		null,
	)?.use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else null
		} ?: uri.lastPathSegment ?: "Audio"

	private fun MediaFormat.longOrNull(key: String): Long? =
		if (containsKey(key)) getLong(key) else null

	private fun MediaFormat.intOrNull(key: String): Int? =
		if (containsKey(key)) getInteger(key) else null

	private companion object {
		val wavMimeTypes = setOf("audio/wav", "audio/x-wav", "audio/vnd.wave")
		val flacMimeTypes = setOf("audio/flac", "audio/x-flac")
	}
}
