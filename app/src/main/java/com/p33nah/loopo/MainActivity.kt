package com.p33nah.loopo

import android.content.pm.ActivityInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p33nah.loopo.platform.audio.NativeBootstrap
import com.p33nah.loopo.platform.audio.PlaybackService
import com.p33nah.loopo.ui.StudyScreen
import com.p33nah.loopo.ui.theme.LoopoTheme

class MainActivity : androidx.activity.ComponentActivity() {
	internal var playbackSession by mutableStateOf<PlaybackService.Session?>(null)
		private set
	private var bound = false
	private val playbackConnection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName, service: IBinder) {
			playbackSession = service as PlaybackService.Session
		}

		override fun onServiceDisconnected(name: ComponentName) {
			playbackSession = null
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
		enterImmersiveMode()
		check(NativeBootstrap.apiVersion() == 1)
		bound = bindService(Intent(this, PlaybackService::class.java), playbackConnection, Context.BIND_AUTO_CREATE)

		setContent {
			val session = playbackSession ?: return@setContent
			val viewModel = session.study
			val context = LocalContext.current
			val state by viewModel.state.collectAsStateWithLifecycle()
			val playback by viewModel.playbackState.collectAsStateWithLifecycle()
			val waveform by viewModel.waveformState.collectAsStateWithLifecycle()
			val viewport by viewModel.viewport.collectAsStateWithLifecycle()
			val openDocument = rememberLauncherForActivityResult(
				contract = ActivityResultContracts.OpenDocument(),
			) { uri ->
				if (uri != null) {
					val persisted = runCatching {
						context.contentResolver.takePersistableUriPermission(
							uri,
							Intent.FLAG_GRANT_READ_URI_PERMISSION,
						)
					}.isSuccess
					viewModel.openTrack(uri, persisted)
				}
			}

			LoopoTheme {
				StudyScreen(
					state = state,
					playback = playback,
					waveform = waveform,
					viewport = viewport,
					onOpenTrack = { openDocument.launch(arrayOf("audio/*", "application/octet-stream")) },
					onViewportChange = viewModel::updateViewport,
					onTogglePlayback = session::togglePlayback,
					onSeekToFrame = viewModel::seekToFrame,
					onSetLoopRegion = viewModel::setLoopRegion,
					onSetPlaybackRate = viewModel::setPlaybackRate,
				)
			}
		}
	}

	override fun onStop() {
		playbackSession?.study?.checkpointSession()
		super.onStop()
	}

	override fun onDestroy() {
		if (bound) unbindService(playbackConnection)
		super.onDestroy()
	}

	private fun enterImmersiveMode() {
		WindowCompat.setDecorFitsSystemWindows(window, false)
		WindowInsetsControllerCompat(window, window.decorView).apply {
			hide(WindowInsetsCompat.Type.systemBars())
			systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
	}
}
