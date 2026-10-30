# Work on Loopo

Loopo is an offline Android music-study app. Kotlin and Compose own study state and platform integration. C++ runs Signalsmith Stretch before an SPSC ring feeding Oboe. The first planned official version is `0.0.1`; one successful Xiaomi test is a baseline, not a compatibility guarantee.

## Start every chat

1. Read `README.md` and `docs/release-readiness.md`. Inspect the current branch, worktree status, remotes, and recent commits before changing files.
2. Read the requested GitHub issue and its comments, linked issues, and existing pull requests. Use their current state rather than an earlier chat's summary. If the user asks for the next task, select one actionable release blocker not already covered by an open PR, then explain the selection and proceed.
3. Before application changes, read `docs/architecture.md`. Before choosing or running checks, read `docs/testing.md`. For tagging, signing, or release workflow changes, also read `docs/releasing.md` and `THIRD_PARTY_NOTICES.md`.

This file is the persistent entry point. Other Markdown files are references reached through these instructions; they are not assumed to load automatically. Keep workflow rules here, commands in `docs/testing.md`, architecture decisions in `docs/architecture.md`, and current release evidence in `docs/release-readiness.md`.

## Take one issue through verification

1. State the observable outcome and acceptance criteria for the selected issue. Reproduce a reported defect or establish a passing baseline before refactoring. Keep unrelated findings in separate issues.
2. Follow the branch workflow below. Preserve existing changes. If the current checkout is dirty or belongs to another task, use a clean isolated worktree from updated `main` rather than stashing or resetting another task's work.
3. Add behavior tests at the owning boundary and make the smallest change that meets the criteria. Keep one owner for playback state and one for study/session state. Extract deterministic logic when it enables a concrete regression test; avoid speculative interfaces.
4. Run the relevant checks from `docs/testing.md`. For audio, parser, persistence, JNI, or build changes, run the full JVM suite, native sanitizer suites, lint, APK packaging checks, and Android instrumentation as applicable. A compiled test APK is not an executed integration test. Report unavailable hardware or tools explicitly.
5. Review the actual diff and test outputs. Push the branch, open or update one PR for the issue, link its closing reference, and inspect CI on the current head. Fix failures attributable to the change. Keep unresolved checks visible in the PR.
6. Finish with the PR link, verified behavior, and remaining risks. Update durable architecture or release evidence when the task changes it. Keep the issue open until its fix is merged, and leave merging to the owner.

## GitHub access

Use the repository identified by `origin`, currently `p33nah/loopo`. Prefer the connected GitHub tools when they support the operation. If the connector returns an access error, verify access through the existing authenticated `gh` CLI before declaring the resource unavailable. This repository's connector has returned 403 for issue creation while the Windows `gh` CLI succeeds.

Read back issue and PR updates. Keep reproduction steps, acceptance criteria, evidence, and follow-up links in GitHub so a fresh chat can resume without private local logs. `.audit/` is ignored working material, not durable project state.

## Branches and pull requests

- Start each implementation from the latest `origin/main`. Confirm a clean worktree, fetch, switch to `main`, and fast-forward it before creating a `codex/<short-task-slug>` branch.
- Keep implementation commits on that branch. Build and verify the change, push the branch, then open a pull request into `main` with a short summary, evidence, and unverified checks.
- Leave pull requests open for the repository owner to merge. Do not merge pull requests or push application changes directly to `main`.
- After the owner reports a merge, verify the pull request state, fetch `origin`, fast-forward local `main`, and create the next work branch from it.
- If the repository has no `main` branch, create a minimal base branch first and put application changes in a pull request.

## Audio and release constraints

- Keep decoding, file I/O, allocation, DSP processing, and waits outside the Oboe output callback. Reset the ring only after producer and consumer have stopped.
- Validate untrusted audio metadata and JNI dimensions before allocation or arithmetic. Supported playback input is mono or stereo PCM at 8 to 192 kHz.
- Preserve Kotlin and native frame/rate units across JNI. Test source-frame mapping as well as samples around seek, preroll, crossfade, and loop wrap.
- Use generated audio fixtures or recordings with clear redistribution rights. Preserve upstream dependency notices.
- A green CI run permits code review, not a release claim. Release-blocker issues, physical-device evidence, signing identity, and signed update checks are separate gates. The owner publishes releases after reviewing the draft.
- Keep keystores and passwords outside Git. Read signing secrets only in the release workflow. Repository protection and the `release` environment require owner configuration, documented in `docs/releasing.md`.

When a task changes this workflow, update this file and the affected reference in the same PR. Avoid duplicating instructions across new files. Verify every referenced path and command before handing the task back.
