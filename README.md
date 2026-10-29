# Loopo

Loopo is an offline Android app for studying music from local audio files. It opens MP3, WAV, and FLAC files through Android's document picker, draws a waveform, supports A/B loops with crossfade, and provides pitch-preserving playback speed from 25% to 200% in 1% steps.

The app uses Kotlin and Jetpack Compose for its interface, Android's media decoders for PCM input, Oboe for low-latency output, and Signalsmith Stretch for time stretching. DSP runs in the producer worker before the output ring buffer. The Oboe callback only reads prepared PCM.

## Build

Install JDK 17, Android SDK Platform 36, Android SDK Build Tools 35.0.0, NDK 27.0.12077973, and CMake 3.22.1. Then run `gradlew.bat assembleDebug` on Windows or `./gradlew assembleDebug` on Linux or macOS.

The initial build targets `arm64-v8a`. The minimum Android version is API 26.

Signalsmith Stretch and Signalsmith Linear source headers and MIT license notices are checked into `app/src/main/cpp/third_party/`. The notices are also packaged in the APK under `assets/third_party_licenses/`.
