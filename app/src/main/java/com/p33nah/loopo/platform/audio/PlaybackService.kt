package com.p33nah.loopo.platform.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.p33nah.loopo.MainActivity
import com.p33nah.loopo.R
import com.p33nah.loopo.ui.StudyUiState
import com.p33nah.loopo.ui.StudyViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : Service() {
	private val viewModelStore = ViewModelStore()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private lateinit var study: StudyViewModel
	private lateinit var mediaSession: MediaSession
	private lateinit var wakeLock: PowerManager.WakeLock
	private var latestStartId: Int? = null
	private var pendingCommands = 0
	private var taskRemoved = false
	private val voiceController = PlaybackVoiceController(this)

	internal inner class Session : Binder() {
		val study: StudyViewModel get() = this@PlaybackService.study
		val trackRevision: Long get() = study.trackGeneration
		val isTaskRemoved: Boolean get() = taskRemoved
		val voiceStatus: StateFlow<String?> get() = voiceController.status
		fun armVoice() {
			try {
				startService(commandIntent(ACTION_VOICE_ARM))
			} catch (_: RuntimeException) {
				voiceController.setStatus("Ascolto vocale non disponibile")
			}
		}
		fun cancelVoiceTurn() = voiceController.cancelTurn()

		fun togglePlayback() {
			if (study.playbackState.value.isPlaying) {
				runCommand { study.pause() }
			} else {
				requestPlay()
			}
		}

		fun requestPlay() {
			try {
				ContextCompat.startForegroundService(this@PlaybackService, commandIntent(ACTION_PLAY))
			} catch (failure: RuntimeException) {
				reportStartFailure(failure)
			}
		}

		fun requestVoicePlay() {
			runCommand(study::play)
		}

		fun setVoiceRate(rate: com.p33nah.loopo.domain.model.PlaybackRate) {
			runCommand { study.setPlaybackRate(rate) }
		}

		suspend fun pauseForVoice(isCurrent: () -> Boolean = { true }): Boolean = withContext(Dispatchers.Main.immediate) {
			val result = CompletableDeferred<Boolean>()
			val request = currentCoroutineContext()
			val job = runCommand {
				result.complete(study.pause { request.isActive && isCurrent() })
			}
			job.invokeOnCompletion { failure ->
				if (failure != null) result.completeExceptionally(failure)
			}
			result.await()
		}
	}

	override fun onCreate() {
		super.onCreate()
		study = ViewModelProvider.create(
			viewModelStore, ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[StudyViewModel::class]
		wakeLock = getSystemService(PowerManager::class.java)
			.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:playback")
		mediaSession = MediaSession(this, "Loopo").apply {
			setCallback(object : MediaSession.Callback() {
				override fun onPause() = pauseFromSystem()
				override fun onStop() = pauseFromSystem()
			})
		}
		getSystemService(NotificationManager::class.java).createNotificationChannel(
			NotificationChannel(CHANNEL_ID, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW),
		)
		scope.launch {
			study.playbackFocus.collect { held ->
				if (!held) stopWhenIdle()
			}
		}
		scope.launch {
			study.playbackState.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
				if (mediaSession.isActive) publishPlaybackState(playing)
			}
		}
	}

	override fun onBind(intent: Intent): IBinder = Session()

	override fun onTaskRemoved(rootIntent: Intent?) {
		taskRemoved = true
		voiceController.close()
		runCommand { study.pause() }
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		latestStartId = startId
		if (intent?.action == ACTION_PLAY || intent?.action == ACTION_VOICE_ARM) taskRemoved = false
		when (intent?.action) {
			ACTION_PLAY -> {
				if (startForegroundPlayback()) runCommand(study::play)
				else scope.launch { stopWhenIdle() }
			}
			ACTION_VOICE_ARM -> {
				voiceController.arm()
				if (!voiceController.isArmed) scope.launch { stopWhenIdle() }
			}
			ACTION_PAUSE -> pauseFromSystem()
			else -> scope.launch { stopWhenIdle() }
		}
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		scope.cancel()
		voiceController.close()
		if (wakeLock.isHeld) wakeLock.release()
		mediaSession.release()
		viewModelStore.clear()
		super.onDestroy()
	}

	private fun pauseFromSystem() {
		voiceController.cancelTurn()
		runCommand { study.pause() }
	}

	private fun runCommand(command: suspend () -> Unit): Job {
		pendingCommands++
		return scope.launch {
			try {
				command()
			} finally {
				pendingCommands--
				stopWhenIdle()
			}
		}
	}

	private suspend fun stopWhenIdle() {
		val startId = latestStartId ?: return
		if (pendingCommands != 0) return
		if (!taskRemoved && (study.playbackFocus.value || voiceController.isArmed)) return
		if (mediaSession.isActive && !taskRemoved) study.saveSession()
		if (latestStartId != startId || pendingCommands != 0) return
		if (!taskRemoved && (study.playbackFocus.value || voiceController.isArmed)) return
		if (taskRemoved) stopSelf() else if (!stopSelfResult(startId)) return
		latestStartId = null
		mediaSession.isActive = false
		stopForeground(STOP_FOREGROUND_REMOVE)
		if (wakeLock.isHeld) wakeLock.release()
	}

	@SuppressLint("WakelockTimeout")
	private fun startForegroundPlayback(): Boolean {
		val track = (study.state.value as? StudyUiState.Ready)?.inspection?.track
		val title = track?.displayName ?: applicationInfo.loadLabel(packageManager).toString()
		val openApp = PendingIntent.getActivity(
			this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
			PendingIntent.FLAG_IMMUTABLE,
		)
		val pause = PendingIntent.getService(this, 0, commandIntent(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
		mediaSession.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
		mediaSession.setSessionActivity(openApp)
		publishPlaybackState(study.playbackState.value.isPlaying)
		val notification = Notification.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_playback_notification)
			.setContentTitle(title)
			.setContentIntent(openApp)
			.setCategory(Notification.CATEGORY_TRANSPORT)
			.setOnlyAlertOnce(true)
			.setOngoing(true)
			.addAction(Notification.Action.Builder(
				Icon.createWithResource(this, android.R.drawable.ic_media_pause),
				getString(R.string.playback_pause),
				pause,
			).build())
			.setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))
			.build()
		try {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
				val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
					if (voiceController.isArmed && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
						ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
					} else 0
				startForeground(NOTIFICATION_ID, notification, types)
			} else {
				startForeground(NOTIFICATION_ID, notification)
			}
		} catch (failure: RuntimeException) {
			reportStartFailure(failure)
			return false
		}
		if (!wakeLock.isHeld) wakeLock.acquire()
		mediaSession.isActive = true
		return true
	}

	private fun publishPlaybackState(playing: Boolean) {
		mediaSession.setPlaybackState(
			PlaybackState.Builder()
				.setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
				.setState(
					if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
					PlaybackState.PLAYBACK_POSITION_UNKNOWN,
					if (playing) study.playbackState.value.playbackRate.factor else 0f,
				)
				.build(),
		)
	}

	private fun commandIntent(action: String) = Intent(this, PlaybackService::class.java).setAction(action)

	internal fun promoteForVoice(): Boolean = startForegroundPlayback()
	internal fun voiceScope(): CoroutineScope = scope
	internal val voiceSession: Session get() = Session()
	internal val voiceStudy: StudyViewModel get() = study
	internal fun voiceBecameIdle() { scope.launch { stopWhenIdle() } }

	private fun reportStartFailure(failure: RuntimeException) {
		if (failure !is IllegalStateException && failure !is SecurityException) throw failure
		study.reportBackgroundPlaybackUnavailable()
	}

	private companion object {
		const val CHANNEL_ID = "playback"
		const val NOTIFICATION_ID = 1
		const val ACTION_PLAY = "com.p33nah.loopo.action.PLAY"
		const val ACTION_PAUSE = "com.p33nah.loopo.action.PAUSE"
		const val ACTION_VOICE_ARM = "com.p33nah.loopo.debug.voice.ARM"
	}
}
