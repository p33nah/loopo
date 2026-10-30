# Changelog

## Unreleased

The first official version is planned as `0.0.1`. Release hardening is in progress. No compatibility claim is made until the checks in [release readiness](docs/release-readiness.md) have evidence.

- Add layered native, JVM, and Android verification and continuous integration.
- Add MIT licensing, contribution guidance, security reporting, and a signed draft-release workflow.
- Extend APK builds to ARMv7, ARM64, and x86_64 and check native packaging alignment.
- Preserve requested short-loop samples and source position through DSP preroll and loop wraps.
- Invalidate waveform peaks after middle-only source edits, including unchanged provider metadata.
- Bound native allocations and reject invalid JNI dimensions, rates, and time conversions.
- Flush paused OpenSL ES output synchronously so Android 8 can reset playback after pause.
