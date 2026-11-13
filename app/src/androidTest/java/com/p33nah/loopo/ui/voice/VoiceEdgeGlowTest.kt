package com.p33nah.loopo.ui.voice

import android.hardware.input.InputManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p33nah.loopo.platform.audio.VoicePhase
import com.p33nah.loopo.ui.theme.LoopA
import com.p33nah.loopo.ui.theme.LoopB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

	@Test
	fun overlayWindowAlphaStaysAtOrBelowTheTouchPassThroughLimit() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val systemMaximum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
		} else {
			null
		}
		val params = voiceEdgeOverlayLayoutParams(context)

		assertEquals(overlayTouchAlpha(Build.VERSION.SDK_INT, systemMaximum), params.alpha, 0f)
		assertTrue(params.alpha <= (systemMaximum ?: FALLBACK_OVERLAY_TOUCH_ALPHA))
		assertEquals(0.8f, overlayTouchAlpha(Build.VERSION_CODES.R, null), 0f)
		assertEquals(0.65f, overlayTouchAlpha(Build.VERSION_CODES.S, 0.65f), 0f)
		assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
	}
}
