# Loopo

Loopo is an offline Android app for studying music from local audio files. The current build starts the landscape Compose screen, opens MP3, WAV, and FLAC files through Android's document picker, and reads track metadata when the Android media extractor supports the file.

The audio engine, waveform, A/B loop, time stretch, and saved sessions are not implemented yet. The native CMake target is an initialization stub for the later Oboe engine.

## Build

Install JDK 17, Android SDK Platform 36, Android SDK Build Tools 35.0.0, NDK 27.0.12077973, and CMake 3.22.1. Then run `gradlew.bat assembleDebug` on Windows or `./gradlew assembleDebug` on Linux or macOS.

The initial build targets `arm64-v8a`. The minimum Android version is set to API 26 provisionally and needs confirmation on the Redmi Note 14 Pro 4G.
