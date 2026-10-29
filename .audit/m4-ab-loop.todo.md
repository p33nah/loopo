# M4 A/B loop

## Goal

Deliver an editable A/B source-frame loop with real-time repetition, equal-power crossfade, and acknowledged edits during playback.

## Phases

- [x] Ground the existing transport, PCM pump, native ring, study UI, and M4 requirements.
- [x] Compare loop ownership designs and sketch the selected types and boundaries in `m4-ab-loop.design.md`.
- [x] Add loop-region normalization and crossfade frame calculations.
- [x] Make the PCM producer emit bounded, loop-aware audio segments and crossfade data.
- [x] Keep native source-frame position correct across loop boundaries and latency estimation.
- [x] Add A/B handles and loop/crossfade controls with acknowledged controller state.
- [x] Build and exercise short loops, near-EOF loops, edits during playback, and track replacement on the Redmi.
- [x] Update `.audit/studio-musicale-v1.tsv and inspect the diff.
- [ ] Commit, push the branch, and open a PR to `main`.

## Scope and constraints

- One tightly coupled workstream: decoder reads, producer queue order, native callback position, and UI acknowledgement share one loop contract.
- The audio callback may not allocate, lock, decode, or perform I/O.
- Keep PCM buffering bounded; do not preload the whole source.
- A/B points remain source frames and obey `0 <= A < B <= durationFrames`.
- Crossfade is 0–100 ms, equal-power, capped at 25% of the loop length, and disabled at 0 ms.
- Keep UI state on the last controller-acknowledged loop while an edit is being applied.

## Verification

- Build the arm64 debug APK after each contract boundary.
- Verify loop wrap by observing source-frame position return from B to A while playback remains active.
- Verify crossfade limits and command acknowledgement on-device; ask the user to listen for clicks because this host cannot hear the phone's physical audio route.
- Record any unverified long-duration or audio-route acceptance separately.
