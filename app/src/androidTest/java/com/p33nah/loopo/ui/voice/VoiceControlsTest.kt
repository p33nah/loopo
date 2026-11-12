package com.p33nah.loopo.ui.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p33nah.loopo.platform.audio.VoicePhase
import com.p33nah.loopo.platform.audio.VoiceUiState
import com.p33nah.loopo.ui.theme.LoopoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceControlsTest {
	@get:Rule val compose = createComposeRule()

	@Test
	fun brandButtonExposesOffSetupWakeAndCommandStatesAndCanBeToggled() {
		var status by mutableStateOf(VoiceUiState.Idle)
		var taps = 0
		compose.setContent { LoopoTheme { VoiceControlButton(status) { taps++ } } }
		val button = compose.onNodeWithTag("voice-toggle")

		button.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Attiva i comandi vocali")))
		button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Disattivati"))
		button.performClick()
		assertEquals(1, taps)

		status = VoiceUiState(VoicePhase.DownloadingModel, "Scarico modello 20%", enabled = true)
		button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Scarico modello 20%"))
		button.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Disattiva i comandi vocali")))

		status = VoiceUiState(VoicePhase.WakeListening, "In ascolto per Ehi Lupo…", enabled = true)
		button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In attesa di Ehi Lupo"))
		status = VoiceUiState(VoicePhase.Listening, "Ti ascolto…", enabled = true)
		button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In ascolto del comando"))
		status = VoiceUiState(VoicePhase.Applying, "Applico il comando…", enabled = true)
		button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Applico il comando"))
		button.performClick()
		assertEquals("The same control disarms listening", 2, taps)
	}
}
