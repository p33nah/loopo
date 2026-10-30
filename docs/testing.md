# Verify Loopo

Use JDK 17, SDK Platform 36, Build Tools 35.0.0, NDK 27.0.12077973, and CMake 3.22.1. The Gradle wrapper pins Gradle 8.13 and verifies the distribution checksum. Test dependencies are pinned in `app/build.gradle.kts`. Playback accepts mono or stereo PCM at 8 to 192 kHz and rejects unsupported configurations before native allocation.

Run the Android build, JVM tests, and lint on Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
python scripts/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
```

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
| Playback rate and loop limits | Domain tests at valid boundaries and invalid values | Controls remain usable at supported display sizes |
| Cached loop seek and crossfade | Exact sample, phase, wrap, channel, and clipping tests | Listen for clicks at multiple loop lengths and rates |
| Time stretching | Native generated-signal pitch, duration, reset, tail, and finite-output tests | MP3, WAV, FLAC listening on target hardware |
| Output queue | Ring capacity, wrap, metadata, and SPSC tests | Underruns and stable playback under device load |
| WAV decode and seek | Generated encoding and malformed-file tests | Real document providers and large files |
| MediaCodec MP3 and FLAC | Android integration scenarios | Vendor decoder outputs and inaccurate duration metadata |
| Waveform | Pyramid extrema, partial buckets, cache roundtrip and corruption tests | Large file, cancellation, low storage, and cloud provider behavior |
| Session | Source matching and persistence integration tests | Process death, expired grants, changed or deleted file, disk-full recovery |
| Interface | State rendering, callback and application integration tests | Touch seek, pinch, pan, marker drag, accessibility, and small displays |
| Audio lifecycle | Pause and foreground integration tests | Focus loss, calls, wired/Bluetooth changes, unplug, lock screen |
| Distribution | Three ABIs, ELF/ZIP alignment, notices, lint | Signed install/update and physical ARM devices |

Use a generated sine or short redistributable recording in mono and stereo at 44.1 and 48 kHz. Include a one-frame or very short WAV, invalid header, truncated payload, unsupported channel layout, and long file. Check playback rates at 25%, 100%, and 200%, plus a fractional value such as 73%. Check loops around the crossfade limit and the two-second cache boundary.

Record each physical-device run with commit, APK SHA-256, Android version, ABI, page size, device model, output route, scenario, and result. Keep document URIs and private recordings out of public logs. A passed emulator run does not replace this matrix.
