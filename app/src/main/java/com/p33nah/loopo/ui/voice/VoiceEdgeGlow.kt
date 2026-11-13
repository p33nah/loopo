package com.p33nah.loopo.ui.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.p33nah.loopo.platform.audio.VoicePhase
import com.p33nah.loopo.ui.theme.LoopA
import com.p33nah.loopo.ui.theme.LoopB
import androidx.compose.ui.graphics.toArgb

internal enum class VoiceEdgeCue {
	Hidden,
	Wake,
	Command,
}

internal fun VoicePhase.toVoiceEdgeCue(): VoiceEdgeCue = when (this) {
	VoicePhase.WakeListening -> VoiceEdgeCue.Wake
	VoicePhase.Listening -> VoiceEdgeCue.Command
	else -> VoiceEdgeCue.Hidden
}

internal fun VoiceEdgeCue.colorArgb(): Int = when (this) {
	VoiceEdgeCue.Wake -> LoopA.toArgb()
	VoiceEdgeCue.Command -> LoopB.toArgb()
	VoiceEdgeCue.Hidden -> 0
}

internal const val FALLBACK_OVERLAY_TOUCH_ALPHA = 0.8f

internal fun overlayTouchAlpha(sdkInt: Int, systemMaximum: Float?): Float =
	if (sdkInt >= Build.VERSION_CODES.S && systemMaximum != null) {
		systemMaximum.coerceIn(0f, 1f)
	} else {
		FALLBACK_OVERLAY_TOUCH_ALPHA
	}

@Composable
internal fun VoiceEdgeGlowInApp(phase: VoicePhase, modifier: Modifier = Modifier) {
	val cue = phase.toVoiceEdgeCue()
	if (cue != VoiceEdgeCue.Hidden) {
		AndroidView(
			factory = { context -> VoiceEdgeGlowView(context).apply { render(cue) } },
			update = { view -> view.render(cue) },
			modifier = modifier.testTag("voice-edge-glow"),
		)
	}
}

internal class VoiceEdgeGlowOverlay(context: Context) {
	private val appContext = context.applicationContext
	private val windowManager = appContext.getSystemService(WindowManager::class.java)
	private val view = VoiceEdgeGlowView(appContext)
	private var phase = VoicePhase.Idle

	fun update(nextPhase: VoicePhase) {
		phase = nextPhase
		val cue = nextPhase.toVoiceEdgeCue()
		if (cue == VoiceEdgeCue.Hidden || !Settings.canDrawOverlays(appContext)) {
			view.render(VoiceEdgeCue.Hidden)
			detach()
			return
		}

		view.render(cue)
		if (!view.isAttachedToWindow) attach()
	}

	fun refreshPermission() = update(phase)

	fun close() {
		phase = VoicePhase.Idle
		view.render(VoiceEdgeCue.Hidden)
		detach()
	}

	private fun attach() {
		try {
			windowManager.addView(view, voiceEdgeOverlayLayoutParams(appContext))
		} catch (_: SecurityException) {
			view.render(VoiceEdgeCue.Hidden)
		} catch (_: WindowManager.BadTokenException) {
			view.render(VoiceEdgeCue.Hidden)
		}
	}

	private fun detach() {
		if (!view.isAttachedToWindow) return
		try {
			windowManager.removeViewImmediate(view)
		} catch (_: IllegalArgumentException) {
			// The system can remove the overlay itself when special access is revoked.
		}
	}
}

internal fun voiceEdgeOverlayLayoutParams(context: Context): WindowManager.LayoutParams {
	val systemMaximum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
	} else {
		null
	}
	return WindowManager.LayoutParams(
		WindowManager.LayoutParams.MATCH_PARENT,
		WindowManager.LayoutParams.MATCH_PARENT,
		WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
		WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
			WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
			WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
		PixelFormat.TRANSLUCENT,
	).apply {
		alpha = overlayTouchAlpha(Build.VERSION.SDK_INT, systemMaximum)
		gravity = Gravity.TOP or Gravity.START
		title = "Loopo voice listening glow"
	}
}

internal class VoiceEdgeGlowView(context: Context) : View(context) {
	private val density = resources.displayMetrics.density
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeJoin = Paint.Join.ROUND
		strokeCap = Paint.Cap.ROUND
	}
	private val path = Path()
	private val bounds = RectF()
	private var cue = VoiceEdgeCue.Hidden

	init {
		isClickable = false
		isFocusable = false
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	fun render(nextCue: VoiceEdgeCue) {
		if (cue == nextCue) return
		cue = nextCue
		invalidate()
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		configurePath(w, h)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (cue == VoiceEdgeCue.Hidden || width <= 0 || height <= 0) return

		val cueColor = cueColor()
		paint.color = cueColor
		paint.alpha = 26
		paint.strokeWidth = 34f * density
		canvas.drawPath(path, paint)

		paint.alpha = 64
		paint.strokeWidth = 26f * density
		canvas.drawPath(path, paint)
		paint.alpha = 100
		paint.strokeWidth = 16f * density
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			paint.setShadowLayer(9f, 0f, 0f, Color.argb(80, Color.red(cueColor), Color.green(cueColor), Color.blue(cueColor)))
		}
		canvas.drawPath(path, paint)
		paint.clearShadowLayer()
		paint.alpha = 152
		paint.strokeWidth = 7f * density
		canvas.drawPath(path, paint)
		paint.alpha = 192
		paint.strokeWidth = 2f * density
		canvas.drawPath(path, paint)
	}

	private fun configurePath(width: Int, height: Int) {
		val inset = 5f * density
		bounds.set(inset, inset, width - inset, height - inset)
		path.reset()
		path.addRoundRect(bounds, 28f * density, 28f * density, Path.Direction.CW)
	}

	private fun cueColor(): Int = cue.colorArgb()
}
