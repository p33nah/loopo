package com.p33nah.loopo.ui.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceMicrophoneAccessTest {
	@Test
	fun wakeAndCommandCaptureCannotOwnTheMicrophoneAtTheSameTime() = runBlocking {
		val firstEntered = CompletableDeferred<Unit>()
		val releaseFirst = CompletableDeferred<Unit>()
		val events = mutableListOf<String>()
		val wake = async {
			VoiceMicrophoneAccess.mutex.withLock {
				events += "wake-start"
				firstEntered.complete(Unit)
				releaseFirst.await()
				events += "wake-close"
			}
		}
		firstEntered.await()
		val command = launch {
			VoiceMicrophoneAccess.mutex.withLock {
				events += "command-start"
				events += "command-close"
			}
		}
		yield()
		assertEquals(listOf("wake-start"), events)
		releaseFirst.complete(Unit)
		wake.await()
		command.join()
		assertEquals(listOf("wake-start", "wake-close", "command-start", "command-close"), events)
	}
}
