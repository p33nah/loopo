# Contribute to Loopo

Open an issue describing the behavior before a large change. Include the Android version, device model, app version, audio format, and steps to reproduce a bug. Use generated or redistributable audio fixtures. Do not attach private music or document URIs.

Start implementation from current `origin/main` on a `codex/<task>` branch. Keep changes in a pull request and leave merging to the owner. Add a behavior test for a fix. Keep audio callback work bounded, allocation-free, and independent of file access or DSP processing.

Run the checks in [Testing](docs/testing.md) before opening the pull request. Describe what changed, the executed checks, and any unverified device behavior. Changes to DSP need signal-based tests and a device listening check. Changes to storage need malformed-source, cancellation, and resource-lifetime checks.

Keep Kotlin formatting consistent with nearby code. Prefer existing domain values and explicit ownership over forwarding interfaces. Do not commit signing keys, credentials, generated builds, local paths, or logs containing document identifiers.

Contributions use the project's [MIT license](LICENSE). Keep upstream copyright and license notices when changing vendored dependencies. Discuss dependency upgrades separately from behavior refactors so their failures remain attributable.
