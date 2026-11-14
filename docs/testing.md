# Verify Loopo

Use JDK 17, SDK Platform 37.0, Build Tools 36.0.0, NDK 27.0.12077973, and CMake 3.22.1. The Gradle wrapper pins Gradle 9.9.0 and verifies the distribution checksum. Test dependencies are pinned in `app/build.gradle.kts`. Playback accepts mono or stereo PCM at 8 to 192 kHz and rejects unsupported configurations before native allocation.

Run JVM tests, lint, debug and release packaging, and instrumentation APK compilation on Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest --console=plain
python scripts/verify_apk.py app/build/outputs/apk/debug/app-debug.apk --allow-model
python scripts/verify_apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

The release APK contains the Vosk runtime but must not contain `loopo-vosk-it-0.22.zip`. The debug build can bundle the official [`vosk-model-small-it-0.22.zip`](https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip) for testing; Gradle verifies SHA-256 `9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267`:

```powershell
.\gradlew.bat assembleDebug '-PloopoWakeModel=C:/path/to/vosk-model-small-it-0.22.zip' --console=plain
python scripts/verify_apk.py app/build/outputs/apk/debug/app-debug.apk --allow-model
```

The acoustic fixture strategy uses live, consenting Italian speakers on physical devices rather than storing voice recordings in the repository. Record only the prompt, device/room condition, transcript shown by Loopo, resulting rate/play state, and pass/fail; do not retain raw audio. At minimum, repeat **Ehi Lupo** three times, then test **riprendi**, **pausa**, **aumenta la velocità a ottanta**, **aumenta del cinque per cento la velocità**, **rallenta al settanta e poi riprendi**, an unknown phrase, and an out-of-range request. Repeat in quiet and ordinary room noise, at normal speaking distance and with a second speaker when possible. This is manual acoustic evidence; parser and action-plan cases remain deterministic automated tests.

For a debug device check, install the model-enabled APK, open a track, and confirm voice is off without a permission prompt. Tap the wolf-and-microphone mark and grant microphone access. When Android opens **Display over other apps**, grant access to test background glow. Verify the toggle animation and passive **In ascolto per Ehi Lupo…** state. Confirm that wake listening shows the marker A glow without the board outline. With the release APK, verify first-activation download progress, cancel, retry, successful install, then enable voice again in airplane mode to confirm cached offline use. Check insufficient storage and invalid/missing model errors if available.

On the study screen, confirm the Fade slider, dropout count, and latency estimate are not visible. Confirm loop A/B, seek, rate controls, and playback error messages remain available. This is a temporary presentation change; the fade state and diagnostics remain active internally.

Say **Ehi Lupo**. Wait for **Ti ascolto…**, the marker B edge glow, and the board outline. Then try **riprendi**, **pausa**, **velocità al settanta**, **aumenta a 80 la velocità**, **aumenta la velocità a 80**, **aumenta del 5% la velocità**, and **aumenta la velocità del 5%**. Starting at 70%, confirm that either relative word order selects 75% and leaves playback paused. Starting at 75%, confirm that either absolute word order selects 80% and leaves playback paused. Note the exact recognized transcript in the feedback if a command is rejected.

Say **velocità al settanta e poi riprendi**. Confirm that Play starts after the rate transition. Confirm that speed-only commands stay paused. Try an unknown phrase, a recognition failure or timeout, and an out-of-range relative result. Confirm that playback stays paused and that the board shows an error and the recognized text when available. Confirm that the top status returns to passive wake listening and the board result clears after four seconds. A new command or cancellation clears it sooner. The outline must disappear when recognition ends.

Repeat after pressing Home with overlay access granted and the display awake. During wake and command capture, confirm that the A and B edge glows appear over another app and do not intercept its touch input. Revoke overlay access, return to Loopo, and confirm that voice continues with an in-app glow and the background-access notice. Repeat with the display locked and awake; record whether the device keyguard covers the glow. Do not bypass the lock screen. Turn the display off and confirm Loopo does not wake or keep it on; no screen-off glow is expected. Confirm **riprendi** starts playback from each state. Confirm that manual controls and notification Pause cancel an active turn. Change tracks during a turn and confirm that the stale turn cannot resume playback. Close the track and confirm that capture stops. Toggle voice off and confirm the microphone indicator and foreground service stop when playback is paused. Repeat with speaker and Bluetooth where available. Voice permission and Vosk code are expected in release; only the large model archive is excluded. A compiled instrumentation APK is not device evidence.

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

## Android 15 16 KB runtime

APK alignment and runtime behavior are separate checks. Android's [16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes) describes the runtime requirement and the Android 15 x86_64 `google_apis_ps16k` emulator image. Use this image to exercise native loading and app behavior under a 16 KB kernel; a virtual audio HAL failure means it cannot verify audible output.

Install the emulator and image, create an AVD, and start it:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH = "$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:PATH"
sdkmanager.bat "emulator" "system-images;android-35;google_apis_ps16k;x86_64"
avdmanager.bat create avd --name Loopo16kApi35 --device pixel_6 --package "system-images;android-35;google_apis_ps16k;x86_64"
emulator.exe -avd Loopo16kApi35 -no-window -no-boot-anim -no-snapshot -gpu swiftshader_indirect
```

With the emulator booted, select only that device when other ADB devices are connected, then record its runtime identity before installing/running tests:

```powershell
$env:ANDROID_SERIAL = "emulator-5554"
adb -s $env:ANDROID_SERIAL shell getconf PAGE_SIZE
adb -s $env:ANDROID_SERIAL shell getprop ro.build.version.release
adb -s $env:ANDROID_SERIAL shell getprop ro.build.version.sdk
adb -s $env:ANDROID_SERIAL shell getprop ro.product.cpu.abi
adb -s $env:ANDROID_SERIAL shell getprop ro.build.fingerprint
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

`getconf PAGE_SIZE` must print `16384`. Replace `emulator-5554` with the actual ADB serial. The device selector matters: without `ANDROID_SERIAL`, Gradle runs connected tests on every available device. For an isolated instrumentation case, pass its fully qualified class and method:

```powershell
.\gradlew.bat connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.p33nah.loopo.platform.audio.NativePlaybackBridgeTest' `
  --console=plain
```

Check packaging separately against the same APK:

```powershell
python scripts/verify_apk.py app/build/outputs/apk/debug/app-debug.apk --allow-model
```

This verifier reports ELF segment and uncompressed ZIP alignment by ABI. Preserve the commit/tree, APK SHA-256, image fingerprint, page-size output, full instrumentation result, and filtered logcat errors together. In particular, record emulator audio-HAL I/O errors: a passing native bridge/controller test proves execution and control paths, but does not prove that the virtual audio device can sustain playback.

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

## Compare Italian command interpreters

The test APK compares the current parser with two fits of the same supervised nearest-centroid classifier. B trains on the 57 examples in `training.txt`. C adds the 112 examples in `training-extra.txt`, including 32 phrases that resemble commands but refer to another action or object. Its inputs are word and character trigram features. A separate extractor supplies the rate or delta, and both interpreters return the app's typed voice plans. The test compares the complete action list, including any resume step.

`evaluation.txt` contains written commands and simulated transcription errors. `vosk-synthetic.txt` contains 12 prompts spoken by Microsoft Elsa Desktop and Microsoft Cosimo, with transcripts produced by the Windows Vosk 0.3.45 runtime. The replay used the same pinned Italian model as Loopo, with mono 16 kHz PCM in 100 ms chunks and an unconstrained recognizer. It flushes the recognizer at the end of each clip if no endpoint has returned text. The app uses the Android runtime and live microphone endpointing. The synthetic sample tests interpretation of Vosk output and cannot establish microphone or wake accuracy.

`generated-evaluation.txt` adds a deterministic stress corpus built from Italian phrase templates and labelled playback plans. Each row records the category, expected plan, source phrase, and input text. A label comes from the intended action before text mutations. The generator keeps the original number and negation intact in the one- and two-edit cases. A swapped pair of adjacent characters counts as one edit. Relative rate labels follow the existing Loopo convention of adding or subtracting percentage points. The generated cases also cover agreement errors, split number words, dropped prepositions, and commands that must be rejected.

Regenerate the corpus after editing the templates. Use `--check` to confirm that the committed file matches the generator output.

```powershell
python scripts/generate_voice_evaluation.py
python scripts/generate_voice_evaluation.py --check
```

`training-holdout.txt` contains 60 independently written evaluation cases for the training comparison. Both fits receive the same evaluation inputs. The test checks for duplicate training examples and excludes matching evaluation prompts or transcripts after normalization.

Build and install the app and test APKs on one Android target. Run the paired replay and read its report.

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.p33nah.loopo.ui.voice.VoiceIntentComparisonTest com.p33nah.loopo.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s LoopoVoiceAB:I '*:S'
```

The report counts exact plans and correct rejections together. It reports missed commands, wrong plans, and accepted negative cases separately. Timing covers warmed interpretation and result hashing. It excludes model training, Vosk, and playback. Each interpreter runs 15 batches in rotating order over the original 92 cases. The generated corpus contributes to the accuracy comparison. The p95 describes batch averages, not individual command latency. The report prints one fit time and the number of fitted weights for each classifier. B fits 726 double coefficients and C fits 1,473. The coefficient count excludes map entries, feature strings, and object overhead.

Keep the evaluation sets fixed when comparing training data. Read the generated categories separately because many rows share a template. The synthetic corpus measures interpretation of text. Microphone accuracy and device resource use require separate measurements.
