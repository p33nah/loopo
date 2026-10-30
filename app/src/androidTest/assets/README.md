# Generated audio fixtures

These files contain an original synthetic 440 Hz sine, mono, 48 kHz, one second. They contain no third-party recording. The fixtures are covered by Loopo's MIT license and are packaged only in the test APK.

Generated with FFmpeg 7.1, supplied in the `imageio-ffmpeg` 0.7.0 Windows wheel. The tool is not a project runtime dependency and is not checked in.

```sh
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000:duration=1 -c:a libmp3lame -b:a 128k sine-440hz.mp3
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000:duration=1 -c:a flac sine-440hz.flac
```

| File | SHA-256 |
| --- | --- |
| sine-440hz.mp3 | dff173087f613b9180f29e46e476eb91f0caa11604278d0ebb96e1b9bbe2bb7e |
| sine-440hz.flac | c2610d143646e7140e41e7ef5a9c096a7bff63ae458adf32e3f45622a8189abd |
