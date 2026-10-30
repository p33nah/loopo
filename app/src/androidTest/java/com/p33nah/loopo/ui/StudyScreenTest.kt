package com.p33nah.loopo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p33nah.loopo.data.audio.TrackInspection
import com.p33nah.loopo.domain.audio.PlaybackSnapshot
import com.p33nah.loopo.domain.model.AudioContainer
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.TrackId
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.waveform.WaveformChannelPeaks
import com.p33nah.loopo.domain.waveform.WaveformData
import com.p33nah.loopo.domain.waveform.WaveformLevel
import com.p33nah.loopo.domain.waveform.WaveformState
import com.p33nah.loopo.domain.waveform.WaveformViewport
import com.p33nah.loopo.ui.theme.LoopoTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyScreenTest {
	@get:org.junit.Rule
	val compose = createComposeRule()

	@Test
	fun emptyLoadingErrorAndRecoveryStatesExposeTheirActions() {
		var state by mutableStateOf<StudyUiState>(StudyUiState.Empty)
		var openRequests = 0
		compose.setContent {
			LoopoTheme {
				StudyScreen(
					state = state,
					playback = PlaybackSnapshot(),
					waveform = WaveformState.Empty,
					viewport = null,
					onOpenTrack = { openRequests++ },
					onViewportChange = {},
					onTogglePlayback = {},
					onSeekToFrame = {},
					onSetLoopRegion = {},
					onSetPlaybackRate = {},
				)
			}
		}
		compose.onNodeWithText("Pronto per studiare?").assertIsDisplayed()
		compose.onNodeWithText("Play").assertIsNotEnabled()
		compose.onNodeWithText("Scegli un brano").performClick()
		assertEquals(1, openRequests)

		state = StudyUiState.Loading("prova.wav")
		compose.waitForIdle()
		compose.onNodeWithText("Lettura del brano").assertIsDisplayed()
		compose.onNodeWithText("prova.wav").assertIsDisplayed()

		state = StudyUiState.Error(TrackInspection.Reason.UNSUPPORTED)
		compose.waitForIdle()
		compose.onNodeWithText("Formato non supportato. Scegli un MP3, WAV o FLAC.").assertIsDisplayed()
		compose.onNodeWithText("Scegli un altro file").performClick()
		assertEquals(2, openRequests)

		state = StudyUiState.RecoveryRequired("prova.wav", "Accesso scaduto")
		compose.waitForIdle()
		compose.onNodeWithText("Riprendi lo studio").assertIsDisplayed()
		compose.onNodeWithText("Seleziona di nuovo il brano").performClick()
		assertEquals(3, openRequests)
	}

	@Test
	fun readyTransportLoopSeekAndRateExposeCurrentStateAndCallbacks() {
		val track = TrackInfo(
			id = TrackId("test-track"),
			uri = android.net.Uri.parse("content://test/audio.wav"),
			displayName = "prova.wav",
			container = AudioContainer.WAV,
			durationFrames = 48_000,
			sampleRate = 48_000,
			channelCount = 1,
			sourceMimeType = "audio/wav",
		)
		val defaultLoop = LoopRegion(0, track.durationFrames, enabled = false, crossfadeMs = 20)
		val toggles = mutableListOf<Unit>()
		val seeks = mutableListOf<Long>()
		val loops = mutableListOf<LoopRegion>()
		val rates = mutableListOf<PlaybackRate>()
		compose.setContent {
			LoopoTheme {
				StudyScreen(
					state = StudyUiState.Ready(TrackInspection.Ready(track, true)),
					playback = PlaybackSnapshot(
						positionFrame = 4_800,
						isPlaying = true,
						loopRegion = defaultLoop,
					),
					waveform = WaveformState.Ready(
						WaveformData(
							trackId = track.id,
							durationFrames = track.durationFrames,
							coveredFrames = track.durationFrames,
							sampleRate = track.sampleRate,
							channelCount = 1,
							levels = listOf(WaveformLevel(1, listOf(WaveformChannelPeaks(floatArrayOf(-0.5f), floatArrayOf(0.5f))))),
						),
					),
					viewport = WaveformViewport.full(track.durationFrames),
					onOpenTrack = {},
					onViewportChange = {},
					onTogglePlayback = { toggles += Unit },
					onSeekToFrame = { seeks += it },
					onSetLoopRegion = { loops += it },
					onSetPlaybackRate = { rates += it },
				)
			}
		}

		compose.onNodeWithText("prova.wav").assertIsDisplayed()
		compose.onNodeWithText("100% · 1.00x").assertIsDisplayed()
		compose.onNodeWithText("Pausa").performClick()
		assertEquals(1, toggles.size)
		compose.onNodeWithText("Loop").performClick()
		assertEquals(true, loops.last().enabled)
		compose.onNodeWithText("Loop").assert(
			SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loop attivo"),
		)
		compose.onNodeWithText("A · 0:00.00").performClick()
		assertEquals(4_800L, loops.last().aFrame)

		val sliders = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
		sliders.assertCountEquals(3)
		sliders.get(1).performSemanticsAction(SemanticsActions.SetProgress) { action -> check(action(0.5f)) }
		assertEquals(24_000L, seeks.last())
		sliders.get(2).performSemanticsAction(SemanticsActions.SetProgress) { action -> check(action(73f)) }
		assertEquals(PlaybackRate(73), rates.last())
		compose.onNodeWithText("1x").performClick()
		assertEquals(PlaybackRate.NORMAL, rates.last())
	}
}
