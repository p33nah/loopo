# Offline voice pilot

The debug build has a voice control for one Italian command at a time. It uses the on-device Android speech recognizer on API 31 and later. If the recognizer or Italian model is unavailable, the app shows an error. The release build has no voice control or microphone permission.

## Try a command

1. Open a track in a debug build and tap **Prova voce**.
2. On the first use, grant microphone permission and tap **Prova voce** again.
3. Wait for **Ti ascolto…**, then say **riprendi**, **pausa**, or **rallenta al settanta**. For a different speed, say **velocità al** followed by an integer percentage from 25 through 200.

Use **Annulla voce** or a playback control to end the voice turn. An unknown command, a timeout, or a recognition error leaves the track paused. **Riprendi** explicitly requests Play.

## Playback and microphone ownership

The activity owns the voice turn. The playback service runs the explicit pause and returns the result from the native engine. The coordinator starts recognition only after a successful pause. The service still owns Play, audio focus, and the media notification.

The recognizer handles one result, with a 12-second limit. The activity closes the recognizer when the turn ends or the activity leaves the foreground. Manual controls, a system Pause or Stop, a track change, or a service disconnect invalidate the turn. The controller checks that a queued pause is still current before starting it.

The app requests local recognition in `it-IT` and adds no network permission. It does not save microphone audio or transcripts. The command parser accepts exact commands and checks speed bounds before passing a typed command to the player. **Comando inviato** means Loopo submitted the request. The player controls show whether the change took effect.

## Later phases

**Hey Loop** is the English wake-word candidate. Validate it on the Redmi with music playing before enabling hands-free use. For repeat exercises, use A at the start and B at the end of the track when the user has not set markers. Exact cycle boundaries and optional ChatGPT interpretation are separate work.

- [Android speech recognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [On-device language support](https://developer.android.com/reference/android/speech/RecognitionSupport)
