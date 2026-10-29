package com.p33nah.loopo.data.audio

import android.content.Context
import android.net.Uri
import com.p33nah.loopo.domain.audio.PcmDecodeFailure
import com.p33nah.loopo.domain.audio.PcmDecoderResult
import com.p33nah.loopo.domain.model.AudioContainer

class AndroidPcmDecoderFactory(context: Context) {
	private val appContext = context.applicationContext

	fun open(container: AudioContainer, uri: Uri): PcmDecoderResult = when (container) {
		AudioContainer.WAV -> WavPcmDecoder.open(appContext.contentResolver, uri)
		AudioContainer.MP3, AudioContainer.FLAC -> MediaCodecPcmDecoder.open(appContext, uri)
	}
}
