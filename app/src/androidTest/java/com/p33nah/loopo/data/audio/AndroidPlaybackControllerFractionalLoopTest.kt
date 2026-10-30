package com.p33nah.loopo.data.audio

import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.p33nah.loopo.MainActivity
import com.p33nah.loopo.domain.audio.PlaybackSnapshot
import com.p33nah.loopo.domain.model.AudioContainer
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.TrackId
import com.p33nah.loopo.domain.model.TrackInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPlaybackControllerFractionalLoopTest {
	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun oneFrameBeforeCrossfadedLoopEndAtDoubleRateKeepsBufferingAndPlaybackAlive() = runBlocking {
		assertShortLoopPlaybackSurvives(
			name = "fractional-output-crossfaded-loop.wav",
			loop = LoopRegion(aFrame = 24_000, bFrame = 84_000, enabled = true, crossfadeMs = 10),
		)
	}

	@Test
	fun oddLengthZeroFadeLoopAtDoubleRateKeepsBufferingAndPlaybackAlive() = runBlocking {
		assertShortLoopPlaybackSurvives(
			name = "fractional-output-odd-loop.wav",
			loop = LoopRegion(aFrame = 24_001, bFrame = 24_008, enabled = true, crossfadeMs = 0),
		)
	}

	@Test
	fun oneFrameFileLoopAtDoubleRateKeepsBufferingAndPlaybackAlive() = runBlocking {
		assertShortLoopPlaybackSurvives(
			name = "fractional-output-one-frame-file.wav",
			loop = LoopRegion(aFrame = 0, bFrame = 1, enabled = true, crossfadeMs = 0),
			trackFrames = 1,
		)
	}

	@Test
	fun rateChangesAndSeeksWhilePumpingKeepRemaindersValid() = runBlocking {
		val name = "fractional-output-rate-transitions.wav"
		val audioFile = WavFixture.pcmFile(context, name, frames = TRACK_FRAMES)
		val controller = AndroidPlaybackController(context)
		var activity: ActivityScenario<MainActivity>? = null
		val loop = LoopRegion(aFrame = 24_000, bFrame = 84_000, enabled = true, crossfadeMs = 10)
		try {
			activity = ActivityScenario.launch(MainActivity::class.java)
			val track = TrackInfo(
				id = TrackId(name),
				uri = Uri.fromFile(audioFile),
				displayName = name,
				container = AudioContainer.WAV,
				durationFrames = TRACK_FRAMES.toLong(),
				sampleRate = SAMPLE_RATE,
				channelCount = 1,
				sourceMimeType = "audio/wav",
			)
			assertNull("Generated WAV must load", controller.load(track))
			assertTrue("Loop settings must be accepted", controller.setLoopRegion(loop))
			controller.setPlaybackRate(PlaybackRate(199))
			controller.seekToFrame(loop.aFrame + 1_000)
			controller.togglePlayback()
			assertTrue("Playback did not start: ${controller.state.value}", controller.state.value.isPlaying)

			for (ratePercent in listOf(25, 75, 200)) {
				controller.setPlaybackRate(PlaybackRate(ratePercent))
				assertEquals(PlaybackRate(ratePercent), controller.state.value.playbackRate)
				assertTrue("Rate change did not resume playback: ${controller.state.value}", controller.state.value.isPlaying)
				controller.seekToFrame(loop.aFrame + 2_000)
				assertTrue("Seek did not resume playback: ${controller.state.value}", controller.state.value.isPlaying)
				assertHealthy(150L) {
					val snapshot = controller.state.value
					assertNull("Rate transition or seek reported an error: $snapshot", snapshot.error)
					assertFalse("Loop ended during a rate transition: $snapshot", snapshot.isEnded)
					assertTrue("Playback stopped during a rate transition: $snapshot", snapshot.isPlaying)
					assertInLoop(loop, snapshot)
				}
			}
		} finally {
			try {
				controller.unload()
			} finally {
				activity?.close()
				audioFile.delete()
			}
		}
	}

	private suspend fun assertShortLoopPlaybackSurvives(
		name: String,
		loop: LoopRegion,
		trackFrames: Int = TRACK_FRAMES,
	) {
		val audioFile = WavFixture.pcmFile(context, name, frames = trackFrames)
		val controller = AndroidPlaybackController(context)
		var activity: ActivityScenario<MainActivity>? = null
		try {
			activity = ActivityScenario.launch(MainActivity::class.java)
			val track = TrackInfo(
				id = TrackId(name),
				uri = Uri.fromFile(audioFile),
				displayName = name,
				container = AudioContainer.WAV,
				durationFrames = trackFrames.toLong(),
				sampleRate = SAMPLE_RATE,
				channelCount = 1,
				sourceMimeType = "audio/wav",
			)
			assertNull("Generated WAV must load", controller.load(track))
			assertTrue("Loop settings must be accepted", controller.setLoopRegion(loop))
			controller.setPlaybackRate(PlaybackRate(200))
			controller.seekToFrame(loop.bFrame - 1)

			assertEquals(PlaybackRate(200), controller.state.value.playbackRate)
			assertEquals(loop, controller.state.value.loopRegion)
			assertEquals(loop.bFrame - 1, controller.state.value.positionFrame)
			assertHealthy(PAUSED_OBSERVATION_MS) {
				val snapshot = controller.state.value
				assertNull("Paused buffering reported a playback error, including DECODER_READ_FAILED: $snapshot", snapshot.error)
				assertFalse("Paused buffering ended playback: $snapshot", snapshot.isEnded)
				assertInLoop(loop, snapshot)
			}

			controller.togglePlayback()
			assertTrue("Playback did not start: ${controller.state.value}", controller.state.value.isPlaying)
			val observedPositions = mutableSetOf<Long>()
			assertHealthy(PLAYING_OBSERVATION_MS) {
				val snapshot = controller.state.value
				assertNull("Playback reported an error: $snapshot", snapshot.error)
				assertFalse("Playback ended while the loop was enabled: $snapshot", snapshot.isEnded)
				assertTrue("Playback stopped while the loop was enabled: $snapshot", snapshot.isPlaying)
				assertInLoop(loop, snapshot)
				observedPositions += snapshot.positionFrame
			}
			if (loop.bFrame - loop.aFrame >= SAMPLE_RATE) {
				assertTrue("Playback position did not advance during observation: $observedPositions", observedPositions.size > 1)
			}
		} finally {
			try {
				controller.unload()
			} finally {
				activity?.close()
				audioFile.delete()
			}
		}
	}

	private fun assertInLoop(loop: LoopRegion, snapshot: PlaybackSnapshot) {
		assertTrue(
			"Playback position ${snapshot.positionFrame} escaped loop [${loop.aFrame}, ${loop.bFrame})",
			snapshot.positionFrame in loop.aFrame until loop.bFrame,
		)
	}

	private fun assertHealthy(durationMillis: Long, assertSnapshot: () -> Unit) {
		val deadline = SystemClock.uptimeMillis() + durationMillis
		while (SystemClock.uptimeMillis() < deadline) {
			assertSnapshot()
			SystemClock.sleep(10)
		}
		assertSnapshot()
	}

	private companion object {
		const val SAMPLE_RATE = 48_000
		const val TRACK_FRAMES = SAMPLE_RATE * 2
		const val PAUSED_OBSERVATION_MS = 500L
		const val PLAYING_OBSERVATION_MS = 750L
	}
}
