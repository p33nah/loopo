package com.p33nah.loopo.data.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal class AndroidOutputDeviceObserver(
	context: Context,
	private val onOutputRemoved: (OutputRemoval) -> Unit,
) {
	private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
	private val handler = Handler(Looper.getMainLooper())
	private var callback: AudioDeviceCallback? = null

	fun register(generation: Long) {
		unregister()
		val manager = audioManager ?: return
		val observer = object : AudioDeviceCallback() {
			override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
				val ids = removedDevices.asSequence()
					.filter { it.isSink && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
						it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && it.type != AudioDeviceInfo.TYPE_UNKNOWN }
					.map { OutputDeviceId(it.id) }
					.toSet()
				if (ids.isNotEmpty()) onOutputRemoved(OutputRemoval(generation, ids))
			}
		}
		callback = observer
		manager.registerAudioDeviceCallback(observer, handler)
	}

	fun unregister() {
		val observer = callback ?: return
		callback = null
		audioManager?.unregisterAudioDeviceCallback(observer)
	}
}
