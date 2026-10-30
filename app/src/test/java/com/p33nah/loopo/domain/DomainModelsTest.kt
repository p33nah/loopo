package com.p33nah.loopo.domain

import android.net.Uri
import com.p33nah.loopo.domain.model.AudioContainer
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.SessionSource
import com.p33nah.loopo.domain.model.SourceMatch
import com.p33nah.loopo.domain.model.TrackId
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.waveform.WaveformViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DomainModelsTest {
	@Test
	fun playbackRateBoundsAndLabelsRepresentTheSelectedPercent() {
		assertEquals(0.25f, PlaybackRate(25).factor)
		assertEquals("100% · 1.00x", PlaybackRate.NORMAL.label)
		assertEquals(2f, PlaybackRate(200).factor)
		assertThrows(IllegalArgumentException::class.java) { PlaybackRate(24) }
		assertThrows(IllegalArgumentException::class.java) { PlaybackRate(201) }
	}

	@Test
	fun loopRegionMovesKeepTheMinimumSpanInsideTrackAndLimitCrossfade() {
		val loop = LoopRegion(100, 300, enabled = true, crossfadeMs = 80)
		assertEquals(50L, loop.effectiveCrossfadeFrames(sampleRate = 1_000))
		assertEquals(290L, loop.moveATo(299, durationFrames = 1_000, sampleRate = 1_000).aFrame)
		assertEquals(110L, loop.moveBTo(101, durationFrames = 1_000, sampleRate = 1_000).bFrame)
		assertEquals(1L, LoopRegion(0, 4, false, 100).effectiveCrossfadeFrames(1_000))
		assertThrows(IllegalArgumentException::class.java) { LoopRegion(4, 4, true, 0) }
	}

	@Test
	fun sessionSourceMatchesIdentityAndReportsMissingStoredMetadataAsUnverified() {
		val stored = SessionSource(
			uri = "content://audio/item/1",
			displayName = "lesson.wav",
			sizeBytes = 100,
			modifiedAtMillis = 200,
			durationFrames = 1_000,
			sampleRate = 1_000,
			channelCount = 2,
			container = AudioContainer.WAV,
		)
		assertEquals(SourceMatch.Same, stored.matches(track(size = 100, modified = 200)))
		assertEquals(SourceMatch.Different, stored.matches(track(uri = "content://audio/item/2", size = 100, modified = 200)))
		assertEquals(SourceMatch.Different, stored.matches(track(size = 101, modified = 200)))
		assertEquals(SourceMatch.Unverified, stored.matches(track(size = null, modified = null)))
	}

	@Test
	fun viewportZoomKeepsTheAnchorAndPanClampsAtBothTrackEdges() {
		val viewport = WaveformViewport(startFrame = 200, visibleFrames = 400, durationFrames = 1_000)
		val zoomed = viewport.zoomAt(zoomFactor = 2f, anchorFraction = 0.5f, minimumVisibleFrames = 50)
		assertEquals(200L, zoomed.visibleFrames)
		assertEquals(300L, zoomed.startFrame)
		assertEquals(400L, zoomed.frameAt(0.5f))
		assertEquals(800L, zoomed.panByPixels(deltaPixels = -10_000f, widthPixels = 100f).startFrame)
		assertEquals(0L, viewport.panByPixels(deltaPixels = 10_000f, widthPixels = 100f).startFrame)
		assertEquals(viewport, viewport.zoomAt(Float.NaN, 0.5f, 1))
		assertNotEquals(viewport, zoomed)
	}

	private fun track(
		uri: String = "content://audio/item/1",
		size: Long?,
		modified: Long?,
	) = TrackInfo(
		id = TrackId("track"),
		uri = Uri.parse(uri),
		displayName = "lesson.wav",
		container = AudioContainer.WAV,
		durationFrames = 1_000,
		sampleRate = 1_000,
		channelCount = 2,
		sourceMimeType = "audio/wav",
		sourceSizeBytes = size,
		sourceModifiedAtMillis = modified,
	)
}
