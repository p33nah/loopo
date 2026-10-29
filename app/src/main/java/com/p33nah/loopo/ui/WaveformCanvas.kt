package com.p33nah.loopo.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.p33nah.loopo.domain.audio.PlaybackSnapshot
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.waveform.WaveformData
import com.p33nah.loopo.domain.waveform.WaveformState
import com.p33nah.loopo.domain.waveform.WaveformViewport
import com.p33nah.loopo.ui.theme.Card
import com.p33nah.loopo.ui.theme.Line
import com.p33nah.loopo.ui.theme.LoopA
import com.p33nah.loopo.ui.theme.LoopB
import com.p33nah.loopo.ui.theme.Playhead
import com.p33nah.loopo.ui.theme.Waveform
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@Composable
fun WaveformCanvas(
	track: TrackInfo,
	waveform: WaveformState,
	playback: PlaybackSnapshot,
	loopRegion: LoopRegion,
	viewport: WaveformViewport,
	onViewportChange: (WaveformViewport) -> Unit,
	modifier: Modifier = Modifier,
	onSeekToFrame: (Long) -> Unit,
	onLoopRegionChange: (LoopRegion) -> Unit,
	onLoopRegionChangeFinished: (LoopRegion) -> Unit,
) {
	var playheadDragFrame by remember(track.id) { mutableStateOf<Long?>(null) }
	val currentViewport = rememberUpdatedState(viewport)
	val currentLoop = rememberUpdatedState(loopRegion)
	val currentPlayhead = rememberUpdatedState(playback.positionFrame)
	val updateViewport = rememberUpdatedState(onViewportChange)
	val seek = rememberUpdatedState(onSeekToFrame)
	val updateLoop = rememberUpdatedState(onLoopRegionChange)
	val finishLoop = rememberUpdatedState(onLoopRegionChangeFinished)
	val density = LocalDensity.current
	val handleHitRadius = with(density) { LOOP_HANDLE_HIT_RADIUS_DP.dp.toPx() }
	val markerRadius = with(density) { MARKER_RADIUS_DP.dp.toPx() }
	val markerTextSize = with(density) { MARKER_TEXT_SIZE_DP.dp.toPx() }
	val playheadHeadRadius = with(density) { PLAYHEAD_HEAD_RADIUS_DP.dp.toPx() }
	val markerLabelPaint = remember(markerTextSize) {
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Card.toArgb()
			textAlign = Paint.Align.CENTER
			textSize = markerTextSize
			typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
		}
	}
	val minVisibleFrames = (track.sampleRate / MIN_VISIBLE_SECONDS_DIVISOR).coerceAtLeast(1).toLong()
	val waveformData = when (waveform) {
		WaveformState.Empty -> null
		is WaveformState.Loading -> waveform.preview
		is WaveformState.Ready -> waveform.data
		is WaveformState.Failed -> null
	}

	Canvas(
		modifier = modifier
			.fillMaxSize()
			.background(Card)
			.pointerInput(track.id) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
					val width = size.width.toFloat().coerceAtLeast(1f)
					val gestureViewport = currentViewport.value
					val loop = currentLoop.value
					val playheadFrame = currentPlayhead.value.coerceIn(0, track.durationFrames)
					val candidates = listOfNotNull(
						loopHandleX(loop.aFrame, gestureViewport, track.durationFrames, width)?.let {
							DragHandle(DragTarget.A, Offset(it.coerceIn(markerRadius, width - markerRadius), markerRadius))
						},
						loopHandleX(loop.bFrame, gestureViewport, track.durationFrames, width)?.let {
							DragHandle(DragTarget.B, Offset(it.coerceIn(markerRadius, width - markerRadius), markerRadius))
						},
						loopHandleX(playheadFrame, gestureViewport, track.durationFrames, width)?.let {
							DragHandle(DragTarget.Playhead, Offset(it, playheadHeadRadius))
						},
					)
					val target = candidates
						.filter { (down.position - it.center).getDistance() <= handleHitRadius }
						.minByOrNull { (down.position - it.center).getDistance() }
						?.target
					if (target != null) {
						down.consume()
						val initialLoop = loop
						var latestLoop = loop
						var latestPlayheadFrame: Long? = null
						var completed = false
						if (target == DragTarget.Playhead) playheadDragFrame = playheadFrame
						try {
							while (!completed) {
								val event = awaitPointerEvent(PointerEventPass.Initial)
								val change = event.changes.firstOrNull { it.id == down.id } ?: break
								if (change.changedToUp()) {
									change.consume()
									completed = true
								} else {
									val frame = gestureViewport.frameAt((change.position.x / width).coerceIn(0f, 1f))
									when (target) {
										DragTarget.A -> {
											latestLoop = latestLoop.moveATo(frame, track.durationFrames, track.sampleRate)
											updateLoop.value(latestLoop)
										}
										DragTarget.B -> {
											latestLoop = latestLoop.moveBTo(frame, track.durationFrames, track.sampleRate)
											updateLoop.value(latestLoop)
										}
										DragTarget.Playhead -> {
											latestPlayheadFrame = frame
											playheadDragFrame = frame
										}
									}
									change.consume()
								}
							}
						} finally {
							if (target == DragTarget.Playhead) playheadDragFrame = null
						}
						if (completed) {
							when (target) {
								DragTarget.A, DragTarget.B -> if (latestLoop != initialLoop) finishLoop.value(latestLoop)
								DragTarget.Playhead -> latestPlayheadFrame?.let(seek.value)
							}
						}
					}
				}
			}
			.pointerInput(track.id) {
				detectTransformGestures { centroid, pan, zoom, _ ->
					val width = size.width.toFloat().coerceAtLeast(1f)
					updateViewport.value(currentViewport.value
						.zoomAt(zoom, centroid.x / width, minVisibleFrames)
						.panByPixels(pan.x, width))
				}
			}
			.pointerInput(track.id) {
				detectTapGestures { point ->
					val width = size.width.toFloat().coerceAtLeast(1f)
					val visibleViewport = currentViewport.value
					val loop = currentLoop.value
					val aX = loopHandleX(loop.aFrame, visibleViewport, track.durationFrames, width)
					val bX = loopHandleX(loop.bFrame, visibleViewport, track.durationFrames, width)
					val onLoopHandle = listOfNotNull(aX, bX).any { kotlin.math.abs(point.x - it) <= handleHitRadius }
					if (!onLoopHandle) seek.value(visibleViewport.frameAt(point.x / width))
				}
			},
	) {
		val centerY = size.height / 2f
		drawLine(Line.copy(alpha = 0.55f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 1.dp.toPx())
		waveformData?.let { data ->
			drawPeaks(data, viewport, track, size.width, size.height)
		}
		val loopStartX = loopHandleX(loopRegion.aFrame, viewport, track.durationFrames, size.width)
		val loopEndX = loopHandleX(loopRegion.bFrame, viewport, track.durationFrames, size.width)
		if (loopRegion.enabled) {
			val left = loopStartX ?: 0f
			val right = loopEndX ?: size.width
			if (left > 0f) drawRect(Card.copy(alpha = 0.56f), Offset.Zero, Size(left, size.height))
			if (right < size.width) drawRect(Card.copy(alpha = 0.56f), Offset(right, 0f), Size(size.width - right, size.height))
		}
		loopStartX?.let { x ->
			drawLine(LoopA, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.5.dp.toPx())
			drawCircle(LoopA, radius = markerRadius, center = Offset(x.coerceIn(markerRadius, size.width - markerRadius), markerRadius))
			drawContext.canvas.nativeCanvas.drawText("A", x.coerceIn(markerRadius, size.width - markerRadius), markerRadius + markerLabelPaint.textSize / 3, markerLabelPaint)
		}
		loopEndX?.let { x ->
			drawLine(LoopB, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.5.dp.toPx())
			drawCircle(LoopB, radius = markerRadius, center = Offset(x.coerceIn(markerRadius, size.width - markerRadius), markerRadius))
			drawContext.canvas.nativeCanvas.drawText("B", x.coerceIn(markerRadius, size.width - markerRadius), markerRadius + markerLabelPaint.textSize / 3, markerLabelPaint)
		}
		val playheadFrame = (playheadDragFrame ?: playback.positionFrame).coerceIn(0, track.durationFrames)
		if (playheadFrame in viewport.startFrame..(viewport.startFrame + viewport.visibleFrames)) {
			val playheadX = ((playheadFrame - viewport.startFrame).toFloat() / viewport.visibleFrames) * size.width
			drawLine(Playhead, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2.dp.toPx())
			val head = Path().apply {
				moveTo(playheadX - playheadHeadRadius, 0f)
				lineTo(playheadX + playheadHeadRadius, 0f)
				lineTo(playheadX, playheadHeadRadius * 1.6f)
				close()
			}
			drawPath(head, Playhead)
		}
	}
}

private enum class DragTarget { A, B, Playhead }

private data class DragHandle(val target: DragTarget, val center: Offset)

private fun loopHandleX(frame: Long, viewport: WaveformViewport, durationFrames: Long, width: Float): Float? {
	if (frame !in viewport.startFrame..(viewport.startFrame + viewport.visibleFrames).coerceAtMost(durationFrames)) return null
	return ((frame - viewport.startFrame).toDouble() / viewport.visibleFrames * width).toFloat()
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPeaks(
	data: WaveformData,
	viewport: WaveformViewport,
	track: TrackInfo,
	width: Float,
	height: Float,
) {
	if (width <= 0f || height <= 0f || data.levels.isEmpty()) return
	val framesPerPixel = viewport.visibleFrames.toDouble() / width
	val level = data.levels.lastOrNull { it.bucketFrames <= framesPerPixel } ?: data.levels.first()
	if (level.bucketCount == 0) return
	val startBucket = (viewport.startFrame / level.bucketFrames).toInt().coerceAtLeast(0)
	val endFrame = (viewport.startFrame + viewport.visibleFrames).coerceAtMost(track.durationFrames)
	val endBucket = ceil(endFrame.toDouble() / level.bucketFrames).toInt().coerceAtMost(level.bucketCount)
	if (endBucket <= startBucket) return
	val separateRows = data.channelCount == 2 && height >= TWO_ROW_HEIGHT_DP.dp.toPx()
	val rowCount = if (separateRows) 2 else 1
	val rowHeight = height / rowCount
	val bucketWidth = (level.bucketFrames.toDouble() * width / viewport.visibleFrames).toFloat()
	val strokeWidth = min(MAX_BAR_WIDTH_DP.dp.toPx(), max(MIN_BAR_WIDTH_DP.dp.toPx(), bucketWidth * BAR_WIDTH_RATIO))
	val endCoveredFrame = data.coveredFrames
	for (bucket in startBucket until endBucket) {
		val bucketStart = bucket.toLong() * level.bucketFrames
		if (bucketStart >= endCoveredFrame) break
		val centerFrame = bucketStart + level.bucketFrames / 2
		val x = ((centerFrame - viewport.startFrame).toDouble() / viewport.visibleFrames * width).toFloat()
		if (x < 0f || x > width) continue
		for (row in 0 until rowCount) {
			val low = if (separateRows) {
				level.channels[row].minimum[bucket]
			} else if (data.channelCount == 2) {
				min(level.channels[0].minimum[bucket], level.channels[1].minimum[bucket])
			} else {
				level.channels[0].minimum[bucket]
			}
			val high = if (separateRows) {
				level.channels[row].maximum[bucket]
			} else if (data.channelCount == 2) {
				max(level.channels[0].maximum[bucket], level.channels[1].maximum[bucket])
			} else {
				level.channels[0].maximum[bucket]
			}
			val centerY = rowHeight * (row + 0.5f)
			val amplitude = rowHeight * WAVEFORM_HEIGHT_RATIO
			drawLine(
				color = Waveform,
				start = Offset(x, centerY - high * amplitude),
				end = Offset(x, centerY - low * amplitude),
				strokeWidth = strokeWidth,
			)
		}
	}
}

private const val MIN_VISIBLE_SECONDS_DIVISOR = 4
private const val TWO_ROW_HEIGHT_DP = 144
private const val WAVEFORM_HEIGHT_RATIO = 0.42f
private const val BAR_WIDTH_RATIO = 0.72f
private const val MIN_BAR_WIDTH_DP = 1f
private const val MAX_BAR_WIDTH_DP = 3f
private const val LOOP_HANDLE_HIT_RADIUS_DP = 28
private const val MARKER_RADIUS_DP = 11f
private const val MARKER_TEXT_SIZE_DP = 11f
private const val PLAYHEAD_HEAD_RADIUS_DP = 6f
