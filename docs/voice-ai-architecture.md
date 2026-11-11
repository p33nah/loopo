# Offline voice pilot

With the Italian model packaged and microphone access granted, the debug build automatically listens for **Ehi Lupo** when a track is open and Loopo is in the foreground. After the phrase, Loopo pauses playback and uses the on-device Android speech recognizer for one Italian command. This requires Android 12 (API 31) or later and an on-device Italian recognition model. The release build excludes the voice controls, microphone permission, and wake detector.

## Try a command

1. Open a track. On first use, grant microphone access in the Android prompt.
2. When **In ascolto per Ehi Lupo…** appears, the wake phrase is ready to use with playback running or paused.
3. Say **Ehi Lupo** and wait for **Ti ascolto…**.
4. Say **riprendi**, **pausa**, or **rallenta al settanta**. To set another speed, say **velocità al** followed by an integer percentage from 25 through 200.

An unknown command, timeout, or recognition error leaves playback paused. **Riprendi** explicitly requests Play. After a command, Loopo returns to wake detection while the voice session remains active. A playback control or system Pause or Stop cancels the current voice turn. Wake detection restarts for the current foreground track. Leaving the foreground or closing the track stops microphone capture.

## Playback and microphone ownership

The activity owns the voice session. Vosk captures mono audio at 16 kHz and accepts the exact final text `ehi lupo` from the tested Italian phrase list. The wake detector releases the microphone before the activity requests a pause. The playback service returns the native pause result, and the voice coordinator starts command recognition only after a successful pause. The service still owns Play, audio focus, and the media notification.

The app requests local recognition in `it-IT` and adds no network permission. It keeps microphone audio and recognition results in memory for processing. The recognizer handles one result, with a 12-second limit. The command parser accepts exact commands and checks speed bounds before passing a typed command to the player. **Comando inviato** means Loopo submitted the request. The player controls show whether the change took effect.

## Later phases

For repeat exercises, use A at the start and B at the end of the track when the user has not set markers. Exact cycle boundaries and optional ChatGPT interpretation are separate work.

- [Italian wake phrase evaluation](https://github.com/p33nah/loopo/issues/45)
- [Android speech recognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [On-device language support](https://developer.android.com/reference/android/speech/RecognitionSupport)
