# M3 waveform increment

## Figure it out checklist

- Read the Principles section of the `poteto-mode` skill. **Done.**
- Frame the task with a falsifiable finish condition, scope, effort and blockers. **Done:** M3 comprises the waveform domain/cache and the Compose interaction. The finish condition is a multiresolution waveform and navigable viewport that build into the arm64 APK. The 60-minute Redmi acceptance check remains open because no device is attached.
- Design the workflow before coding. **Done:** implement and build the cache/generator unit, then implement and build the screen/gesture unit. Keep the work on one branch and the decision log in `.audit/studio-musicale-v1.tsv`.
- Run each unit as an experiment. **In progress.** Build the unchanged branch as baseline, then check after each unit. Do not add or run tests unless the user asks.
- Keep the decision trail current. **In progress:** append one row per design fork and verified unit to `.audit/studio-musicale-v1.tsv`.
- Verify M3 against the app and open a PR. **Pending:** inspect the produced APK and report the phone-only acceptance gap. Leave the PR open for the user to merge.
- `BLOCKED: independent review`. Session policy forbids spawning subagents, so neither parallel architecture runners nor the comment-sicko and cross-model trail reviews can run. Do not claim an independent review or merge readiness.

## Frame

- **Definition of done.** The app shows a versioned, integrity-checked per-channel min/max pyramid; scans through a separate decoder and reports progress; lets the user zoom around the pinch center, pan, and seek by tapping; renders the playhead in source-frame time; and builds into the arm64 APK.
- **Scope.** Two implementation units span the domain model, PCM scan, cache, ViewModel, Compose canvas, and app activity. Playback continues to own its decoder. A device, an audio corpus, and runtime performance measurements are unavailable here.
- **Rigor.** High for cache format, source identity, and frame mapping because corruption or time-coordinate errors silently show the wrong song position. Medium for reversible UI styling. The cache uses a schema version, CRC, atomic replacement, and bounded LRU cleanup.

## Design decisions

- `WaveformData` owns per-channel min/max arrays grouped in `WaveformLevel` objects. Each next level combines two adjacent buckets. `WaveformState` represents scanning progress, preview data, ready data, or failure.
- `WaveformViewport` owns a source-frame window and centralizes zoom anchoring, pan bounds, and tap-to-frame conversion.
- `WaveformRepository` emits immutable snapshots. Its worker owns a separate decoder and cache operations. Playback and persistence do not share its state.
- Compared raw PCM, one fixed-resolution peak array, and a hierarchical peak pyramid. Raw PCM grows with song duration. One fixed level loses detail at zoom. The pyramid lets the canvas choose a level near one bucket per pixel.
- Compared Room rows, raw PCM, and a versioned binary file for cache storage. The binary file stores the peak arrays directly and avoids a new database dependency. It uses CRC, atomic replacement, and an LRU quota capped at the smaller of 256 MiB or 10% of available app storage.
- Cache identity uses normalized URI, track format and duration, source size and modification time when available, plus a hash of the source prefix and suffix. Without stable metadata, hash the whole source before lookup.
- Compared gesture recognizers split between Compose's transform and tap detectors with a custom single `awaitEachGesture` state machine. Use Compose detectors so framework touch-slop and cancellation remain in control. Pinch preserves the source frame under the centroid; horizontal drag pans; tap seeks.
- Compared vertical peak bars, a filled envelope, and retaining all PCM samples for rendering. Draw bounded vertical min/max bars from the nearest pyramid level. This preserves transients without allocating a path or loading PCM into the UI.

## Verification units

1. **Baseline.** Build the merged M2 branch and record the result before code changes. **Verified:** `assembleDebug` passes on the unchanged branch.
2. **Waveform model, generator, and cache.** Build, inspect the data bounds and cache validation paths, then commit this unit. **Build verified; commit is next.**
3. **ViewModel and Compose interaction.** Build, inspect frame mapping and gesture state, then commit this unit.
4. **Hand back.** Inspect APK libraries, `git diff --check`, attach the PR, and list device checks that remain inconclusive.
