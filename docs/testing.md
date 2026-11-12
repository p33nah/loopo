# Verify Loopo

Use JDK 17, SDK Platform 37.0, Build Tools 36.0.0, NDK 27.0.12077973, and CMake 3.22.1. The Gradle wrapper pins Gradle 9.9.0 and verifies the distribution checksum. Test dependencies are pinned in `app/build.gradle.kts`. Playback accepts mono or stereo PCM at 8 to 192 kHz and rejects unsupported configurations before native allocation.

Run the Android build, JVM tests, and lint on Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
python scripts/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
```

To package the debug wake detector, download [`vosk-model-small-it-0.22.zip`](https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip). The build verifies SHA-256 `9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267` before packaging the archive. Pass the local archive path when building:

```powershell
.\gradlew.bat assembleDebug '-PloopoWakeModel=C:/path/to/vosk-model-small-it-0.22.zip' --console=plain
python scripts/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
```

For the device check, install the model-enabled debug APK, open a track, and grant microphone access if prompted. Confirm the high-contrast **In ascolto per Ehi Lupo…** appears during playback and pause. Confirm that passive wake detection has no board outline.

Say **Ehi Lupo**. Wait for **Ti ascolto…** and the board outline. Then try **riprendi**, **pausa**, **velocità al settanta**, **aumenta a 80 la velocità**, and **aumenta del 5% la velocità**. Starting at 70%, confirm that the relative form selects 75% and leaves playback paused.

Say **velocità al settanta e poi riprendi**. Confirm that Play starts after the rate transition. Confirm that speed-only commands stay paused. Try an unknown phrase, a recognition failure or timeout, and an out-of-range relative result. Confirm that playback stays paused and that the board shows an error and the recognized text when available. Confirm that the top status returns to passive wake listening and the board result clears after four seconds. A new command or cancellation clears it sooner. The outline must disappear when recognition ends.

Repeat after pressing Home, with the display locked, and while already paused. Confirm **riprendi** starts playback from each state. Confirm that manual controls and notification Pause cancel an active turn. Change tracks during a turn and confirm that the stale turn cannot resume playback. Close the track and confirm that capture stops. Check that the foreground service and wake lock end when voice is no longer armed. Repeat with speaker and Bluetooth where available. Check the release APK for the absence of debug microphone permissions and voice code. A compiled instrumentation APK is not device evidence.

On Linux and macOS, use `bash gradlew` instead of `gradlew.bat`. JVM boundary tests use Robolectric, not a physical device. Robolectric downloads an Android runtime on the first run and cannot prove vendor MediaCodec or Oboe behavior. See the [Robolectric setup](https://robolectric.org/getting-started/) for its JDK access flags.

Run the host C++ checks with a C++20 compiler and CMake:

```sh
cmake -S native-tests -B native-tests/build -DCMAKE_BUILD_TYPE=Debug -DLOOPO_SANITIZER=address
cmake --build native-tests/build --parallel 2
ctest --test-dir native-tests/build --output-on-failure
```

Repeat in a separate build directory with `-DLOOPO_SANITIZER=thread` for SPSC concurrency checks. AddressSanitizer and ThreadSanitizer are separate runs. These tests compile the actual ring and time-stretcher headers without Oboe. They verify sample behavior, not physical output latency or glitch freedom.

Connect a test Android device or start an emulator, then run:

```sh
bash gradlew connectedDebugAndroidTest --console=plain
```

CI runs x86_64 Android emulators at API 26 and API 36. Device integration tests must run the native library as well as the Kotlin code. Generated fixture input through a view-model entry point exercises application integration, but does not exercise the external document-picker UI or persisted provider grants.

Generate the JVM coverage report with `:app:createDebugUnitTestCoverageReport`. Generate the connected-device report with `:app:createDebugAndroidTestCoverageReport`. Reports live under `app/build/reports/coverage/`. [AGP coverage reporting](https://developer.android.com/studio/test/coverage-report) measures Kotlin and Java execution. It does not measure C++ coverage or prove assertions are sufficient. Do not report a combined application coverage percentage from only the JVM report.

Run `python scripts/check_coverage.py app/build/reports/coverage/test/debug/report.xml` to check that pure JVM and Robolectric classes both contribute execution data. The unit-test agent includes classes without a code-source location, restricted to the application package. Without that setting, Robolectric tests can pass while their application classes appear unexecuted in the report. This check validates measurement, not a release-coverage target.

The feature matrix identifies the required evidence for the release:

| Feature | Automated evidence | Additional release evidence |
| --- | --- | --- |
| Playback rate and loop limits | Domain boundaries and exact frame-budget tests across all 176 supported percentages | Controls remain usable at supported display sizes |
| Cached loop seek and crossfade | Exact sample, phase, wrap, channel, and clipping tests | Listen for clicks at multiple loop lengths and rates |
| Time stretching | Native generated-signal pitch, duration, input-only retention, reset, tail, and finite-output tests | MP3, WAV, FLAC listening on target hardware |
| Output queue | Ring capacity, explicit source positions, bounded source-span mapping, wrap, and SPSC tests | Underruns and stable playback under device load |
| WAV decode and seek | Generated encoding and malformed-file tests | Real document providers and large files |
| MediaCodec MP3 and FLAC | Android integration scenarios | Vendor decoder outputs and inaccurate duration metadata |
| Waveform | Pyramid extrema, partial buckets, cache roundtrip and corruption tests | Large file, cancellation, low storage, and cloud provider behavior |
| Session | Source matching and persistence integration tests | Process death, expired grants, changed or deleted file, disk-full recovery |
| Interface | State rendering, callback and application integration tests | Touch seek, pinch, pan, marker drag, accessibility, and small displays |
| Audio lifecycle | Pause, seek, rate transitions, output-device identity, and native playback across activity stop and recreation. The playback notification pauses audio and is removed afterward. The task-removal instrumentation test checks playback, notification, and service shutdown. | Lock the phone during playback and verify the same track continues with the display off. Verify notification Pause and release of the wake lock after playback stops. Remove Loopo from Recents and confirm playback, microphone capture, the notification, the foreground service, and the wake lock stop. Check focus loss, calls, Bluetooth and wired removal. After reconnecting, use Play to resume the retained track. Record route IDs and any unknown-ID fallback. |
| Distribution | Three ABIs, ELF/ZIP alignment, notices, lint | Signed install/update and physical ARM devices |

Use a generated sine or short redistributable recording in mono and stereo at 44.1 and 48 kHz. Include a one-frame or very short WAV, invalid header, truncated payload, unsupported channel layout, and long file. Check playback rates at 25%, 100%, and 200%, plus a fractional value such as 73%. Check loops around the crossfade limit and the two-second cache boundary.

Record each physical-device run with commit, APK SHA-256, Android version, ABI, page size, device model, output route, scenario, and result. Keep document URIs and private recordings out of public logs. A passed emulator run does not replace this matrix.
