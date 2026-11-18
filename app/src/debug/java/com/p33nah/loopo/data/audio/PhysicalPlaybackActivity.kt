package com.p33nah.loopo.data.audio

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import com.p33nah.loopo.domain.model.AudioContainer
import com.p33nah.loopo.domain.model.LoopRegion
import com.p33nah.loopo.domain.model.PlaybackRate
import com.p33nah.loopo.domain.model.TrackId
import com.p33nah.loopo.domain.model.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class PhysicalPlaybackActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val result = TextView(this)
        result.text = "Loopo playback diagnostic"
        setContentView(result)
        CoroutineScope(Dispatchers.Main).launch {
            result.text = runCatching {
                withTimeout(20_000) { verifyPlayback() }
                "PASS"
            }.getOrElse { error ->
                Log.e(TAG, "FAIL", error)
                "FAIL: ${error.message}"
            }
        }
    }

    private suspend fun verifyPlayback() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        Log.i(TAG, "PAGE_SIZE=$pageSize API=${Build.VERSION.SDK_INT} ABI=${Build.SUPPORTED_ABIS.first()}")
        check(pageSize == 16_384L) { "Unexpected page size $pageSize" }

        val audio = WavFixture.pcmFile(this, "physical-playback.wav", frames = 480_000)
        val controller = AndroidPlaybackController(this)
        val loop = LoopRegion(aFrame = 48_000L, bFrame = 96_000L, enabled = true, crossfadeMs = 10)
        try {
            val track = TrackInfo(
                id = TrackId(audio.name),
                uri = Uri.fromFile(audio),
                displayName = audio.name,
                container = AudioContainer.WAV,
                durationFrames = 480_000L,
                sampleRate = 48_000,
                channelCount = 1,
                sourceMimeType = "audio/wav",
            )
            check(controller.load(track) == null)
            controller.play()
            var snap = controller.state.value
            check(snap.isPlaying && snap.error == null) { "Play at 100%: $snap" }
            val initial = snap.positionFrame
            withTimeout(4_000) {
                while (controller.state.value.positionFrame <= initial + 4_800L) delay(25)
            }
            check(controller.pause())
            val paused = controller.state.value
            delay(250)
            check(!paused.isPlaying && controller.state.value.positionFrame == paused.positionFrame) {
                "Position did not stop at pause: $paused -> ${controller.state.value}"
            }
            Log.i(TAG, "PASS play at 100%, frame advanced, then pause held frame ${paused.positionFrame}")

            controller.seekToFrame(loop.aFrame)
            check(controller.state.value.positionFrame == loop.aFrame) { "Seek: ${controller.state.value}" }
            check(controller.setLoopRegion(loop))
            controller.setPlaybackRate(PlaybackRate(73))
            controller.play()
            var previous = controller.state.value.positionFrame
            var wraps = 0
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (wraps < 2 && SystemClock.elapsedRealtime() < deadline) {
                delay(30)
                snap = controller.state.value
                check(snap.isPlaying && !snap.isEnded && snap.error == null && snap.playbackRate.percent == 73) {
                    "Loop at 73%: $snap"
                }
                check(snap.positionFrame in loop.aFrame..loop.bFrame) { "Frame outside A/B: $snap" }
                if (previous - snap.positionFrame > 24_000L) wraps++
                previous = snap.positionFrame
            }
            check(wraps >= 2) { "Observed $wraps loop wraps" }
            check(controller.pause())
            Log.i(TAG, "PASS seek to A, 73% speed, $wraps A/B wraps, then pause")
        } finally {
            controller.unload()
            audio.delete()
        }
    }

    private companion object {
        const val TAG = "LoopoPlaybackCheck"
    }
}
