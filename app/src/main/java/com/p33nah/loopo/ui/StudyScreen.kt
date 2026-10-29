package com.p33nah.loopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p33nah.loopo.data.audio.TrackInspection
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.domain.audio.PlaybackError
import com.p33nah.loopo.domain.audio.PlaybackSnapshot
import com.p33nah.loopo.domain.waveform.WaveformPhase
import com.p33nah.loopo.domain.waveform.WaveformState
import com.p33nah.loopo.ui.theme.Accent
import com.p33nah.loopo.ui.theme.Background
import com.p33nah.loopo.ui.theme.Card
import com.p33nah.loopo.ui.theme.Line
import com.p33nah.loopo.ui.theme.Muted
import com.p33nah.loopo.ui.theme.TextPrimary

@Composable
fun StudyScreen(
	state: StudyUiState,
	playback: PlaybackSnapshot,
	waveform: WaveformState,
	onOpenTrack: () -> Unit,
	onTogglePlayback: () -> Unit,
	onSeekToFrame: (Long) -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxSize().background(Background).padding(horizontal = 28.dp, vertical = 18.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		TopBar(onOpenTrack)
		when (state) {
			StudyUiState.Empty -> EmptyStudy(onOpenTrack, Modifier.weight(1f))
			is StudyUiState.Loading -> LoadingStudy(state.displayName, Modifier.weight(1f))
			is StudyUiState.Ready -> TrackStudy(
				inspection = state.inspection,
				waveform = waveform,
				playback = playback,
				onOpenTrack = onOpenTrack,
				onSeekToFrame = onSeekToFrame,
				modifier = Modifier.weight(1f),
			)
			is StudyUiState.Error -> ErrorStudy(state.reason, onOpenTrack, Modifier.weight(1f))
		}
		TransportBar(
			track = (state as? StudyUiState.Ready)?.inspection?.track,
			playback = playback,
			onTogglePlayback = onTogglePlayback,
			onSeekToFrame = onSeekToFrame,
		)
	}
}

@Composable
private fun TopBar(onOpenTrack: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().height(44.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text("LOOPO", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
		Spacer(Modifier.width(14.dp))
		Text("MUSIC PRACTICE", color = Muted, fontSize = 10.sp, letterSpacing = 1.6.sp)
		Spacer(Modifier.weight(1f))
		OutlinedButton(
			onClick = onOpenTrack,
			shape = RoundedCornerShape(12.dp),
			colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
		) {
			Text("Apri brano")
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
			Spacer(Modifier.height(12.dp))
			Text("Pronto per studiare?", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
			Spacer(Modifier.height(6.dp))
			Text("Apri un file MP3, WAV o FLAC dal tuo dispositivo.", color = Muted, fontSize = 13.sp)
			Spacer(Modifier.height(10.dp))
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
private fun TrackStudy(
	inspection: TrackInspection.Ready,
	waveform: WaveformState,
	playback: PlaybackSnapshot,
	onOpenTrack: () -> Unit,
	onSeekToFrame: (Long) -> Unit,
	modifier: Modifier = Modifier,
) {
	val track = inspection.track
	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(modifier = Modifier.weight(1f)) {
				Text(track.displayName, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(track.formatSummary(), color = Muted, fontSize = 12.sp)
			}
			OutlinedButton(onClick = onOpenTrack) { Text("Cambia brano") }
		}
		Surface(color = Card, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
			Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
				WaveformCanvas(
					track = track,
					waveform = waveform,
					playback = playback,
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
		if (!inspection.hasPersistedAccess) {
			Text("Il file potrebbe dover essere riaperto dopo la chiusura dell'app.", color = Accent, fontSize = 11.sp)
		}
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
) {
	val duration = track?.durationFrames ?: 0L
	val position = playback.positionFrame.coerceIn(0, duration)
	val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
	var isSeeking by remember(track?.id) { mutableStateOf(false) }
	var seekProgress by remember(track?.id) { mutableFloatStateOf(0f) }
	Column(
		modifier = Modifier.fillMaxWidth().height(84.dp).background(Card, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.Center,
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
		val latency = playback.estimatedLatencyMillis?.let { "  ·  stima ${it.toInt()} ms" }.orEmpty()
		val status = playback.error?.message() ?: if (track == null) "Nessun brano aperto" else "1.00x  ·  dropout ${playback.underrunCount}$latency"
		Text(status, color = if (playback.error == null) Muted else Accent, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
	}
}

private fun Long.formatTime(sampleRate: Int): String {
	val totalSeconds = this / sampleRate.coerceAtLeast(1)
	return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
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
	TrackInspection.Reason.READ_FAILED -> "Non riesco ad accedere al file. Riaprilo dal selettore."
}
