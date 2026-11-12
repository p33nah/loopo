package com.p33nah.loopo.ui.voice

import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p33nah.loopo.platform.audio.VoicePhase
import com.p33nah.loopo.ui.theme.LoopA
import com.p33nah.loopo.ui.theme.LoopB
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceEdgeGlowTest {
	@Test
	fun voicePhaseSelectsOnlyTheMatchingMarkerColor() {
		assertEquals(VoiceEdgeCue.Hidden, VoicePhase.Idle.toVoiceEdgeCue())
		assertEquals(VoiceEdgeCue.Hidden, VoicePhase.Applying.toVoiceEdgeCue())
		assertEquals(VoiceEdgeCue.Wake, VoicePhase.WakeListening.toVoiceEdgeCue())
		assertEquals(VoiceEdgeCue.Command, VoicePhase.Listening.toVoiceEdgeCue())
		assertEquals(LoopA.toArgb(), VoiceEdgeCue.Wake.colorArgb())
		assertEquals(LoopB.toArgb(), VoiceEdgeCue.Command.colorArgb())
		assertEquals(0, VoiceEdgeCue.Hidden.colorArgb())
	}
}
