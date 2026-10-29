package com.p33nah.loopo.domain.model

import android.net.Uri

@JvmInline
value class TrackId(val value: String)

enum class AudioContainer {
	MP3,
	WAV,
	FLAC,
}

data class TrackInfo(
	val id: TrackId,
	val uri: Uri,
	val displayName: String,
	val container: AudioContainer,
	val durationFrames: Long,
	val sampleRate: Int,
	val channelCount: Int,
	val sourceMimeType: String,
	val sourceSizeBytes: Long? = null,
	val sourceModifiedAtMillis: Long? = null,
) {
	init {
		require(durationFrames > 0)
		require(sampleRate > 0)
		require(channelCount in 1..2)
		require(sourceSizeBytes == null || sourceSizeBytes >= 0)
		require(sourceModifiedAtMillis == null || sourceModifiedAtMillis >= 0)
	}
}
