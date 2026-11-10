package com.p33nah.loopo.data.audio

@JvmInline
internal value class OutputDeviceId(val value: Int)

internal data class OutputRemoval(
	val generation: Long,
	val deviceIds: Set<OutputDeviceId>,
)

internal data class OutputDeviceSession(
	val generation: Long,
	val deviceId: OutputDeviceId?,
) {
	/** Backends without a device ID pause conservatively on any removed external sink. */
	fun shouldInterrupt(removal: OutputRemoval, isPlaying: Boolean): Boolean =
		isPlaying && generation == removal.generation &&
			(deviceId?.let { it in removal.deviceIds } ?: removal.deviceIds.isNotEmpty())
}
