# First release readiness

Status on 2026-10-30 is **not ready for public release**. The owner reports successful testing on one Xiaomi device at the pre-hardening baseline. This is a release candidate reference. No official version tag, signed public APK, or compatibility certification is created by this change.

The hardening work adds automated verification before further product features. Each result must name the commit and executed check. A missing check remains open even if the build succeeds.

| Gate | Status and required evidence |
| --- | --- |
| Project licensing | MIT selected by the owner; license and native notices packaged |
| Baseline | `ee71995` builds and passes lint; Gradle reports no unit-test sources |
| Cached short-loop seek | Fix and exact sample/metadata regressions in PR #17 for [issue 15](https://github.com/p33nah/loopo/issues/15); final Android evidence remains required |
| Waveform source identity | Full-content hash fix for [issue 19](https://github.com/p33nah/loopo/issues/19); middle-edit test fails on baseline and passes after the fix |
| Native input validation | Bounded engine allocation, finish/preroll validation, and overflow-safe array checks; [issue 18](https://github.com/p33nah/loopo/issues/18) |
| Automated verification | 21 JVM tests, seven native groups, five packaging-check tests; Android execution is tracked by PR CI |
| Kotlin coverage | JVM report records 661 of 2698 lines at code head `2048d27`; it excludes executed Android and C++ evidence, so it is not total application coverage |
| Supported ABIs | ARMv7, ARM64, x86_64 debug build and packaging checks pass locally; real ARM validation remains required |
| 16 KB page compatibility | All packaged 64-bit ELF LOAD and uncompressed ZIP alignment checks pass; a 16 KB runtime test remains required |
| Output device changes | [Issue 16](https://github.com/p33nah/loopo/issues/16) remains a release blocker until route/error behavior is reproduced and addressed |
| Fractional output at loop wrap | [Issue 20](https://github.com/p33nah/loopo/issues/20) tracks one-input-frame segments at fast rates; controller reproduction and a fix remain required |
| API 26 output reset | [Issue 21](https://github.com/p33nah/loopo/issues/21) has a native/application regression and a backend-specific flush fix in PR #17; use that PR's current API 26 and 36 results as verification evidence |
| Physical Android matrix | Xiaomi rerun plus at least one other manufacturer and representative API versions |
| File-provider and storage failures | Verify expired grants, changed/deleted files, process death, nonseekable sources, large files, and disk-full behavior |
| Screen compatibility | Verify small screens and Android 16 large-screen behavior where orientation restrictions may be ignored |
| Repository protection | Configured and read back on 2026-10-30; main requires a PR and all five CI jobs, forbids force push/deletion, and release requires owner approval |
| Signing and updates | Owner supplies stable key, verifies certificate, backup, install, and signed update |

Time/frame conversions now use checked arithmetic. Remaining source risks include delayed detection of truncated WAV payloads, best-effort session saving during abrupt process death, and uncovered playback transitions in the controller. Resolve reproduced defects with focused regressions. Keep assumptions about vendor codecs and output routes labeled until physical evidence exists.

Completion of this PR establishes test and release infrastructure. It is not evidence that every corner case or every Android device has been tested. Update this record from actual test outputs and device results before tagging the first version.
