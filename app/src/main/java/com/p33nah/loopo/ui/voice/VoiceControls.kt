package com.p33nah.loopo.ui.voice

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.p33nah.loopo.R
import com.p33nah.loopo.platform.audio.PlaybackService
import com.p33nah.loopo.platform.audio.VoicePhase
import com.p33nah.loopo.platform.audio.VoiceUiState
import com.p33nah.loopo.ui.theme.Accent
import com.p33nah.loopo.ui.theme.LoopA
import com.p33nah.loopo.ui.theme.Muted

@Composable
internal fun VoiceStudy(
	session: PlaybackService.Session,
	trackUri: Uri?,
	isSessionActive: () -> Boolean,
	content: @Composable (cancelVoice: () -> Unit, voiceControls: (@Composable () -> Unit)?, feedback: VoiceUiState) -> Unit,
) {
	val context = LocalContext.current
	val status by session.voiceStatus.collectAsState()
	var permissionRequestCount by remember(session) { mutableStateOf(0) }
	val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		permissionRequestCount++
		if (!isSessionActive()) return@rememberLauncherForActivityResult
		if (granted) session.armVoice() else {
			val message = if (permissionRequestCount >= 2) {
				"Permesso microfono negato. Tocca il lupo per aprire le impostazioni."
			} else {
				"Il microfono è necessario. Tocca il lupo per riprovare."
			}
			session.voicePermissionDenied(message)
		}
	}
	val controls: (@Composable () -> Unit)? = if (trackUri == null) null else {
		{
			val onClick = {
				when {
					status.enabled -> session.disarmVoice()
					context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED -> session.armVoice()
					permissionRequestCount >= 2 -> {
						val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
							.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						runCatching { context.startActivity(settings) }
							.onFailure { session.voicePermissionDenied("Apri le impostazioni dell’app per autorizzare il microfono.") }
						Unit
					}
					else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
				}
			}
			VoiceControlButton(status, onClick)
		}
	}
	content({ session.cancelVoiceTurn() }, controls, status)
}

@Composable
internal fun VoiceControlButton(status: VoiceUiState, onClick: () -> Unit) {
	val listening = status.phase == VoicePhase.Listening
	val transition = rememberInfiniteTransition(label = "voice-arm-pulse")
	val pulse by transition.animateFloat(
		initialValue = 1f,
		targetValue = 1.08f,
		animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
		label = "voice-arm-scale",
	)
	IconButton(
		onClick = onClick,
		modifier = Modifier.testTag("voice-toggle").semantics {
			contentDescription = if (status.enabled) "Disattiva i comandi vocali" else "Attiva i comandi vocali"
			stateDescription = when {
				!status.enabled -> "Disattivati"
				listening -> "In ascolto del comando"
				status.phase == VoicePhase.DownloadingModel -> status.message ?: "Preparazione del modello vocale"
				status.phase == VoicePhase.Pausing -> "Metto in pausa"
				status.phase == VoicePhase.Applying -> "Applico il comando"
				else -> "In attesa di Ehi Lupo"
			}
		},
	) {
		Image(
			painter = painterResource(R.drawable.ic_brand_mark),
			contentDescription = null,
			colorFilter = ColorFilter.tint(when {
				listening -> LoopA
				status.enabled -> Accent
				else -> Muted
			}),
			modifier = Modifier.size(24.dp).scale(if (status.enabled && status.phase != VoicePhase.Feedback) pulse else 1f),
		)
	}
}
