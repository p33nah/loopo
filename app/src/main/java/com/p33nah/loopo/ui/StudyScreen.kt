package com.p33nah.loopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p33nah.loopo.data.audio.TrackInspection
import com.p33nah.loopo.domain.model.TrackInfo
import com.p33nah.loopo.ui.theme.Accent
import com.p33nah.loopo.ui.theme.Background
import com.p33nah.loopo.ui.theme.Card
import com.p33nah.loopo.ui.theme.Line
import com.p33nah.loopo.ui.theme.Muted
import com.p33nah.loopo.ui.theme.TextPrimary

@Composable
fun StudyScreen(state: StudyUiState, onOpenTrack: () -> Unit) {
	Column(
		modifier = Modifier.fillMaxSize().background(Background).padding(horizontal = 28.dp, vertical = 18.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		TopBar(onOpenTrack)
		when (state) {
			StudyUiState.Empty -> EmptyStudy(onOpenTrack, Modifier.weight(1f))
			is StudyUiState.Loading -> LoadingStudy(state.displayName, Modifier.weight(1f))
			is StudyUiState.Ready -> TrackDetails(state.inspection, onOpenTrack, Modifier.weight(1f))
			is StudyUiState.Error -> ErrorStudy(state.reason, onOpenTrack, Modifier.weight(1f))
		}
		TransportBar(state is StudyUiState.Ready)
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
			Surface(shape = CircleShape, color = Accent.copy(alpha = 0.12f), modifier = Modifier.size(64.dp)) {
				Box(contentAlignment = Alignment.Center) {
					Text("♪", color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
				}
			}
			Spacer(Modifier.height(18.dp))
			Text("Pronto per studiare?", color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
			Spacer(Modifier.height(7.dp))
			Text("Apri un file MP3, WAV o FLAC dal tuo dispositivo.", color = Muted, fontSize = 14.sp)
			Spacer(Modifier.height(19.dp))
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
private fun TrackDetails(
	inspection: TrackInspection.Ready,
	onOpenTrack: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val track = inspection.track
	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Surface(color = Card, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
			Column(
				modifier = Modifier.fillMaxSize().padding(22.dp),
				verticalArrangement = Arrangement.Center,
			) {
				Text(track.displayName, color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Spacer(Modifier.height(9.dp))
				Text(track.formatSummary(), color = Muted, fontSize = 13.sp)
				Spacer(Modifier.height(22.dp))
				Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
				Spacer(Modifier.height(18.dp))
				Text("Il motore audio e la forma d'onda verranno aggiunti nelle prossime fasi.", color = Muted, fontSize = 13.sp)
				if (!inspection.hasPersistedAccess) {
					Spacer(Modifier.height(7.dp))
					Text("Il file potrebbe dover essere riaperto dopo la chiusura dell'app.", color = Accent, fontSize = 12.sp)
				}
			}
		}
		OutlinedButton(onClick = onOpenTrack, modifier = Modifier.align(Alignment.End)) { Text("Cambia brano") }
	}
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
private fun TransportBar(trackReady: Boolean) {
	Row(
		modifier = Modifier.fillMaxWidth().height(64.dp).background(Card, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text("A", color = Muted, fontSize = 12.sp)
		Spacer(Modifier.width(11.dp))
		Text("00:00", color = TextPrimary, fontSize = 13.sp)
		Spacer(Modifier.weight(1f))
		Text(if (trackReady) "Audio in preparazione" else "Nessun brano aperto", color = Muted, fontSize = 12.sp)
		Spacer(Modifier.weight(1f))
		Text("1.00x", color = if (trackReady) Accent else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
		Spacer(Modifier.width(10.dp))
		Box(Modifier.size(7.dp).background(if (trackReady) Accent else Line, CircleShape))
	}
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
	TrackInspection.Reason.NO_AUDIO_TRACK -> "Il file non contiene una traccia audio leggibile."
	TrackInspection.Reason.INVALID_METADATA -> "Non riesco a leggere durata o formato del brano."
	TrackInspection.Reason.READ_FAILED -> "Non riesco ad accedere al file. Riaprilo dal selettore."
}
