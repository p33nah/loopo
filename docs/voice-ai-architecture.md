# Offline voice pilot

With the Italian model packaged and microphone access granted, the debug build automatically listens for **Ehi Lupo** when a track is open and Loopo is in the foreground. After the phrase, Loopo pauses playback and uses the on-device Android speech recognizer for one Italian command. This requires Android 12 (API 31) or later and an on-device Italian recognition model.

The debug study screen also offers **Pausa vocale a schermo spento**, off by default. When enabled, Loopo arms a separate microphone foreground service while the app is visible and playback is running. Once Loopo goes to the background, the service listens for **Ehi Lupo** and pauses the current track on detection. The notification shows when the service is armed. Turning the option off, pausing playback, ending the track, or changing tracks stops background capture. The release build excludes the voice controls, microphone permissions, and microphone service.

## Try a command

1. Open a track. On first use, grant microphone access in the Android prompt.
2. When **In ascolto per Ehi Lupo…** appears, the wake phrase is ready to use with playback running or paused.
3. Say **Ehi Lupo** and wait for **Ti ascolto…**.
4. Say **riprendi**, **pausa**, or **rallenta al settanta**. To set another speed, say **velocità al** followed by an integer percentage from 25 through 200.

An unknown command, timeout, or recognition error leaves playback paused. **Riprendi** explicitly requests Play. After a command, Loopo returns to wake detection while the voice session remains active. A playback control or system Pause or Stop cancels the current voice turn. Foreground wake detection stops when the app leaves the foreground. Background detection runs only while the explicit debug option is enabled and playback is active.

## Playback and microphone ownership

The activity owns foreground voice commands. Vosk captures mono audio at 16 kHz and accepts the exact final text `ehi lupo` from the tested Italian phrase list. A shared microphone mutex serializes foreground command capture and background wake capture. The wake detector releases the microphone before requesting a pause. The microphone service calls the existing playback session to pause. The pause checks the track generation, playback state, microphone permission, activity visibility, and opt-in.

The app requests local recognition in `it-IT` and adds no network permission. It keeps microphone audio and recognition results in memory for processing. The recognizer handles one result, with a 12-second limit. The command parser accepts exact commands and checks speed bounds before passing a typed command to the player. **Comando inviato** means Loopo submitted the request. The player controls show whether the change took effect.

## Later phases

For repeat exercises, use A at the start and B at the end of the track when the user has not set markers. Exact cycle boundaries and optional ChatGPT interpretation are separate work.

- [Italian wake phrase evaluation](https://github.com/p33nah/loopo/issues/45)
- [Android speech recognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [On-device language support](https://developer.android.com/reference/android/speech/RecognitionSupport)
