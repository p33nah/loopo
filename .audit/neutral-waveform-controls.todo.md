# Neutral waveform controls

## Feature workflow
- [x] How: identify the title/logo, theme tokens, waveform renderer, and loop controls.
- [x] Architect: compare two presentation shapes and choose Candidate B (transparent mark, charcoal theme, trailing fade group); cross-judge skipped because the configured pool has no different model family.
- [x] Implement: render the white brand mark, neutralize generic UI/waveform colors, compact and right-align Fade, keep Loop label stable with a clear active state.
- [x] Verify on the Android app surface; inspect layout and interaction affordances.
- [x] Reconcile and review the final diff.
- [ ] Open a PR into main and leave it open.

## Throughput checkpoint
- Blocking first steps: fetch origin/main, confirm the main checkout is clean and aligned, create the task branch in a new worktree. Done at 01128ef; branch codex/neutral-waveform-controls.
- Independent workstreams: the title/logo, palette, and loop-row changes share StudyScreen and theme decisions; one implementation owner handles them together.
- Shared mutable state: retain LoopRegion as the single source of loop/fade state; do not add UI state for the active label, derive active styling from region.enabled.
- Smallest safe decomposition: one UI implementation owner; no signature or domain-model changes expected.

## Architect phases
- [x] Ground
- [x] Sketch
- [x] Agree (default: proceed without a human checkpoint)
- [x] Implement
- [x] Scrap (not needed; implementation matched the sketch)

## Arena phases
- [x] Frame
- [x] Fan out
- [x] Cross-judge (skipped because all configured models use gpt-6-luna)
- [x] Pick
- [x] Graft
- [x] Verify
