# Stable per-stream output latency

## Problem

The displayed Oboe timestamp estimate and the waveform cursor offset share one value. Oboe's current-output estimate can vary with queued frames; Loopo stores the last successful sample across stream flushes and converts it directly into a cursor offset. On the attached Redmi Note 14 Pro, the user reported 2,000 ms at 49% speed with no dropout, and the cursor visibly waited. Existing M5 device notes already recorded spikes up to 96,000 ms. The product requirement is a stable estimate and a responsive cursor on the same output stream.

## Usage (caller's view)

```cpp
engine.open();
const double latencyMs = engine.estimatedLatencyMillis();
const int64_t positionFrame = engine.positionFrame();
engine.resetAtFrame(targetFrame); // keeps the same stream timing
```

The Kotlin monitor and UI continue to read the same two native methods. A track load opens a new stream and establishes a new estimate. A seek, loop change, or speed change resets audio content but does not replace the output stream, so it keeps the same timing estimate.

## Shape

`PlaybackEngine` owns one stable output-frame offset derived from the opened Oboe stream. After `openStream()` succeeds, it reads the effective buffer size, frames per burst, and actual stream sample rate. The offset is `bufferSizeInFrames + framesPerBurst`; the displayed milliseconds are derived from that offset and sample rate. Validate positive geometry at the stream boundary. If geometry is unavailable, keep the existing unavailable sentinel and let `positionFrame()` use the uncorrected source position.

`estimatedLatencyMillis()` no longer samples `calculateLatencyMillis()` on every monitor tick. It reports the nominal per-stream estimate. `positionFrame()` uses the same frame offset directly, avoiding a milliseconds-to-frames conversion through the source track's sample rate. `resetAtFrame()` preserves the offset. `close()` clears it. A changed effective buffer size refreshes the offset, which handles output-route changes without reacting to queue-depth or timestamp spikes.

The existing native `PlaybackEngine` API remains the sole policy boundary. Kotlin, JNI signatures, `PlaybackSnapshot`, and `StudyScreen` do not learn Oboe's geometry. One stored frame offset is the source of truth; the displayed milliseconds are derived. This keeps the offset stable for the lifetime of an unchanged stream and retains cursor compensation for nominal output buffering.

## Synthesis decision

Candidate 1 is the base. Its geometry-derived per-stream offset is stable and preserves the shared estimate/position correction. Candidate 2 also derives a stable estimate, but removes latency correction from the cursor, which risks making the visual position lead the sound by the downstream output delay. A cross-judge was skipped because the configured runner and cross-judge pool contains only `gpt-6-luna`, the same model family as the parent. The two candidates agree on stream geometry as a stable source; the base keeps the smaller and more consistent behavior surface.

## Tradeoffs accepted

- We accept that buffer geometry is a nominal output estimate rather than a full live DAC timestamp in exchange for a stable value that does not grow with repeated writes or survive as a stale high sample.
- We accept that route changes may produce a new estimate in exchange for keeping a useful per-stream output delay.
- We accept that geometry may omit downstream mixer or device delay in exchange for preventing a two-second cursor hold from an unreliable dynamic timestamp.

## Alternatives considered

- A one-time Oboe timestamp calibration preserves end-to-end timing but may lock in a bad first sample and inherits the same device API's reported anomalies.
- A rolling median or exponential smoother hides short spikes but a sustained rise still moves the estimate and cursor.
- Removing cursor compensation entirely makes the marker responsive but can put it ahead of the sound by the actual output-buffer delay.

## Implementation reconciliation

Implemented in `PlaybackEngine.cpp` and `PlaybackEngine.h`. The engine now derives the frame offset from the opened stream geometry and actual output sample rate, refreshes it only from effective buffer geometry, preserves it across seek/rate/loop resets, and clears it on close. JNI and Kotlin signatures remain unchanged. Same-device verification at 35% showed a stable 62 ms estimate after playhead seeks and the user confirmed the cursor starts immediately.

## Open questions and risks

- Verified on the target phone at 35% playback: 48 kHz stream, 2,052 buffer frames, 960 burst frames, 3,012 latency frames (62.75 ms, shown as 62 ms). The user reports immediate cursor movement and a stable estimate after seeks. The exact 49% setting was not used in this final validation.
- Does the effective buffer size change on output-route changes without a stream reopen on this device? This was not exercised.
- The nominal geometry estimate can omit downstream mixer or device delay. The user reported no perceptible cursor lead in the verified run; other output routes remain unverified.

## Next implementation step

Log the opened stream geometry in the debug build, then implement the stable frame offset in `PlaybackEngine` and verify repeated seeks and play starts on the attached phone.

