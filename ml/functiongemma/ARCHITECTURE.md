# Loopo voice command interpretation

When voice control is enabled, `PlaybackVoiceController` owns the Vosk Italian recognizer and the wake-and-command lifecycle. The runtime uses the pinned `vosk-model-small-it-0.22.zip` after the first installation. A wake turn pauses playback, captures a transcript and gives the text to `VoiceCommandCoordinator`. `ItalianVoiceCommandParser` is the default interpreter. It normalizes Italian text, matches transport phrases, parses typed rates and delegates ordering to `VoicePlan.validated`.

The coordinator checks cancellation and session currency before dispatch. The controller sends typed actions to the playback session. A rate-only command resumes playback automatically if playback was active at wake. The benchmark scores the explicit interpretation plan before this playback policy.

| Native tool | Kotlin action | Effect |
| --- | --- | --- |
| `resume()` | `VoiceAction.Resume` | Start or resume from the current position |
| `restart()` | `VoiceAction.Restart` | Start at marker A when looping, otherwise at frame zero |
| `stay_paused()` | `VoiceAction.StayPaused` | Keep the playback pause established by voice capture |
| `set_rate(percent)` | `VoiceAction.SetRate` | Set an integer rate from 25% through 200% |
| `adjust_rate(delta_percent)` | `VoiceAction.AdjustRate` | Add signed whole percentage points, −175 through +175, excluding zero |

The executor resolves a relative change against the active rate and rejects a final rate outside 25%–200%. A plan contains one action or a rate action followed by explicit resume or restart. Loop marker edits, count-based repetition, progressive ramps, arbitrary seeking and a separate stop-and-reset action have no voice action. A request combining them with a supported action is a rejection example.

[PR #67](https://github.com/p33nah/loopo/pull/67) adds a sparse centroid intent classifier and a guarded fallback to the Android test APK. The production app still runs the parser. The [issue record](https://github.com/p33nah/loopo/issues/66) documents why the centroid remained experimental and the subsequent guarded pilot. This experiment replays the expanded centroid at commit `9465326b8feb99243bd70e31c2fcaf258ecdf72f`. That older candidate predates restart. The current parser includes restart from [PR #72](https://github.com/p33nah/loopo/pull/72).

FunctionGemma runs one isolated tool-selection turn per transcript. It emits native calls or a text rejection. The Python benchmark and Kotlin adapter validate the complete ordered plan against the allowlist. The coordinator can receive the validated plan through an injected interpreter and uses the existing executor. The experimental dry run exposes an in-memory trace at the interpretation boundary without invoking the coordinator or playback. The [Android notes](../../docs/ml/functiongemma/ANDROID.md) cover the native runtime handoff.
