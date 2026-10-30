# Loopo

Loopo is an offline Android app for studying music from local audio files. It opens MP3, WAV, and FLAC files through Android's document picker, draws a waveform, supports A/B loops with crossfade, and provides pitch-preserving playback speed from 25% to 200% in 1% steps.

The app uses Kotlin and Jetpack Compose for its interface, Android's media decoders for PCM input, Oboe for low-latency output, and Signalsmith Stretch for time stretching. DSP runs in the producer worker before the output ring buffer. The Oboe callback only reads prepared PCM.

## Build

Install JDK 17, Android SDK Platform 36, Android SDK Build Tools 35.0.0, NDK 27.0.12077973, and CMake 3.22.1. Then run `gradlew.bat assembleDebug` on Windows or `./gradlew assembleDebug` on Linux or macOS.

Builds include `armeabi-v7a`, `arm64-v8a`, and `x86_64`. The minimum Android version is API 26. The planned first release is `0.0.1`. It is still a release candidate until the documented verification gates pass.

Signalsmith Stretch and Signalsmith Linear source headers and MIT license notices are checked into `app/src/main/cpp/third_party/`. The notices are also packaged in the APK under `assets/third_party_licenses/`.

## Verify and contribute

Run `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` on Windows. Use `bash gradlew` on Linux or macOS. Host C++ tests run separately with CMake and CTest. Android instrumentation needs an emulator or connected device.

See [Testing](docs/testing.md), [architecture](docs/architecture.md), [release readiness](docs/release-readiness.md), and [release procedure](docs/releasing.md) for commands, evidence, and remaining hardware checks. GitHub Actions runs automated checks on pull requests. The release workflow signs an APK and creates a draft only after verification.

Read [Contributing](CONTRIBUTING.md) before changing the code and [Security](SECURITY.md) to report a vulnerability. Loopo uses the [MIT license](LICENSE). Dependencies retain their [upstream licenses](THIRD_PARTY_NOTICES.md).
