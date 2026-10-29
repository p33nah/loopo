package com.p33nah.loopo.data.audio

import com.p33nah.loopo.domain.model.TrackInfo

sealed interface TrackInspection {
	data class Ready(val track: TrackInfo, val hasPersistedAccess: Boolean) : TrackInspection
	data class Failed(val reason: Reason) : TrackInspection

	enum class Reason {
		UNSUPPORTED,
		NO_AUDIO_TRACK,
		INVALID_METADATA,
		READ_FAILED,
	}
}
