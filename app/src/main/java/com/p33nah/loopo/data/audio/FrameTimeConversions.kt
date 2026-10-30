package com.p33nah.loopo.data.audio

internal fun framesFromDurationMicros(durationMicros: Long, sampleRate: Int): Long? {
	if (durationMicros < 0 || sampleRate <= 0) return null
	val wholeSeconds = durationMicros / MICROS_PER_SECOND
	val remainingMicros = durationMicros % MICROS_PER_SECOND
	return try {
		val wholeFrames = Math.multiplyExact(wholeSeconds, sampleRate.toLong())
		val fractionalFrames = (remainingMicros * sampleRate + MICROS_PER_SECOND / 2) / MICROS_PER_SECOND
		Math.addExact(wholeFrames, fractionalFrames)
	} catch (_: ArithmeticException) {
		null
	}
}

internal fun microsFromFrame(frame: Long, sampleRate: Int): Long? {
	if (frame < 0 || sampleRate <= 0) return null
	val wholeSeconds = frame / sampleRate
	val remainingFrames = frame % sampleRate
	return try {
		val wholeMicros = Math.multiplyExact(wholeSeconds, MICROS_PER_SECOND)
		val fractionalMicros = remainingFrames * MICROS_PER_SECOND / sampleRate
		Math.addExact(wholeMicros, fractionalMicros)
	} catch (_: ArithmeticException) {
		null
	}
}

private const val MICROS_PER_SECOND = 1_000_000L
