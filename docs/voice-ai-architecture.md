# Offline voice pilot

With the Italian model packaged and microphone access granted, the debug build automatically listens for **Ehi Lupo** whenever a track is loaded. It uses the same flow in the foreground, background, and with the screen locked, whether playback is running or paused. After the wake phrase, Loopo pauses the track if needed and listens for one Italian command: **riprendi**, **pausa**, or a supported playback speed. This requires Android 12 (API 31) or later and an on-device Italian recognition model.

There is no always-listening switch. Voice listening begins after the user opens a track while Loopo is visible and grants microphone access. PlaybackService becomes a media playback and microphone foreground service and owns the voice session alongside playback. The service remains active while voice commands are armed, including during pause. The release build excludes the voice controller, microphone permissions, and microphone foreground service type.

## Try a command

1. Install a debug APK with the Italian Vosk model, open a track, and grant microphone access.
2. Confirm **In ascolto per Ehi Lupo…** appears while playback is running and while it is paused.
3. Say **Ehi Lupo**, wait for **Ti ascolto…**, then say **riprendi**, **pausa**, or **velocità al settanta**. Speeds accept integer percentages from 25 through 200.
4. Repeat after pressing Home and with the display locked. While paused, **riprendi** starts the loaded track without starting another foreground service.

An unknown command or timeout leaves playback paused, then wake detection resumes. After one command, Loopo returns to wake detection. Manual playback controls and system Pause or Stop cancel the current voice turn. Changing or closing the track ends listening. Permission and model checks happen before microphone capture starts.

## Playback and microphone ownership

PlaybackService owns the voice controller in the debug variant. It starts the voice session only from the visible activity and retains its foreground notification and partial wake lock while listening, even after playback focus is abandoned. The service promotes with `mediaPlayback|microphone` types only after validating the loaded track, packaged model, and microphone permission. Regular playback promotes with only `mediaPlayback`; release contains no microphone declaration.

Vosk captures mono audio at 16 kHz and accepts the exact final text `ehi lupo` from its phrase list. The wake detector releases the microphone before the Android on-device recognizer captures the single command. A mutex prevents overlap between the two capture paths. Recognition requests local `it-IT` speech and adds no network permission. Audio and transcripts remain in memory. The recognizer handles one result with a 12-second limit. The command parser accepts exact phrases and checks speed bounds before submitting a typed playback command. **Comando inviato** means Loopo submitted the request; the player controls show whether it took effect.

The service serializes spoken Play and rate changes through its existing command path. Spoken Play calls the already-running service directly, including after pause, so it does not attempt to start a foreground service from the background. A playback control or system Pause invalidates an in-flight turn; a later wake phrase can start another turn while the session remains armed.

## Later phases

For repeat exercises, use A at the start and B at the end of the track when the user has not set markers. Exact cycle boundaries and optional ChatGPT interpretation are separate work.

- [Italian wake phrase evaluation](https://github.com/p33nah/loopo/issues/45)
- [Android speech recognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [On-device language support](https://developer.android.com/reference/android/speech/RecognitionSupport)
