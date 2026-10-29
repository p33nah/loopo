# M2 playback increment

## Feature playbook

1. `how` over the affected subsystem. **Done:** existing decoder and screen architecture grounded in the M1 implementation and the project plan.
2. `architect` for parallel design exploration. **Done in the preceding M2 implementation turn:** compared Kotlin `AudioTrack`, Oboe with JNI PCM handoff, and Media3. Oboe plus the SPSC ring was selected.
3. Write the throughput checkpoint as four todo items. **Done:**
	- **Blocking first steps.** Confirm PR #2 is merged, sync `main`, read the M2 plan and current decoder API. **Done.**
	- **Independent workstreams.** One owner implements the coupled native engine, decoder seek, Kotlin controller, and screen wiring. **n/a: the stream, ring, seek, and clock share state and need serialized changes.**
	- **Shared mutable state.** The decoder worker owns file and codec operations; the Oboe callback consumes preallocated ring data; the controller owns transport transitions. **Done.**
	- **Smallest safe decomposition.** Keep one writer for the native callback, JNI bridge, decoder seek contract, controller, and UI. **Done.**
4. Delegate code-writing to a subagent using your configured feature model (default in poteto-mode's Models section) with a specific scope (file paths, named data shape and its organizing structure per **principle-model-the-domain**, a state machine over scattered booleans, a table/registry over branching, a typed model over repeated shape assumptions, chosen before the delegate writes logic, and success criteria). Pick each subagent's reasoning level for the job, per the Subagents section. When the implementation admits multiple valid shapes (error handling, abstraction layer, test structure), delegate via the **arena** skill instead so the runners surface the alternatives and the cross-judge guards the pick. Mandatory: no skip-with-reason escape, and Laziness Protocol does not override it (the gain is review separation, not lines saved). A subagent forbidden to spawn satisfies this by owning the diff directly with the same review separation. Give every file-writing delegate its own worktree (spawn it with `isolation: "worktree"`, or hand it an exclusive branch), and do not write files or run a suite in a worktree a delegate still holds. On Claude Code, `isolation: "worktree"` branches from the remote default branch unless the `worktree.baseRef` setting is `"head"`. When a delegate builds on commits the default branch lacks, commit them, create its worktree with `git worktree add <path> -b <delegate-branch> HEAD`, and name that base commit in the brief. Fencing a file in the brief's prose is not a lock (**principle-separate-before-serializing-shared-state**). Comments per **Comments**. Surgical edits, re-ground against the source for upstream-derived files. Port shared-primitive improvements to all consumers and verify each. Commit liberally. **Done by the current owner:** the session's `multi_agent_mode` forbids spawning subagents, so the implementation stayed in this branch and was reviewed directly against the interfaces and decoder contract.
5. Verify on the matching surface. "Inconclusive" or wrong-surface is not a pass. Flag it. **APK build and packaging verification done; actual playback and focus behavior remain inconclusive without the Redmi.**
6. Rebase into small, ordered commits. Stack follow-ups. Use the **sequence-verifiable-units** principle skill, building, verifying, and committing each small unit before the next. **One M2 commit is appropriate because native engine, decoder seek, controller, and UI form one playback path.**
7. If the design is contested, `interrogate` before shipping. **n/a: the design comparison and documented selection were completed before this continuation.**
8. Run **Opening a PR**. **In progress.**

## M2 scope and evidence

- Source plan: `piano-app-studio-musicale-android-v1.md`, milestone M2 and sections 5-6, 9, and 11.
- Implemented Oboe float output, a bounded SPSC PCM ring, source-frame clock, underrun and latency reporting, decoder seek, play/pause/seek/EOF, AudioFocus, and foreground-only playback lifecycle.
- Android focus behavior follows the distinction between permanent loss and transient loss. Transient loss pauses and resumes on gain; permanent loss pauses and abandons focus.
- Verified with `gradlew.bat assembleDebug`, APK inspection for the three `arm64-v8a` native libraries, `javap` signature comparison, and `git diff --check`. These checks are recorded again in `.audit/studio-musicale-v1.tsv`.
- No tests were added or run.
- Device acceptance is open. No device is attached, so the 30-minute run, repeated seek, focus, underrun, and latency checks have not been observed.
- `BLOCKED: independent review`. The current session policy forbids subagent spawning, so the required comment-sicko review could not run. The PR is open for the user's review; this run does not claim an independent verdict or merge readiness.
