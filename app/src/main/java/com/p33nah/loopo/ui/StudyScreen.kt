package com.p33nah.loopo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p33nah.loopo.R
import com.p33nah.loopo.data.audio.TrackInspection
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.audio.PlaybackError
import com.p33nah.loopo.domain.audio.PlaybackSnapshot
import com.p33nah.loopo.domain.waveform.WaveformViewport
import com.p33nah.loopo.domain.waveform.WaveformPhase
import com.p33nah.loopo.domain.waveform.WaveformState
import com.p33nah.loopo.ui.theme.Accent
import com.p33nah.loopo.ui.theme.Background
import com.p33nah.loopo.ui.theme.Card
import com.p33nah.loopo.ui.theme.Line
import com.p33nah.loopo.ui.theme.LoopA
import com.p33nah.loopo.ui.theme.LoopB
import com.p33nah.loopo.ui.theme.Muted
import com.p33nah.loopo.ui.theme.TextPrimary
import kotlin.math.roundToInt

@Composable
fun StudyScreen(
	state: StudyUiState,
	playback: PlaybackSnapshot,
	waveform: WaveformState,
	viewport: WaveformViewport?,
	onOpenTrack: () -> Unit,
	onViewportChange: (WaveformViewport) -> Unit,
	onTogglePlayback: () -> Unit,
	onSeekToFrame: (Long) -> Unit,
	onSetLoopRegion: (LoopRegion) -> Unit,
	onSetPlaybackRate: (PlaybackRate) -> Unit,
) {
	val readyTrack = (state as? StudyUiState.Ready)?.inspection?.track
	val trackActionLabel = when (state) {
		is StudyUiState.Ready -> "Cambia brano"
		is StudyUiState.Loading -> "Apri brano"
		else -> null
	}
	Column(
		modifier = Modifier.fillMaxSize().background(Background).padding(horizontal = 28.dp, vertical = 12.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		TopBar(readyTrack, trackActionLabel, onOpenTrack)
		when (state) {
			StudyUiState.Empty -> EmptyStudy(onOpenTrack, Modifier.weight(1f))
			is StudyUiState.Loading -> LoadingStudy(state.displayName, Modifier.weight(1f))
			is StudyUiState.RecoveryRequired -> RecoveryStudy(
				state.displayName,
				state.message,
				onOpenTrack,
				Modifier.weight(1f),
			)
			is StudyUiState.Ready -> TrackStudy(
				inspection = state.inspection,
				sessionWarning = state.sessionWarning,
				waveform = waveform,
				playback = playback,
				viewport = viewport,
				onViewportChange = onViewportChange,
				onSeekToFrame = onSeekToFrame,
				onSetLoopRegion = onSetLoopRegion,
				modifier = Modifier.weight(1f),
			)
			is StudyUiState.Error -> ErrorStudy(state.reason, onOpenTrack, Modifier.weight(1f))
		}
		TransportBar(
			track = (state as? StudyUiState.Ready)?.inspection?.track,
			playback = playback,
			onTogglePlayback = onTogglePlayback,
			onSeekToFrame = onSeekToFrame,
			onSetPlaybackRate = onSetPlaybackRate,
		)
	}
}

@Composable
private fun TopBar(track: TrackInfo?, actionLabel: String?, onOpenTrack: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text("LOOP", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
		Image(
			painter = painterResource(R.drawable.ic_brand_mark),
			contentDescription = null,
			modifier = Modifier.size(18.dp),
		)
		Spacer(Modifier.width(12.dp))
		Column(Modifier.weight(1f)) {
			Text(
				track?.displayName ?: "MUSIC PRACTICE",
				color = if (track == null) Muted else TextPrimary,
				fontSize = if (track == null) 10.sp else 15.sp,
				fontWeight = if (track == null) FontWeight.Normal else FontWeight.SemiBold,
				letterSpacing = if (track == null) 1.6.sp else 0.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			track?.let {
				Text(it.formatSummary(), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
		if (actionLabel != null) {
			OutlinedButton(
				onClick = onOpenTrack,
				shape = RoundedCornerShape(12.dp),
				colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
				modifier = Modifier.heightIn(min = 48.dp),
			) {
				Text(actionLabel)
			}
		}
	}
}

@Composable
private fun EmptyStudy(onOpenTrack: () -> Unit, modifier: Modifier = Modifier) {
	Box(
		modifier = modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)),
		contentAlignment = Alignment.Center,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			Surface(shape = CircleShape, color = Accent.copy(alpha = 0.12f), modifier = Modifier.size(52.dp)) {
				Box(contentAlignment = Alignment.Center) {
					Text("♪", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
				}
			}
			Spacer(Modifier.height(8.dp))
			Text("Pronto per studiare?", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
			Spacer(Modifier.height(3.dp))
			Text("Apri un file MP3, WAV o FLAC dal tuo dispositivo.", color = Muted, fontSize = 13.sp)
			Spacer(Modifier.height(6.dp))
			Button(
				onClick = onOpenTrack,
				shape = RoundedCornerShape(13.dp),
				colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
			) {
				Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
				Spacer(Modifier.width(7.dp))
				Text("Scegli un brano", fontWeight = FontWeight.SemiBold)
			}
		}
	}
}

@Composable
private fun LoadingStudy(displayName: String, modifier: Modifier = Modifier) {
	Box(modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
			Spacer(Modifier.height(15.dp))
			Text("Lettura del brano", color = TextPrimary, fontWeight = FontWeight.SemiBold)
			Text(displayName, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

@Composable
private fun RecoveryStudy(displayName: String, message: String, onOpenTrack: () -> Unit, modifier: Modifier = Modifier) {
	Box(modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
		Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
			Text("Riprendi lo studio", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
			Spacer(Modifier.height(8.dp))
			Text(displayName, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(message, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
			Spacer(Modifier.height(12.dp))
			Button(
				onClick = onOpenTrack,
				shape = RoundedCornerShape(13.dp),
				colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
			) {
				Text("Seleziona di nuovo il brano")
			}
		}
	}
}

@Composable
private fun TrackStudy(
	inspection: TrackInspection.Ready,
	sessionWarning: String?,
	waveform: WaveformState,
	playback: PlaybackSnapshot,
	viewport: WaveformViewport?,
	onViewportChange: (WaveformViewport) -> Unit,
	onSeekToFrame: (Long) -> Unit,
	onSetLoopRegion: (LoopRegion) -> Unit,
	modifier: Modifier = Modifier,
) {
	val track = inspection.track
	val defaultLoop = LoopRegion(0, track.durationFrames, enabled = false, crossfadeMs = 20)
	var draftLoop by remember(track.id) { mutableStateOf(playback.loopRegion ?: defaultLoop) }
	LaunchedEffect(track.id, playback.loopRegion, playback.isLoopCommandPending) {
		if (!playback.isLoopCommandPending) draftLoop = playback.loopRegion ?: defaultLoop
	}
	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Surface(color = Card, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
			Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
				WaveformCanvas(
					track = track,
					waveform = waveform,
					playback = playback,
					loopRegion = draftLoop,
					viewport = viewport ?: WaveformViewport.full(track.durationFrames),
					onViewportChange = onViewportChange,
					onLoopRegionChange = { draftLoop = it },
					onLoopRegionChangeFinished = onSetLoopRegion,
					onSeekToFrame = onSeekToFrame,
				)
				when (waveform) {
					WaveformState.Empty -> WaveformStatus("Preparazione waveform…")
					is WaveformState.Loading -> {
						val progress = if (waveform.totalFrames > 0) {
							(waveform.processedFrames.toFloat() / waveform.totalFrames).coerceIn(0f, 1f)
						} else 0f
						if (waveform.preview == null) {
							WaveformStatus(if (waveform.phase == WaveformPhase.VERIFYING_SOURCE) "Verifica file audio…" else "Analisi audio…")
						} else {
							Text("Analisi ${(progress * 100).toInt()}%", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
						}
					}
					is WaveformState.Failed -> WaveformStatus("Waveform non disponibile")
					is WaveformState.Ready -> Unit
				}
			}
		}
		LoopControls(
			track = track,
			region = draftLoop,
			positionFrame = playback.positionFrame,
			effectiveCrossfadeFrames = draftLoop.effectiveCrossfadeFrames(track.sampleRate),
			enabled = !playback.isLoopCommandPending,
			onRegionChange = { draftLoop = it },
			onCommit = onSetLoopRegion,
		)
		if (!inspection.hasPersistedAccess) {
			Text("Il file potrebbe dover essere riaperto dopo la chiusura dell'app.", color = Accent, fontSize = 11.sp)
		}
		sessionWarning?.let {
			Text(it, color = Accent, fontSize = 11.sp)
		}
	}
}

@Composable
private fun LoopControls(
	track: TrackInfo,
	region: LoopRegion,
	positionFrame: Long,
	effectiveCrossfadeFrames: Long,
	enabled: Boolean,
	onRegionChange: (LoopRegion) -> Unit,
	onCommit: (LoopRegion) -> Unit,
) {
	var fadeDraft by remember(track.id) { mutableFloatStateOf(region.crossfadeMs.toFloat()) }
	LaunchedEffect(track.id, region.crossfadeMs) { fadeDraft = region.crossfadeMs.toFloat() }
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		TextButton(
			onClick = {
				val next = region.moveATo(positionFrame, track.durationFrames, track.sampleRate)
				onRegionChange(next)
				onCommit(next)
			},
			enabled = enabled,
			shape = RoundedCornerShape(10.dp),
			colors = ButtonDefaults.textButtonColors(containerColor = Card, contentColor = LoopA),
			contentPadding = PaddingValues(horizontal = 8.dp),
			modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 104.dp),
		) { Text("A · ${region.aFrame.formatLoopTime(track.sampleRate)}", maxLines = 1, softWrap = false) }
		TextButton(
			onClick = {
				val next = region.moveBTo(positionFrame, track.durationFrames, track.sampleRate)
				onRegionChange(next)
				onCommit(next)
			},
			enabled = enabled,
			shape = RoundedCornerShape(10.dp),
			colors = ButtonDefaults.textButtonColors(containerColor = Card, contentColor = LoopB),
			contentPadding = PaddingValues(horizontal = 8.dp),
			modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 104.dp),
		) { Text("B · ${region.bFrame.formatLoopTime(track.sampleRate)}", maxLines = 1, softWrap = false) }
		OutlinedButton(
			onClick = {
				val next = region.copy(enabled = !region.enabled)
				onRegionChange(next)
				onCommit(next)
			},
			enabled = enabled,
			shape = RoundedCornerShape(12.dp),
			colors = ButtonDefaults.outlinedButtonColors(
				containerColor = if (region.enabled) TextPrimary.copy(alpha = 0.08f) else Card,
				contentColor = if (region.enabled) TextPrimary else Muted,
			),
			border = BorderStroke(1.dp, if (region.enabled) TextPrimary.copy(alpha = 0.72f) else Line),
			contentPadding = PaddingValues(horizontal = 9.dp),
			modifier = Modifier.heightIn(min = 48.dp).semantics {
				stateDescription = when {
					!enabled -> "Applicazione loop in corso"
					region.enabled -> "Loop attivo"
					else -> "Loop disattivo"
				}
			},
		) { Text("Loop") }
		Spacer(Modifier.weight(1f))
		val appliedMs = effectiveCrossfadeFrames * 1_000 / track.sampleRate.coerceAtLeast(1)
		Text(
			if (appliedMs < region.crossfadeMs) "${appliedMs} ms" else "Fade ${region.crossfadeMs} ms",
			color = Muted,
			fontSize = 11.sp,
			maxLines = 1,
			modifier = Modifier.widthIn(min = 64.dp),
		)
		Slider(
			value = fadeDraft,
			onValueChange = {
				fadeDraft = it
				onRegionChange(region.copy(crossfadeMs = it.toInt().coerceIn(0, 100)))
			},
			onValueChangeFinished = {
				val next = region.copy(crossfadeMs = fadeDraft.toInt().coerceIn(0, 100))
				onRegionChange(next)
				onCommit(next)
			},
			valueRange = 0f..100f,
			enabled = enabled,
			colors = SliderDefaults.colors(
				thumbColor = Accent,
				activeTrackColor = Accent,
				inactiveTrackColor = Line,
			),
			modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().heightIn(min = 48.dp),
		)
	}
}

@Composable
private fun BoxScope.WaveformStatus(message: String) {
	Text(message, color = Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
}

@Composable
private fun ErrorStudy(reason: TrackInspection.Reason, onOpenTrack: () -> Unit, modifier: Modifier = Modifier) {
	Box(modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			Text(reason.message(), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
			Spacer(Modifier.height(14.dp))
			Button(onClick = onOpenTrack, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)) {
				Text("Scegli un altro file")
			}
		}
	}
}

@Composable
private fun TransportBar(
	track: TrackInfo?,
	playback: PlaybackSnapshot,
	onTogglePlayback: () -> Unit,
	onSeekToFrame: (Long) -> Unit,
	onSetPlaybackRate: (PlaybackRate) -> Unit,
) {
	val duration = track?.durationFrames ?: 0L
	val position = playback.positionFrame.coerceIn(0, duration)
	val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
	var isSeeking by remember(track?.id) { mutableStateOf(false) }
	var seekProgress by remember(track?.id) { mutableFloatStateOf(0f) }
	var rateDraft by remember { mutableFloatStateOf(playback.playbackRate.percent.toFloat()) }
	var isChangingRate by remember { mutableStateOf(false) }
	LaunchedEffect(playback.playbackRate.percent) {
		if (isChangingRate && playback.playbackRate.percent == rateDraft.roundToInt()) {
			isChangingRate = false
		} else if (!isChangingRate) {
			rateDraft = playback.playbackRate.percent.toFloat()
		}
	}
	Column(
		modifier = Modifier.fillMaxWidth().height(136.dp).background(Card, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 5.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(position.formatTime(track?.sampleRate ?: 1), color = TextPrimary, fontSize = 12.sp)
			Slider(
				value = if (isSeeking) seekProgress else progress,
				onValueChange = {
					isSeeking = true
					seekProgress = it
				},
				onValueChangeFinished = {
					if (track != null) onSeekToFrame((seekProgress * duration).toLong())
					isSeeking = false
				},
				enabled = track != null,
				colors = SliderDefaults.colors(
					thumbColor = Accent,
					activeTrackColor = Accent,
					inactiveTrackColor = Line,
				),
				modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
			)
			Text(duration.formatTime(track?.sampleRate ?: 1), color = Muted, fontSize = 12.sp)
			Spacer(Modifier.width(12.dp))
			Button(
				onClick = onTogglePlayback,
				enabled = track != null,
				shape = RoundedCornerShape(12.dp),
				colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
			) {
				Text(if (playback.isPlaying) "Pausa" else if (playback.isEnded) "Ripeti" else "Play", fontWeight = FontWeight.SemiBold)
			}
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Text("Velocità", color = Muted, fontSize = 11.sp)
			Slider(
				value = rateDraft,
				onValueChange = {
					isChangingRate = true
					rateDraft = it
				},
				onValueChangeFinished = {
					val selectedRate = PlaybackRate.fromPercent(
						rateDraft.roundToInt().coerceIn(PlaybackRate.MIN_PERCENT, PlaybackRate.MAX_PERCENT),
					)
					rateDraft = selectedRate.percent.toFloat()
					isChangingRate = selectedRate != playback.playbackRate
					onSetPlaybackRate(
						selectedRate,
					)
				},
				valueRange = PlaybackRate.MIN_PERCENT.toFloat()..PlaybackRate.MAX_PERCENT.toFloat(),
				steps = 0,
				enabled = track != null,
				colors = SliderDefaults.colors(
					thumbColor = Accent,
					activeTrackColor = Accent,
					inactiveTrackColor = Line,
				),
				modifier = Modifier.weight(1f),
			)
			Text(
				PlaybackRate.fromPercent(rateDraft.roundToInt().coerceIn(PlaybackRate.MIN_PERCENT, PlaybackRate.MAX_PERCENT)).label,
				color = TextPrimary,
				fontSize = 12.sp,
				textAlign = TextAlign.End,
			)
			OutlinedButton(
				onClick = {
					isChangingRate = playback.playbackRate != PlaybackRate.NORMAL
					rateDraft = PlaybackRate.NORMAL_PERCENT.toFloat()
					onSetPlaybackRate(PlaybackRate.NORMAL)
				},
				enabled = track != null,
				shape = RoundedCornerShape(12.dp),
			) { Text("1x") }
		}
		val latency = playback.estimatedLatencyMillis?.let { "  ·  stima ${it.toInt()} ms" }.orEmpty()
		val status = playback.error?.message() ?: if (track == null) "Nessun brano aperto" else "dropout ${playback.underrunCount}$latency"
		Text(status, color = if (playback.error == null) Muted else Accent, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
	}
}

private fun Long.formatTime(sampleRate: Int): String {
	val totalSeconds = this / sampleRate.coerceAtLeast(1)
	return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun Long.formatLoopTime(sampleRate: Int): String {
	val totalMillis = this * 1_000 / sampleRate.coerceAtLeast(1)
	val minutes = totalMillis / 60_000
	val seconds = totalMillis / 1_000 % 60
	val centiseconds = totalMillis / 10 % 100
	return "%d:%02d.%02d".format(minutes, seconds, centiseconds)
}

private fun PlaybackError.message(): String = when (this) {
	PlaybackError.OUTPUT_UNAVAILABLE -> "Uscita audio non disponibile"
	PlaybackError.AUDIO_FOCUS_DENIED -> "Audio in pausa: un'altra app sta usando l'audio"
	PlaybackError.DECODER_READ_FAILED -> "Errore durante la decodifica del brano"
}

private fun TrackInfo.formatSummary(): String {
	val totalSeconds = durationFrames / sampleRate
	val minutes = totalSeconds / 60
	val seconds = totalSeconds % 60
	return "%d:%02d  ·  %d Hz  ·  %s  ·  %s".format(
		minutes,
		seconds,
		sampleRate,
		if (channelCount == 1) "Mono" else "Stereo",
		sourceMimeType.substringAfter("audio/", sourceMimeType),
	)
}

private fun TrackInspection.Reason.message(): String = when (this) {
	TrackInspection.Reason.UNSUPPORTED -> "Formato non supportato. Scegli un MP3, WAV o FLAC."
	TrackInspection.Reason.UNSUPPORTED_CHANNEL_LAYOUT -> "Il brano deve essere mono o stereo."
	TrackInspection.Reason.NO_AUDIO_TRACK -> "Il file non contiene una traccia audio leggibile."
	TrackInspection.Reason.INVALID_METADATA -> "Non riesco a leggere durata o formato del brano."
	TrackInspection.Reason.DECODER_UNAVAILABLE -> "Questo dispositivo non riesce a decodificare il formato del brano."
	TrackInspection.Reason.AUDIO_OUTPUT_UNAVAILABLE -> "Non riesco ad aprire l'uscita audio del dispositivo."
	TrackInspection.Reason.ACCESS_DENIED -> "L’accesso al file è scaduto. Riaprilo dal selettore."
	TrackInspection.Reason.READ_FAILED -> "Non riesco ad accedere al file. Riaprilo dal selettore."
}
