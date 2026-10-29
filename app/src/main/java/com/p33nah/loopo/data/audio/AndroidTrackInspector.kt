package com.p33nah.loopo.data.audio

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.p33nah.loopo.domain.model.TrackId
import com.p33nah.loopo.domain.model.TrackInfo
import java.io.IOException

class AndroidTrackInspector(context: Context) {
	private val appContext = context.applicationContext
	private val resolver: ContentResolver = appContext.contentResolver

	fun inspect(uri: Uri, hasPersistedAccess: Boolean): TrackInspection {
		val extractor = MediaExtractor()
		return try {
			val displayName = displayName(uri)
			val extension = displayName.substringAfterLast('.', "").lowercase()
			val resolverMimeType = resolver.getType(uri)
			if (extension !in supportedExtensions && resolverMimeType !in supportedMimeTypes) {
				return TrackInspection.Failed(TrackInspection.Reason.UNSUPPORTED)
			}

			extractor.setDataSource(appContext, uri, null)
			val audioFormat = (0 until extractor.trackCount)
				.asSequence()
				.map(extractor::getTrackFormat)
				.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
				?: return TrackInspection.Failed(TrackInspection.Reason.NO_AUDIO_TRACK)

			val mimeType = audioFormat.getString(MediaFormat.KEY_MIME)
			val durationUs = audioFormat.longOrNull(MediaFormat.KEY_DURATION)
			val sampleRate = audioFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
			val channelCount = audioFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
			if (mimeType == null || durationUs == null || durationUs <= 0 ||
				sampleRate == null || sampleRate <= 0 || channelCount == null || channelCount !in 1..2
			) {
				return TrackInspection.Failed(TrackInspection.Reason.INVALID_METADATA)
			}

			val frames = ((durationUs * sampleRate) + 500_000) / 1_000_000
			if (frames <= 0) return TrackInspection.Failed(TrackInspection.Reason.INVALID_METADATA)

			TrackInspection.Ready(
				track = TrackInfo(
					id = TrackId(uri.toString()),
					uri = uri,
					displayName = displayName,
					durationFrames = frames,
					sampleRate = sampleRate,
					channelCount = channelCount,
					sourceMimeType = mimeType,
				),
				hasPersistedAccess = hasPersistedAccess,
			)
		} catch (_: IOException) {
			TrackInspection.Failed(TrackInspection.Reason.READ_FAILED)
		} catch (_: SecurityException) {
			TrackInspection.Failed(TrackInspection.Reason.READ_FAILED)
		} catch (_: IllegalArgumentException) {
			TrackInspection.Failed(TrackInspection.Reason.UNSUPPORTED)
		} finally {
			extractor.release()
		}
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
		val supportedExtensions = setOf("mp3", "wav", "flac")
		val supportedMimeTypes = setOf("audio/mpeg", "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/vnd.wave")
	}
}
