# Decoder increment

- 1. `how` over the affected subsystem.
- 2. `architect` for design exploration. Compare candidates sequentially because session policy prohibits subagent spawning.
- 3. Write the throughput checkpoint as four todo items.
- 4. Assign one implementation owner. Session policy prohibits subagent spawning, so the primary agent owns the diff and its direct review; the PR remains open for the repository owner.
- 5. Verify on the matching surface. "Inconclusive" or wrong-surface is not a pass. Flag it.
- 6. Rebase into small, ordered commits. Stack follow-ups. Use the **sequence-verifiable-units** principle skill, building, verifying, and committing each small unit before the next.
- 7. If the design is contested, `interrogate` before shipping. The current candidates can be compared directly against the project plan.
- 8. Run **Opening a PR**.
- **Blocking first steps.** Confirm PR #1 is merged, synchronize `main`, and create this branch from its merge commit before decoder changes.
- **Independent workstreams.** `n/a: decoder selection, the WAV reader, and the MediaCodec adapter share one PCM contract and are serialized.`
- **Shared mutable state.** One decoder instance owns its extractor, codec, and current stream position. Decoder calls run on an I/O worker.
- **Smallest safe decomposition.** Keep one app module and one `PcmDecoder` contract. Use separate implementations for WAV and platform codecs because their byte formats and seek behavior differ.
- Define the PCM data shape and completion predicate before writing decoder logic.
- Implement WAV PCM decoding and verify its output against a generated WAV artifact.
- Implement MediaExtractor/MediaCodec decoding for MP3 and FLAC.
- Integrate decoder availability with file-open state and readable errors.
- Build the APK and inspect the packaged result.
- Record unsupported codecs and device checks that need the Redmi.

## Phase A: Frame

- Done predicate: WAV PCM, MP3, and FLAC decoders compile behind one interleaved-float frame contract; WAV decoding is checked against a generated file; file-open reports decoder errors as actionable UI states.
- Scope: one app module; Kotlin decoder contracts, WAV and MediaCodec adapters, inspector integration, and UI state copy. No Oboe playback or time stretch in this increment.
- Blockers: no Android device is attached. MediaCodec capability and seek behavior on the Redmi remain unverified.
- Rigor: high at the codec and URI boundaries; normal for the in-memory PCM block contract.

## Phase B: Design the workflow

- Data shape: `PcmFormat(sampleRate, channelCount)` plus `PcmBlock(samples: FloatArray, frameCount)` with interleaved float samples; EOF and decode failure are distinct outcomes.
- Candidate A uses MediaExtractor/MediaCodec for every container. It shares one adapter but leaves WAV support dependent on device codec coverage.
- Candidate B uses a native decoder library for every format. It gives a consistent codec set but adds a native dependency, binary size, and license decision before the Oboe milestone.
- Candidate C uses a bounded WAV PCM reader and MediaExtractor/MediaCodec for MP3 and FLAC. It keeps URI access in Android adapters, gives WAV predictable support, and leaves compressed codec capability explicit.
- Chosen direction: Candidate C, matching the project plan and preserving a replaceable decoder boundary.

## Phase C: Run the loop

- Hypothesis: a single PCM block contract can feed waveform generation and playback while independent format adapters keep container details out of the UI.
- Predicate: the debug APK assembles, WAV sample output matches its input frames, invalid WAV and unavailable codec cases return typed failures, and the Redmi-specific codec matrix is reported as unverified until run on-device.

## Phase D: Keep the audit trail

- Append decisions and verification results to `.audit/studio-musicale-v1.tsv`.

## Phase E: Verify and hand back

- Inspect decoder behavior against the source data and the generated WAV artifact.
- Report unsupported formats, on-device checks, and PR state.

## Phase E: Evidence status

- Implemented: shared interleaved float PCM contract; streaming WAV PCM 8/16/24/32-bit and float32 parsing; MediaExtractor/MediaCodec adapter for MP3 and FLAC; decoder probe on open and user-facing errors.
- Verified here: `gradlew.bat assembleDebug` succeeds; `git diff --check` is clean.
- Still open: compare WAV decoded samples with generated reference data; run representative MP3 CBR/VBR, WAV (including extensible), and FLAC files; check codec behavior and sample-accurate assumptions on the Redmi. No Android device or corpus was attached, so M1 device acceptance is not claimed.
