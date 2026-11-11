package com.p33nah.loopo.ui.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.p33nah.loopo.R
import com.p33nah.loopo.platform.audio.PlaybackService
import com.p33nah.loopo.ui.StudyUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class BackgroundVoiceService : Service() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var bound = false
	private val connection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName, binder: IBinder) {
			watchSession(binder as PlaybackService.Session)
		}

		override fun onServiceDisconnected(name: ComponentName) = stopAfterFailure()
		override fun onBindingDied(name: ComponentName) = stopAfterFailure()
		override fun onNullBinding(name: ComponentName) = stopAfterFailure()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		getSystemService(NotificationManager::class.java).createNotificationChannel(
			NotificationChannel(CHANNEL_ID, "Pausa vocale", NotificationManager.IMPORTANCE_LOW),
		)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action != ACTION_ARM) {
			stopSelf(startId)
		} else if (!bound) {
			if (startForegroundVoice()) {
				bound = bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
			}
			if (bound) BackgroundVoiceState.status.value = VoiceStatus.WakeListening else stopAfterFailure()
		}
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		scope.cancel()
		if (bound) unbindService(connection)
		BackgroundVoiceState.status.compareAndSet(VoiceStatus.WakeListening, null)
		stopForeground(STOP_FOREGROUND_REMOVE)
		super.onDestroy()
	}

	private fun watchSession(playback: PlaybackService.Session) {
		val armedTrack = playback.trackRevision
		scope.launch {
			combine(
				playback.study.state.map { state ->
					TrackSnapshot((state as? StudyUiState.Ready)?.inspection?.track?.uri, playback.trackRevision)
				},
				playback.study.playbackState.map { it.isPlaying },
				BackgroundVoiceState.visibility,
				playback.externalPauseVersion,
				::VoiceGate,
			)
				.distinctUntilChanged()
				.collectLatest { gate ->
					val trackUri = gate.track.uri
					if (trackUri == null || gate.track.revision != armedTrack || !gate.playing || !isEnabled() ||
						!hasMicrophonePermission(this@BackgroundVoiceService)
					) {
						stopSelf()
						return@collectLatest
					}
					if (gate.visibility.foreground) return@collectLatest

					fun isCurrent(): Boolean =
						isEnabled() && hasMicrophonePermission(this@BackgroundVoiceService) &&
							BackgroundVoiceState.visibility.value == gate.visibility &&
							playback.trackRevision == gate.track.revision &&
							playback.externalPauseVersion.value == gate.externalPauseVersion &&
							playback.study.playbackState.value.isPlaying &&
							(playback.study.state.value as? StudyUiState.Ready)?.inspection?.track?.uri == trackUri

					val detector = VoskWakeDetector(applicationContext)
					try {
						val coordinator = BackgroundVoiceCoordinator(
							awaitWake = { detector.awaitWake {} },
							pause = { playback.pauseForVoice(::isCurrent) },
						)
						val result = coordinator.runTurn(::isCurrent)
						if (result != VoiceStatus.Cancelled) {
							BackgroundVoiceState.status.value = result
							stopSelf()
						}
					} finally {
						detector.close()
					}
				}
		}
	}

	private fun stopAfterFailure() {
		BackgroundVoiceState.status.value = VoiceStatus.WakeFailed
		scope.cancel()
		stopSelf()
	}

	private fun startForegroundVoice(): Boolean {
		val notification = Notification.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_playback_notification)
			.setContentTitle("Pausa vocale attiva")
			.setContentText("Pronuncia «Ehi Lupo» per mettere in pausa il brano.")
			.setCategory(Notification.CATEGORY_SERVICE)
			.setOngoing(true)
			.build()
		return try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
			} else {
				startForeground(NOTIFICATION_ID, notification)
			}
			true
		} catch (_: RuntimeException) {
			false
		}
	}

	private fun isEnabled(): Boolean = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(ENABLED, false)

	private data class VoiceGate(
		val track: TrackSnapshot,
		val playing: Boolean,
		val visibility: BackgroundVoiceState.Visibility,
		val externalPauseVersion: Long,
	)

	private data class TrackSnapshot(val uri: Uri?, val revision: Long)

	companion object {
		const val ACTION_ARM = "com.p33nah.loopo.debug.voice.ARM"
		const val PREFERENCES = "debug_voice"
		const val ENABLED = "background_pause_enabled"
		private const val CHANNEL_ID = "background_voice"
		private const val NOTIFICATION_ID = 2
	}
}

internal object BackgroundVoiceState {
	data class Visibility(val foreground: Boolean, val revision: Long = 0)

	val visibility = MutableStateFlow(Visibility(foreground = true))
	val status = MutableStateFlow<VoiceStatus?>(null)

	fun setForeground(foreground: Boolean) {
		visibility.update {
			if (it.foreground == foreground) it else Visibility(foreground, it.revision + 1)
		}
	}
}

internal fun armBackgroundVoice(context: Context) {
	val intent = Intent(context, BackgroundVoiceService::class.java).setAction(BackgroundVoiceService.ACTION_ARM)
	try {
		ContextCompat.startForegroundService(context, intent)
	} catch (_: RuntimeException) {
		BackgroundVoiceState.status.value = VoiceStatus.WakeFailed
	}
}

internal fun stopBackgroundVoice(context: Context) {
	context.stopService(Intent(context, BackgroundVoiceService::class.java))
	BackgroundVoiceState.status.value = null
}
