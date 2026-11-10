package com.p33nah.loopo.data.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputDeviceSessionTest {
	@Test
	fun knownOutputMatchingAndUnknownBackendFallbackAreGenerationFenced() {
		val current = OutputDeviceSession(generation = 7, deviceId = OutputDeviceId(31))

		assertTrue(current.shouldInterrupt(OutputRemoval(7, setOf(OutputDeviceId(31))), isPlaying = true))
		assertTrue(current.shouldInterrupt(OutputRemoval(7, setOf(OutputDeviceId(12), OutputDeviceId(31))), isPlaying = true))
		assertFalse(current.shouldInterrupt(OutputRemoval(7, setOf(OutputDeviceId(31))), isPlaying = false))
		assertFalse(current.shouldInterrupt(OutputRemoval(7, setOf(OutputDeviceId(12))), isPlaying = true))
		assertFalse(current.shouldInterrupt(OutputRemoval(6, setOf(OutputDeviceId(31))), isPlaying = true))
		assertTrue(OutputDeviceSession(7, null).shouldInterrupt(OutputRemoval(7, setOf(OutputDeviceId(31))), isPlaying = true))
		assertFalse(OutputDeviceSession(6, null).shouldInterrupt(OutputRemoval(7, setOf(OutputDeviceId(31))), isPlaying = true))
		assertFalse(OutputDeviceSession(7, null).shouldInterrupt(OutputRemoval(7, emptySet()), isPlaying = true))
	}
}
