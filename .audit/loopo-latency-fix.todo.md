### Bug fix

1. Reproduce it yourself on the matching surface via the driver skill (Non-negotiables), even when a debug or instrumentation protocol says to ask the user to reproduce. Ask the user only with a stated, specific reason the control surface cannot reach the target, and only after driving it as far as it goes. If it won't reproduce directly, synthesize the trigger, tighten conditions, or instrument until it fires.
2. Binary-search the cause. Form the candidate hypotheses, then rule them out until one survives. Seed them with `how` over the affected subsystem and the **why** skill for regression history. Each pass, take the split that cuts the most remaining problem space, get runtime evidence, eliminate. When program state is unclear, add instrumentation or logging and read it as the code runs. Don't guess. Drive a long or stubborn hunt with Claude Code's `loop` skill. Confirm the surviving *mechanism* with runtime evidence before the step-3 architect/interrogate fan-out.
3. Plan the fix. If it crosses a function boundary, `architect` first. Delegate implementation to a subagent using your configured bug-fix model (default in poteto-mode's Models section) with a specific scope.
4. Verify on the same surface. The original repro now passes. "Inconclusive" or wrong-surface is not a pass. Flag it. Unit tests show branch behavior, not bug absence.
5. Stage the commits so the failing repro lands before the fix in git history. See the **tdd** skill for the failing-test-first cadence when the bug has a cheap local test path. Skip it when the test would be expensive, integration-heavy, or unclear.
   This is the canonical **sequence-verifiable-units** principle skill, the failing test first and the fix on top.
6. Run **Opening a PR**.

### Throughput checkpoint

- **Blocking first steps.** Confirm Oboe estimate behavior, seek/reset behavior, and latest origin/main before editing.
- **Independent workstreams.** Read-only diagnosis/design can run separately from implementation; shared code edits serialize.
- **Shared mutable state.** Give the implementation worker its own worktree; preserve existing untracked audit artifacts.
- **Smallest safe decomposition.** One implementation owner is best because the latency estimate and cursor compensation share one state owner in PlaybackEngine.

### Architect

1. Ground
2. Sketch
3. Agree
4. Implement
5. Scrap

### Task-specific

- Preserve the prior live-device logs and the user's reported 49% playback rate and 2,000 ms estimate.
- Keep the displayed estimate bounded and stable enough that the waveform cursor does not wait seconds after playback or seek.
- Verify the reported playback and seek behavior on the same Android device if the owner can still run it.
- Do not alter loop semantics or time-stretch quality without evidence.

### Status

- Original report reproduced on matching device by user. The high estimate delays `positionFrame()` because it is subtracted from rendered output frames.
- Repeated seek actions pause and flush the AAudio stream. The measured estimate can persist across `resetAtFrame()` if the next Oboe call fails; no raw-result logging yet proves that failure occurred.
- `simpleperf` capture unavailable on this Android build: `failed to open perf event file for event_type cpu-cycles: Permission denied`.
- throughoutput checkpoint: n/a, read-only forensics complete; implementation now authorized.

### Throughput checkpoint after grounding

- **Blocking first steps.** Complete. The attached device reproduced a 2,000 ms estimate with delayed cursor; `origin/main` was fetched and local `main` fast-forwarded to `b6debad` before creating `codex/stabilize-audio-latency`.
- **Independent workstreams.** Two read-only architecture explorations are writing to separate temporary output folders; implementation stays with one owner after a design is selected.
- **Shared mutable state.** The repo's existing untracked audit artifacts remain preserved. No two workers will edit the code branch at once.
- **Smallest safe decomposition.** One native C++ owner is preferred because both the exposed latency and its cursor compensation are derived in `PlaybackEngine`.

### Source history

- `a5803e5` introduced Oboe `calculateLatencyMillis()` and latency-corrected position.
- `e3ff0dc` added the position-history correction for loop behavior.
- `.audit/m5-time-stretch-design.md` from `9ed91c5` records prior device anomalies up to 96,000 ms and later 229–771 ms, while ordinary playback was 86–98 ms.

### Architect synthesis

- Candidate 1 is the base: stable per-stream estimate from effective buffer size, one burst, and the actual Oboe sample rate; use the same stored frame offset for cursor correction.
- Candidate 2 derives a stable estimate from geometry but removes cursor latency correction; rejected because it may make the cursor lead audio.
- Cross-judge skipped: the configured pool contains only `gpt-6-luna`, the same family as the parent.
- Both candidate designs converge on stream geometry and reject dynamic timestamp smoothing as insufficient for a sustained high value.

### Implementation and verification

- The implementation worker committed `PlaybackEngine.cpp` and `PlaybackEngine.h`; the commit was cherry-picked onto `codex/stabilize-audio-latency`.
- Debug build: `BUILD SUCCESSFUL` from `gradlew.bat --no-daemon assembleDebug`.
- The debug APK was installed over USB on the attached Redmi Note 14 Pro running Android 15 and the app was restarted.
- Opened stream geometry was 48,000 Hz, 2,052 buffer frames, 960 burst frames, for 3,012 latency frames or 62.75 ms. The UI showed `stima 62 ms` and `dropout 0` after several playhead seeks at 35%.
- User confirmed the cursor starts immediately and the estimate stays stable. This same-surface run is the behavioral verification; no unit test harness exists in `app/src`.
- The exact 49% value was not used in this final check. It does not affect the estimate, which is derived from output-stream geometry, but 49% remains an unverified rate-specific check.
- `simpleperf` remains unavailable on this device because the system denied CPU-cycle perf events; this did not block latency/cursor verification.
- `git diff --check` passed before build.
