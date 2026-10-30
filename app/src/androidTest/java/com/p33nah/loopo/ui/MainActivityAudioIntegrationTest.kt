package com.p33nah.loopo.ui

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.p33nah.loopo.MainActivity
import com.p33nah.loopo.data.audio.WavFixture
import com.p33nah.loopo.data.session.AndroidSessionRepository
import com.p33nah.loopo.data.audio.TrackInspection
import com.p33nah.loopo.domain.model.AudioContainer
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.SessionLoadOutcome
import com.p33nah.loopo.domain.model.SessionSaveOutcome
import com.p33nah.loopo.domain.model.SessionSource
import com.p33nah.loopo.domain.model.SessionState
import com.p33nah.loopo.domain.waveform.WaveformState
import com.p33nah.loopo.domain.waveform.WaveformViewport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityAudioIntegrationTest {
	@Test
	fun viewModelInspectsDecodesAndRestoresGeneratedWaveSessionAcrossLifecycle() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val context = instrumentation.targetContext
		val audioFile = WavFixture.pcmFile(context, "integration.wav")
		val uri = Uri.fromFile(audioFile)
		val durationFrames = 192_000L
		val initialLoop = LoopRegion(12_000, 72_000, enabled = true, crossfadeMs = 20)
		val initialSession = SessionState(
			source = SessionSource(
				uri = uri.toString(),
				displayName = audioFile.name,
				sizeBytes = null,
				modifiedAtMillis = null,
				durationFrames = durationFrames,
				sampleRate = 48_000,
				channelCount = 1,
				container = AudioContainer.WAV,
			),
			positionFrame = 48_000,
			playbackRate = PlaybackRate(125),
			loop = initialLoop,
			viewport = WaveformViewport(24_000, 96_000, durationFrames),
			updatedAtMillis = 100,
		)
		val sessions = AndroidSessionRepository(context)
		assertEquals(SessionSaveOutcome.Saved, runBlocking { sessions.save(initialSession) })

		var scenario = ActivityScenario.launch(MainActivity::class.java)
		try {
			lateinit var viewModel: StudyViewModel
			scenario.onActivity { activity -> viewModel = ViewModelProvider(activity)[StudyViewModel::class.java] }
			waitFor("file access recovery state") { viewModel.state.value is StudyUiState.RecoveryRequired }
			// This exercises the existing ViewModel URI entry point; the system OpenDocument picker is not driven here.
			viewModel.openTrack(uri, hasPersistedAccess = false)
			waitFor("track inspector and decoder to open the WAV") {
				viewModel.state.value is StudyUiState.Ready &&
					viewModel.playbackState.value.playbackRate == PlaybackRate(125) &&
					viewModel.playbackState.value.positionFrame == 48_000L &&
					viewModel.playbackState.value.loopRegion == initialLoop
			}
			val ready = viewModel.state.value as StudyUiState.Ready
			assertEquals(audioFile.name, ready.inspection.track.displayName)
			assertEquals(durationFrames, ready.inspection.track.durationFrames)
			assertFalse(ready.inspection.hasPersistedAccess)
			waitFor("waveform decode to complete") { viewModel.waveformState.value is WaveformState.Ready }
			assertEquals(WaveformViewport(24_000, 96_000, durationFrames), viewModel.viewport.value)

			viewModel.setPlaybackRate(PlaybackRate(150))
			waitFor("updated playback rate") { viewModel.playbackState.value.playbackRate == PlaybackRate(150) }
			viewModel.setLoopRegion(LoopRegion(24_000, 84_000, enabled = true, crossfadeMs = 10))
			waitFor("updated loop region") { viewModel.playbackState.value.loopRegion == LoopRegion(24_000, 84_000, true, 10) }
			viewModel.togglePlayback()
			waitFor("native playback start") { viewModel.playbackState.value.isPlaying }
			viewModel.togglePlayback()
			waitFor("native playback pause") { !viewModel.playbackState.value.isPlaying }
			viewModel.seekToFrame(55_000)
			waitFor("seek position") { viewModel.playbackState.value.positionFrame == 55_000L }

			scenario.moveToState(Lifecycle.State.CREATED)
			waitFor("lifecycle pause") { !viewModel.playbackState.value.isPlaying }
			val stored = waitForValue("session saved on activity stop") {
				(runBlocking { sessions.load() } as? SessionLoadOutcome.Found)?.session?.takeIf {
					it.positionFrame == 55_000L && it.playbackRate == PlaybackRate(150) &&
					it.loop == LoopRegion(24_000, 84_000, true, 10) && it.updatedAtMillis > initialSession.updatedAtMillis
				}
			}
			assertEquals(55_000L, stored.positionFrame)
			assertEquals(initialSession.viewport, stored.viewport)
			scenario.close()

			scenario = ActivityScenario.launch(MainActivity::class.java)
			lateinit var restoredViewModel: StudyViewModel
			scenario.onActivity { activity -> restoredViewModel = ViewModelProvider(activity)[StudyViewModel::class.java] }
			waitFor("restored file access recovery state") { restoredViewModel.state.value is StudyUiState.RecoveryRequired }
			restoredViewModel.openTrack(uri, hasPersistedAccess = false)
			waitFor("persisted playback settings restored") {
				restoredViewModel.state.value is StudyUiState.Ready &&
					restoredViewModel.playbackState.value.positionFrame == 55_000L &&
					restoredViewModel.playbackState.value.playbackRate == PlaybackRate(150) &&
					restoredViewModel.playbackState.value.loopRegion == LoopRegion(24_000, 84_000, true, 10)
			}
			assertFalse(restoredViewModel.playbackState.value.isPlaying)
			assertEquals(initialSession.viewport, restoredViewModel.viewport.value)
		} finally {
			scenario.close()
			audioFile.delete()
		}
	}

	private fun waitFor(description: String, condition: () -> Boolean) {
		waitForValue(description) { Unit.takeIf { condition() } }
	}

	private fun <T : Any> waitForValue(description: String, condition: () -> T?): T {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val deadline = SystemClock.uptimeMillis() + 30_000
		while (SystemClock.uptimeMillis() < deadline) {
			instrumentation.waitForIdleSync()
			condition()?.let { return it }
			SystemClock.sleep(20)
		}
		throw AssertionError("Timed out waiting for $description")
	}
}
