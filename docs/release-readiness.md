# First release readiness

Status on 2026-10-30 is **not ready for public release**. The owner reports successful testing on one Xiaomi device at the pre-hardening baseline. This is a release candidate reference. No official version tag, signed public APK, or compatibility certification is created by this change.

The hardening work adds automated verification before further product features. Each result must name the commit and executed check. A missing check remains open even if the build succeeds.

| Gate | Status and required evidence |
| --- | --- |
| Project licensing | MIT selected by the owner; license and native notices packaged |
| Baseline | `ee71995` builds and passes lint; Gradle reports no unit-test sources |
| Cached short-loop seek | Tracked in [issue 15](https://github.com/p33nah/loopo/issues/15); regression and fix required |
| Waveform source identity | Full-content invalidation regression and fix required |
| Native input validation | Invalid finish parameters and interleaved bounds need tested rejection |
| Automated verification | JVM, native, and Android instrumentation suites and CI required |
| Kotlin coverage | Generate and inspect reports; uncovered paths remain explicit |
| Supported ABIs | Build and package ARMv7, ARM64, x86_64; real ARM validation remains required |
| 16 KB page compatibility | Verify all packaged native libraries and run a 16 KB runtime |
| Output device changes | [Issue 16](https://github.com/p33nah/loopo/issues/16) remains a release blocker until route/error behavior is reproduced and addressed |
| Physical Android matrix | Xiaomi rerun plus at least one other manufacturer and representative API versions |
| File-provider and storage failures | Verify expired grants, changed/deleted files, process death, nonseekable sources, large files, and disk-full behavior |
| Screen compatibility | Verify small screens and Android 16 large-screen behavior where orientation restrictions may be ignored |
| Repository protection | Owner enables required CI checks on main and owner approval for the release environment |
| Signing and updates | Owner supplies stable key, verifies certificate, backup, install, and signed update |

Remaining source risks from the initial audit include unchecked time/frame arithmetic for extreme codec metadata, delayed detection of truncated WAV payloads, best-effort session saving during abrupt process death, and the complexity of playback transitions in the controller. Resolve reproduced defects with focused regressions. Keep assumptions about vendor codecs and output routes labeled until physical evidence exists.

Completion of this PR establishes test and release infrastructure. It is not evidence that every corner case or every Android device has been tested. Update this record from actual test outputs and device results before tagging the first version.
