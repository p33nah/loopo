package com.p33nah.loopo.ui

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
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
import com.p33nah.loopo.platform.audio.PlaybackService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityAudioIntegrationTest {
	@Test
	fun nativePlaybackKeepsAdvancingWhileActivityIsStopped() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val audioFile = WavFixture.pcmFile(context, "background.wav", frames = 480_000)
		val notifications = context.getSystemService(NotificationManager::class.java)
		try {
			ActivityScenario.launch(MainActivity::class.java).use { scenario ->
				val study = awaitPlaybackSession(scenario).study
				scenario.onActivity {
					study.openTrack(Uri.fromFile(audioFile), hasPersistedAccess = false)
				}
				waitFor("background playback fixture to load") { study.state.value is StudyUiState.Ready }
				scenario.onActivity { it.playbackSession!!.togglePlayback() }
				waitFor("native playback start") { study.playbackState.value.isPlaying }

				scenario.moveToState(Lifecycle.State.CREATED)
				val stoppedPosition = study.playbackState.value.positionFrame
				SystemClock.sleep(800)
				val background = study.playbackState.value
				assertTrue("Playback stopped with the activity: $background", background.isPlaying)
				assertTrue("Background playback cursor did not advance: $background", background.positionFrame > stoppedPosition)

				scenario.moveToState(Lifecycle.State.RESUMED)
				scenario.recreate()
				val reboundStudy = awaitPlaybackSession(scenario).study
				assertTrue(reboundStudy.playbackState.value.isPlaying)
				assertTrue(reboundStudy.playbackState.value.positionFrame >= background.positionFrame)

				scenario.moveToState(Lifecycle.State.CREATED)
				val notification = waitForValue("playback notification for the active track") {
					notifications.activeNotifications.map { it.notification }.firstOrNull {
						it.extras.getCharSequence(Notification.EXTRA_TITLE) == audioFile.name
					}
				}
				notification.actions.single().actionIntent.send()
				waitFor("notification pause") { !reboundStudy.playbackState.value.isPlaying }
				waitFor("playback notification removed after pause") { notifications.activeNotifications.isEmpty() }
			}
		} finally {
			context.stopService(Intent(context, PlaybackService::class.java))
			audioFile.delete()
		}
	}

	@Test
	fun removingTheAppTaskStopsPlaybackAndForegroundService() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val audioFile = WavFixture.pcmFile(context, "dismiss-task.wav", frames = 480_000)
		val notifications = context.getSystemService(NotificationManager::class.java)
		val activityManager = context.getSystemService(ActivityManager::class.java)
		try {
			ActivityScenario.launch(MainActivity::class.java).use { scenario ->
				val session = awaitPlaybackSession(scenario)
				val study = session.study
				scenario.onActivity { study.openTrack(Uri.fromFile(audioFile), hasPersistedAccess = false) }
				waitFor("task-dismissal playback fixture to load") { study.state.value is StudyUiState.Ready }
				session.requestPlay()
				waitFor("playback start before task dismissal") { study.playbackState.value.isPlaying }
				waitFor("foreground playback notification") {
					notifications.activeNotifications.any {
						it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == audioFile.name
					}
				}

				val task = activityManager.appTasks.first {
					it.taskInfo?.baseIntent?.component?.className == MainActivity::class.java.name
				}
				task.finishAndRemoveTask()

				waitFor("playback pause after task dismissal") { !study.playbackState.value.isPlaying }
				waitFor(
					"playback service stop after task dismissal",
					diagnostic = {
						val running = activityManager.getRunningServices(Int.MAX_VALUE)
							.filter { it.service.className == PlaybackService::class.java.name }
						val titles = notifications.activeNotifications.map {
							it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
						}
						"runningServices=${running.size}, notificationTitles=$titles"
					},
				) {
					activityManager.getRunningServices(Int.MAX_VALUE).none {
						it.service.className == PlaybackService::class.java.name
					}
				}
				waitFor("playback notification removal after task dismissal") {
					notifications.activeNotifications.none {
						it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == audioFile.name
					}
				}
			}
		} finally {
			context.stopService(Intent(context, PlaybackService::class.java))
			audioFile.delete()
		}
	}

	@Test
	fun voicePauseAcknowledgesNativeOutputAndIsIdempotent() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val audioFile = WavFixture.pcmFile(context, "voice-pause.wav", frames = 480_000)
		try {
			ActivityScenario.launch(MainActivity::class.java).use { scenario ->
				val session = awaitPlaybackSession(scenario)
				val study = session.study
				scenario.onActivity { study.openTrack(Uri.fromFile(audioFile), hasPersistedAccess = false) }
				waitFor("voice pause fixture to load") { study.state.value is StudyUiState.Ready }
				scenario.onActivity { session.requestPlay() }
				waitFor("native playback start") { study.playbackState.value.isPlaying }

				assertFalse(runBlocking { withTimeout(5_000) { session.pauseForVoice { false } } })
				assertTrue("An obsolete voice request must leave playback running", study.playbackState.value.isPlaying)
				repeat(2) {
					assertTrue("Native pause must succeed", runBlocking { withTimeout(5_000) { session.pauseForVoice() } })
					assertFalse(study.playbackState.value.isPlaying)
				}
			}
		} finally {
			context.stopService(Intent(context, PlaybackService::class.java))
			audioFile.delete()
		}
	}

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
			val viewModel = awaitPlaybackSession(scenario).study
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
			scenario.onActivity { it.playbackSession!!.togglePlayback() }
			waitFor("native playback start") { viewModel.playbackState.value.isPlaying }
			scenario.onActivity { it.playbackSession!!.togglePlayback() }
			waitFor("native playback pause") { !viewModel.playbackState.value.isPlaying }
			viewModel.seekToFrame(55_000)
			waitFor("seek position", diagnostic = { viewModel.playbackState.value.toString() }) {
				viewModel.playbackState.value.positionFrame == 55_000L
			}

			scenario.moveToState(Lifecycle.State.CREATED)
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
			val restoredViewModel = awaitPlaybackSession(scenario).study
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
			context.stopService(Intent(context, PlaybackService::class.java))
			audioFile.delete()
		}
	}

	private fun awaitPlaybackSession(scenario: ActivityScenario<MainActivity>): PlaybackService.Session =
		waitForValue("activity to connect to playback service") {
			var session: PlaybackService.Session? = null
			scenario.onActivity { session = it.playbackSession }
			session
		}

	private fun waitFor(description: String, diagnostic: () -> String = { "" }, condition: () -> Boolean) {
		waitForValue(description, diagnostic) { Unit.takeIf { condition() } }
	}

	private fun <T : Any> waitForValue(description: String, diagnostic: () -> String = { "" }, condition: () -> T?): T {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val deadline = SystemClock.uptimeMillis() + 30_000
		while (SystemClock.uptimeMillis() < deadline) {
			instrumentation.waitForIdleSync()
			condition()?.let { return it }
			SystemClock.sleep(20)
		}
		throw AssertionError("Timed out waiting for $description. ${diagnostic()}")
	}
}
