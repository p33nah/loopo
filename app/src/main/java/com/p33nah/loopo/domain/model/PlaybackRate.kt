package com.p33nah.loopo.domain.model

import java.util.Locale

data class PlaybackRate(val percent: Int) {
	init {
		require(percent in MIN_PERCENT..MAX_PERCENT)
	}

	val factor: Float get() = percent / 100f
	val label: String get() = "%d%% · %.2fx".format(Locale.ROOT, percent, factor)

	companion object {
		const val MIN_PERCENT = 25
		const val MAX_PERCENT = 200
		const val NORMAL_PERCENT = 100

		val NORMAL = PlaybackRate(NORMAL_PERCENT)

		fun fromPercent(percent: Int): PlaybackRate = PlaybackRate(percent)
	}
}
