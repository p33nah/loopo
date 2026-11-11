package com.p33nah.loopo.ui.voice

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.p33nah.loopo.platform.audio.PlaybackService

@Composable
internal fun VoiceStudy(
	session: PlaybackService.Session,
	trackUri: Uri?,
	isSessionActive: () -> Boolean,
	content: @Composable (cancelVoice: () -> Unit, voiceControls: (@Composable () -> Unit)?) -> Unit,
) {
	val context = LocalContext.current
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val voiceStatus = session.voiceStatus
	val status by voiceStatus.collectAsState()
	val modelAvailable = remember(context) { VoskWakeDetector.isModelAvailable(context) }
	var permissionRevision by remember(voiceStatus) { mutableStateOf(0L) }
	var permissionRequested by remember(voiceStatus, trackUri) { mutableStateOf(false) }
	val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		permissionRevision++
		if (granted) permissionRequested = false
	}

	LaunchedEffect(voiceStatus, trackUri, permissionRevision) {
		if (trackUri == null || !modelAvailable) return@LaunchedEffect
		lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			if (!isSessionActive() || !hasOnDeviceRecognizer(context)) return@repeatOnLifecycle
			val preferences = context.getSharedPreferences("debug_voice", Context.MODE_PRIVATE)
			if (hasMicrophonePermission(context)) {
				session.armVoice()
			} else if (!permissionRequested && !preferences.getBoolean("microphone_requested", false)) {
				permissionRequested = true
				preferences.edit().putBoolean("microphone_requested", true).apply()
				permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
			}
		}
	}

	val voiceControls: (@Composable () -> Unit)? = if (trackUri == null) null else {
		{
			Text(
				text = status ?: when {
					!modelAvailable -> "Risveglio offline non disponibile"
					!hasOnDeviceRecognizer(context) -> "Voce offline in italiano non disponibile"
					!hasMicrophonePermission(context) -> "Consenti il microfono per attivare i comandi vocali."
					else -> "Avvio ascolto vocale…"
				},
				style = MaterialTheme.typography.labelMedium,
				modifier = Modifier.widthIn(max = 188.dp).semantics { liveRegion = LiveRegionMode.Polite },
			)
		}
	}
	content({ session.cancelVoiceTurn() }, voiceControls)
}
