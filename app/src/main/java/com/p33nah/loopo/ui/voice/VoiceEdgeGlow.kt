package com.p33nah.loopo.ui.voice

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
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
	private var attached = false

	fun update(nextPhase: VoicePhase) {
		phase = nextPhase
		val cue = nextPhase.toVoiceEdgeCue()
		if (cue == VoiceEdgeCue.Hidden || !Settings.canDrawOverlays(appContext)) {
			view.render(VoiceEdgeCue.Hidden)
			detach()
			return
		}

		view.render(cue)
		if (!attached) attach()
	}

	fun refreshPermission() = update(phase)

	fun close() {
		phase = VoicePhase.Idle
		view.render(VoiceEdgeCue.Hidden)
		detach()
	}

	private fun attach() {
		val layout = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.TOP or Gravity.START
			title = "Loopo voice listening glow"
		}
		try {
			windowManager.addView(view, layout)
			attached = true
		} catch (_: SecurityException) {
			view.render(VoiceEdgeCue.Hidden)
		} catch (_: WindowManager.BadTokenException) {
			view.render(VoiceEdgeCue.Hidden)
		}
	}

	private fun detach() {
		if (!attached) return
		try {
			windowManager.removeViewImmediate(view)
		} catch (_: IllegalArgumentException) {
			// The system can remove the overlay itself when special access is revoked.
		} finally {
			attached = false
		}
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
	private var rotation = 0f
	private var displayOn = context.getSystemService(PowerManager::class.java).isInteractive
	private var windowVisible = windowVisibility == VISIBLE
	private var shaderColor = 0
	private var sweepGradient: SweepGradient? = null
	private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
		duration = 2_200L
		repeatCount = ValueAnimator.INFINITE
		addUpdateListener {
			rotation = it.animatedValue as Float
			invalidate()
		}
	}
	private val screenStateReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			displayOn = intent?.action == Intent.ACTION_SCREEN_ON
			syncAnimation()
		}
	}
	private var observingScreenState = false

	init {
		isClickable = false
		isFocusable = false
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	fun render(nextCue: VoiceEdgeCue) {
		if (cue == nextCue) return
		cue = nextCue
		shaderColor = 0
		configureGradient()
		syncAnimation()
		invalidate()
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		ContextCompat.registerReceiver(
			context,
			screenStateReceiver,
			IntentFilter().apply {
				addAction(Intent.ACTION_SCREEN_ON)
				addAction(Intent.ACTION_SCREEN_OFF)
			},
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		observingScreenState = true
		displayOn = context.getSystemService(PowerManager::class.java).isInteractive
		syncAnimation()
	}

	override fun onDetachedFromWindow() {
		if (observingScreenState) {
			context.unregisterReceiver(screenStateReceiver)
			observingScreenState = false
		}
		animator.cancel()
		super.onDetachedFromWindow()
	}

	override fun onWindowVisibilityChanged(visibility: Int) {
		super.onWindowVisibilityChanged(visibility)
		windowVisible = visibility == VISIBLE
		syncAnimation()
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		configurePath(w, h)
		configureGradient()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (cue == VoiceEdgeCue.Hidden || width <= 0 || height <= 0) return

		val cueColor = cueColor()
		paint.shader = null
		paint.color = cueColor
		paint.alpha = 32
		paint.strokeWidth = 34f * density
		canvas.drawPath(path, paint)

		val gradient = sweepGradient ?: return
		canvas.save()
		canvas.rotate(rotation, width / 2f, height / 2f)
		paint.shader = gradient
		paint.alpha = 100
		paint.strokeWidth = 26f * density
		canvas.drawPath(path, paint)
		paint.alpha = 170
		paint.strokeWidth = 12f * density
		canvas.drawPath(path, paint)
		paint.alpha = 255
		paint.strokeWidth = 3f * density
		canvas.drawPath(path, paint)
		paint.shader = null
		canvas.restore()
	}

	private fun configurePath(width: Int, height: Int) {
		val inset = 5f * density
		bounds.set(inset, inset, width - inset, height - inset)
		path.reset()
		path.addRoundRect(bounds, 28f * density, 28f * density, Path.Direction.CW)
	}

	private fun configureGradient() {
		if (width <= 0 || height <= 0 || cue == VoiceEdgeCue.Hidden) {
			sweepGradient = null
			return
		}
		val color = cueColor()
		if (shaderColor == color && sweepGradient != null) return
		shaderColor = color
		sweepGradient = SweepGradient(
			width / 2f,
			height / 2f,
			intArrayOf(
				withAlpha(color, 0f),
				withAlpha(color, 0.05f),
				withAlpha(color, 0.28f),
				withAlpha(color, 0.9f),
				withAlpha(color, 0.28f),
				withAlpha(color, 0.05f),
				withAlpha(color, 0f),
			),
			floatArrayOf(0f, 0.34f, 0.42f, 0.5f, 0.58f, 0.66f, 1f),
		)
	}

	private fun cueColor(): Int = cue.colorArgb()

	private fun withAlpha(color: Int, alpha: Float): Int {
		val colorAlpha = (color ushr 24) and 0xff
		return (color and 0x00ffffff) or ((colorAlpha * alpha).toInt().coerceIn(0, 255) shl 24)
	}

	private fun syncAnimation() {
		if (cue != VoiceEdgeCue.Hidden && displayOn && windowVisible && isAttachedToWindow) {
			if (!animator.isStarted) animator.start()
		} else if (animator.isStarted) {
			animator.cancel()
		}
	}
}
