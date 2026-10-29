package com.p33nah.loopo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.p33nah.loopo.domain.audio.PlaybackSnapshot
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.waveform.WaveformData
import com.p33nah.loopo.domain.waveform.WaveformState
import com.p33nah.loopo.domain.waveform.WaveformViewport
import com.p33nah.loopo.ui.theme.Accent
import com.p33nah.loopo.ui.theme.Card
import com.p33nah.loopo.ui.theme.Line
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@Composable
fun WaveformCanvas(
	track: TrackInfo,
	waveform: WaveformState,
	playback: PlaybackSnapshot,
	modifier: Modifier = Modifier,
	onSeekToFrame: (Long) -> Unit,
) {
	val viewportState = remember(track.id) { mutableStateOf(WaveformViewport.full(track.durationFrames)) }
	val viewport by rememberUpdatedState(viewportState.value)
	val seek by rememberUpdatedState(onSeekToFrame)
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
				detectTransformGestures { centroid, pan, zoom, _ ->
					val width = size.width.toFloat().coerceAtLeast(1f)
					viewportState.value = viewport
						.zoomAt(zoom, centroid.x / width, minVisibleFrames)
						.panByPixels(pan.x, width)
				}
			}
			.pointerInput(track.id) {
				detectTapGestures { point ->
					val width = size.width.toFloat().coerceAtLeast(1f)
					seek(viewport.frameAt(point.x / width))
				}
			},
	) {
		val centerY = size.height / 2f
		drawLine(Line.copy(alpha = 0.55f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 1.dp.toPx())
		waveformData?.let { data ->
			drawPeaks(data, viewport, track, size.width, size.height)
		}
		val playheadFrame = playback.positionFrame.coerceIn(0, track.durationFrames)
		if (playheadFrame in viewport.startFrame..(viewport.startFrame + viewport.visibleFrames)) {
			val playheadX = ((playheadFrame - viewport.startFrame).toFloat() / viewport.visibleFrames) * size.width
			drawLine(Accent, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2.dp.toPx())
			drawCircle(Accent, radius = 5.dp.toPx(), center = Offset(playheadX, 5.dp.toPx()))
		}
	}
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
				color = Accent,
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
