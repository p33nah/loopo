# Piano di lavoro

- Phase A: Frame
- Phase B: Design the workflow
- Phase C: Run the loop
- Phase D: Keep the audit trail
- Phase E: Verify and hand back
- **Blocking first steps.** Gates run before fan-out.
- **Independent workstreams.** Disjoint files, services, or layers parallelize. Shared writes serialize.
- **Shared mutable state.** Default to splitting the target (the **separate-before-serializing-shared-state** principle skill). Serialize only for real invariants.
- **Smallest safe decomposition.** If one worker is best, name why.
- Baseline and environment checked.
- [x] Android project scaffold and build entry point.
- [x] M0 screen and immersive landscape configuration.
- [x] M1 SAF picker and extractor metadata inspection.
- [x] Review source and inspect the generated APK.
- [ ] M1 streaming decoders for MP3, WAV, and FLAC.
- [ ] M2 Oboe playback and audio focus.
- [ ] Independent review of the remaining diff.
- [x] Prepare and verify PR #1 from the feature branch into main.

## Phase A: Frame

- Done predicate: the debug APK assembles, declares landscape orientation, includes only the arm64 native library, and has no broad storage or network permission. Device launch remains an explicit unverified predicate.
- Scope: M0 plus the SAF picker and initial metadata inspection, one app module, Gradle build configuration, Compose screen, and initial domain contracts. The complete M1-M7 plan remains follow-on work.
- Blockers: the GitHub repository has no commits or branches; no Android SDK, adb, Gradle, or Android device is currently discoverable on this host.
- Rigor: high for project shape and native/audio boundaries; normal for the reversible visual scaffold.

## Phase B: Design the workflow

- Scaffold first: Android project and reproducible Gradle build.
- Add the landscape/fullscreen Compose shell and typed study state.
- Verify Gradle assembly and inspect the manifest and generated artifact.
- Keep Oboe, decoder, time stretch, persistence, and device acceptance as explicit later milestones.

## Phase C: Run the loop

- Hypothesis: a minimal Kotlin/Compose Android app can establish M0 and keep the future audio pipeline behind typed domain boundaries.
- Predicate: debug APK builds successfully, manifest declares landscape, packages arm64 native code, and requests no broad storage or network permission. The app runs through the Android picker or target-device lifecycle, which is not verified without a device.

## Phase D: Keep the audit trail

- Log design decisions and verification outcomes in `.audit/studio-musicale-v1.tsv`.

## Phase E: Verify and hand back

- [x] Compare the source and APK manifest against the local build predicate.
- [x] Report unverified device-specific behavior and the next open milestone.
