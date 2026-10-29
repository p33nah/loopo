package com.p33nah.loopo.domain.waveform

import com.p33nah.loopo.domain.model.TrackInfo
import kotlinx.coroutines.flow.Flow

interface WaveformRepository {
	fun observe(track: TrackInfo): Flow<WaveformState>
}
