package com.p33nah.loopo.domain.model

import com.p33nah.loopo.domain.waveform.WaveformViewport

data class LoopRegion(
	val aFrame: Long,
	val bFrame: Long,
	val enabled: Boolean,
	val crossfadeMs: Int,
) {
	init {
		require(aFrame >= 0)
		require(aFrame < bFrame)
		require(crossfadeMs in 0..100)
	}

	fun moveATo(frame: Long, durationFrames: Long, sampleRate: Int): LoopRegion {
		val minSpan = minSpanFrames(durationFrames, sampleRate)
		return copy(aFrame = frame.coerceIn(0, (bFrame - minSpan).coerceAtLeast(0)))
	}

	fun moveBTo(frame: Long, durationFrames: Long, sampleRate: Int): LoopRegion {
		val minSpan = minSpanFrames(durationFrames, sampleRate)
		val minB = (aFrame + minSpan).coerceAtMost(durationFrames)
		return copy(bFrame = frame.coerceIn(minB, durationFrames))
	}

	fun effectiveCrossfadeFrames(sampleRate: Int): Long = minOf(
		crossfadeMs.toLong() * sampleRate.coerceAtLeast(1) / 1_000,
		(bFrame - aFrame) / MAX_CROSSFADE_LOOP_DIVISOR,
	)

	private fun minSpanFrames(durationFrames: Long, sampleRate: Int): Long = minOf(
		durationFrames.coerceAtLeast(1),
		(sampleRate / 100).coerceAtLeast(1).toLong(),
	)

	companion object {
		private const val MAX_CROSSFADE_LOOP_DIVISOR = 4
	}
}

data class SessionSource(
	val uri: String,
	val displayName: String,
	val sizeBytes: Long?,
	val modifiedAtMillis: Long?,
	val durationFrames: Long,
	val sampleRate: Int,
	val channelCount: Int,
	val container: AudioContainer,
) {
	init {
		require(uri.isNotBlank())
		require(durationFrames > 0)
		require(sampleRate > 0)
		require(channelCount in 1..2)
		require(sizeBytes == null || sizeBytes >= 0)
		require(modifiedAtMillis == null || modifiedAtMillis >= 0)
	}

	fun matches(candidate: TrackInfo): SourceMatch {
		if (uri != candidate.uri.toString()) return SourceMatch.Different
		if (durationFrames != candidate.durationFrames || sampleRate != candidate.sampleRate ||
			channelCount != candidate.channelCount || container != candidate.container
		) return SourceMatch.Different
		if (sizeBytes != null && candidate.sourceSizeBytes != null && sizeBytes != candidate.sourceSizeBytes) {
			return SourceMatch.Different
		}
		if (modifiedAtMillis != null && candidate.sourceModifiedAtMillis != null &&
			modifiedAtMillis != candidate.sourceModifiedAtMillis
		) return SourceMatch.Different
		if (sizeBytes != null && candidate.sourceSizeBytes == null ||
			modifiedAtMillis != null && candidate.sourceModifiedAtMillis == null
		) return SourceMatch.Unverified
		return SourceMatch.Same
	}

	companion object {
		fun from(track: TrackInfo) = SessionSource(
			uri = track.uri.toString(), displayName = track.displayName,
			sizeBytes = track.sourceSizeBytes, modifiedAtMillis = track.sourceModifiedAtMillis,
			durationFrames = track.durationFrames, sampleRate = track.sampleRate,
			channelCount = track.channelCount, container = track.container,
		)
	}
}

enum class SourceMatch { Same, Different, Unverified }

data class SessionState(
	val source: SessionSource,
	val positionFrame: Long,
	val playbackRate: PlaybackRate,
	val loop: LoopRegion,
	val viewport: WaveformViewport,
	val updatedAtMillis: Long,
	val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
	init {
		require(positionFrame in 0..source.durationFrames)
		require(loop.bFrame <= source.durationFrames)
		require(viewport.durationFrames == source.durationFrames)
		require(updatedAtMillis >= 0)
		require(schemaVersion == CURRENT_SCHEMA_VERSION)
	}

	companion object {
		const val CURRENT_SCHEMA_VERSION = 1
	}
}

sealed interface SessionLoadOutcome {
	data object Empty : SessionLoadOutcome
	data class Found(val session: SessionState) : SessionLoadOutcome
	data object Corrupt : SessionLoadOutcome
	data object StorageFailure : SessionLoadOutcome
}

sealed interface SessionSaveOutcome {
	data object Saved : SessionSaveOutcome
	data object StorageFailure : SessionSaveOutcome
}

interface SessionRepository {
	suspend fun load(): SessionLoadOutcome
	suspend fun save(session: SessionState): SessionSaveOutcome
}
