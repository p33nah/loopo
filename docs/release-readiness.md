# First release readiness

Status on 2026-10-31 is **not ready for public release**. The owner reports successful testing on one Xiaomi device at the pre-hardening baseline. This is a release candidate reference. No official version tag, signed public APK, or compatibility certification is created by this change.

The hardening work adds automated verification before further product features. Each result must name the commit and executed check. A missing check remains open even if the build succeeds.

| Gate | Status and required evidence |
| --- | --- |
| Project licensing | MIT selected by the owner; license and native notices packaged |
| Baseline | `ee71995` builds and passes lint; Gradle reports no unit-test sources |
| Cached short-loop seek | Merged in PR #17 for [issue 15](https://github.com/p33nah/loopo/issues/15); [run 41100993099](https://github.com/p33nah/loopo/actions/runs/41100993099) passes eight tests on each of API 26 and 36 |
| Waveform source identity | Full-content hash fix for [issue 19](https://github.com/p33nah/loopo/issues/19); middle-edit test fails on baseline and passes after the fix |
| Native input validation | Bounded engine allocation, finish/preroll validation, and overflow-safe array checks; [issue 18](https://github.com/p33nah/loopo/issues/18) |
| Automated verification | JVM behavior, native sanitizers, packaging checks, and API 26/36 instrumentation run in CI; each implementation PR records its final-head results |
| Kotlin coverage | CI generates the JVM report and checks actual Robolectric execution data; connected Android and C++ evidence remain separate, so this is not total application coverage |
| Supported ABIs | ARMv7, ARM64, x86_64 debug build and packaging checks pass locally; real ARM validation remains required |
| 16 KB page compatibility | All packaged 64-bit ELF LOAD and uncompressed ZIP alignment checks pass; a 16 KB runtime test remains required |
| Output device changes | [Issue 16](https://github.com/p33nah/loopo/issues/16) remains a release blocker until route/error behavior is reproduced and addressed |
| Fractional output at loop wrap | [Issue 20](https://github.com/p33nah/loopo/issues/20) records the two failing controller cases on both APIs in [run 41136630147](https://github.com/p33nah/loopo/actions/runs/41136630147); its linked fix PR must verify integer budgets, input conservation, source spans, and seek/rate transitions before merge |
| PCM across engine/JNI | [Issue 32](https://github.com/p33nah/loopo/issues/32) tracks observable signal verification across the engine boundary; current wrapper signal tests and Android state tests do not assert that entire path's PCM |
| API 26 output reset | [Issue 21](https://github.com/p33nah/loopo/issues/21) was fixed in PR #17; [run 41100993099](https://github.com/p33nah/loopo/actions/runs/41100993099) passes the native reset and application seek regressions on API 26 and 36 |
| Physical Android matrix | Xiaomi rerun plus at least one other manufacturer and representative API versions |
| File-provider and storage failures | Verify expired grants, changed/deleted files, process death, nonseekable sources, large files, and disk-full behavior |
| Screen compatibility | Verify small screens and Android 16 large-screen behavior where orientation restrictions may be ignored |
| Repository protection | Configured and read back on 2026-10-30; main requires a PR and all five CI jobs, forbids force push/deletion, and release requires owner approval |
| Signing and updates | Owner supplies stable key, verifies certificate, backup, install, and signed update |

Time/frame conversions now use checked arithmetic. Remaining source risks include delayed detection of truncated WAV payloads, best-effort session saving during abrupt process death, and uncovered playback transitions in the controller. Resolve reproduced defects with focused regressions. Keep assumptions about vendor codecs and output routes labeled until physical evidence exists.

PR #17 established the test and release infrastructure. Each follow-up records its current-head CI evidence in the linked issue and PR. Update this record with actual test outputs, physical-device results, and signed-artifact checks before tagging the first version.
