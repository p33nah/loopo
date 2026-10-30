# Third-party notices

Loopo uses the MIT license. The APK packages its license and the vendored DSP notices in `assets/third_party_licenses/`.

| Component | Source/version | License |
| --- | --- | --- |
| Signalsmith Stretch | Commit `88061fd4eda56a2353c5d65c74aa3f4ccf76f9da` | MIT |
| Signalsmith Linear | Tag `0.6.6`, commit `7bb1c8f36794d6980080ce817c91f8da8fe56a66` | MIT |
| Oboe | Maven `com.google.oboe:oboe:1.11.0` | Apache-2.0 |

See the original notices in `app/src/main/cpp/third_party/` and the packaged Oboe license. AndroidX, Kotlin, and their transitive dependencies are resolved by Gradle and carry their upstream notices in dependency metadata and APK packaging. Review the resolved dependency graph and notices before each public release, especially after upgrades. This table identifies direct native dependencies; it is not a complete software bill of materials.

Upstream sources are [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch), [Signalsmith Linear](https://github.com/Signalsmith-Audio/linear), and [Oboe](https://github.com/google/oboe).
