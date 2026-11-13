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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
	content: @Composable (
		cancelVoice: () -> Unit,
		voiceControls: (@Composable () -> Unit)?,
		feedback: VoiceUiState,
		overlayPermissionGranted: Boolean,
	) -> Unit,
) {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val status by session.voiceStatus.collectAsState()
	var permissionRequestCount by remember(session) { mutableStateOf(0) }
	var overlayPermissionGranted by remember(session) { mutableStateOf(Settings.canDrawOverlays(context)) }
	var backgroundGlowUnavailable by remember(session) { mutableStateOf(false) }
	val armVoiceAfterOverlaySettings: () -> Unit = {
		if (isSessionActive()) {
			overlayPermissionGranted = Settings.canDrawOverlays(context)
			backgroundGlowUnavailable = !overlayPermissionGranted
			session.armVoice()
		}
	}
	val overlaySettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		armVoiceAfterOverlaySettings()
	}
	val requestOverlayPermissionThenArm: () -> Unit = {
		if (isSessionActive()) {
			if (Settings.canDrawOverlays(context)) {
				overlayPermissionGranted = true
				backgroundGlowUnavailable = false
				session.armVoice()
			} else {
				val request = Intent(
					Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
					Uri.parse("package:${context.packageName}"),
				)
				runCatching { overlaySettingsLauncher.launch(request) }
					.onFailure { armVoiceAfterOverlaySettings() }
			}
		}
	}
	val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		permissionRequestCount++
		if (!isSessionActive()) return@rememberLauncherForActivityResult
		if (granted) requestOverlayPermissionThenArm() else {
			val message = if (permissionRequestCount >= 2) {
				"Permesso microfono negato. Tocca il lupo per aprire le impostazioni."
			} else {
				"Il microfono è necessario. Tocca il lupo per riprovare."
			}
			session.voicePermissionDenied(message)
		}
	}
	DisposableEffect(lifecycleOwner, session) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				val allowed = Settings.canDrawOverlays(context)
				overlayPermissionGranted = allowed
				if (session.voiceStatus.value.enabled && !allowed) backgroundGlowUnavailable = true
				session.refreshVoiceOverlayPermission()
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
	}
	val controls: (@Composable () -> Unit)? = if (trackUri == null) null else {
		{
			val onClick = {
				when {
					status.enabled -> {
						backgroundGlowUnavailable = false
						session.disarmVoice()
					}
					context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED -> requestOverlayPermissionThenArm()
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
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				VoiceControlButton(status, onClick)
				if (backgroundGlowUnavailable) {
					Text(
						"Glow in background non attivo",
						color = Muted,
						fontSize = 9.sp,
						textAlign = TextAlign.Center,
						modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
					)
				}
			}
		}
	}
	content({ session.cancelVoiceTurn() }, controls, status, overlayPermissionGranted)
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
			painter = painterResource(R.drawable.ic_voice_wolf_microphone),
			contentDescription = null,
			contentScale = ContentScale.Fit,
			colorFilter = ColorFilter.tint(when {
				listening -> LoopA
				status.enabled -> Accent
				else -> Muted
			}),
			modifier = Modifier.size(32.dp).scale(if (status.enabled && status.phase != VoicePhase.Feedback) pulse else 1f),
		)
	}
}
