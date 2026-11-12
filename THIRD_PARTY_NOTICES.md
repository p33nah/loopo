# Third-party notices

Loopo uses the MIT license. The APK packages its license and the vendored DSP notices in `assets/third_party_licenses/`.

| Component | Source/version | License |
| --- | --- | --- |
| Signalsmith Stretch | Commit `88061fd4eda56a2353c5d65c74aa3f4ccf76f9da` | MIT |
| Signalsmith Linear | Tag `0.6.6`, commit `7bb1c8f36794d6980080ce817c91f8da8fe56a66` | MIT |
| Oboe | Maven `com.google.oboe:oboe:1.11.0` | Apache-2.0 |
| Vosk Android | Maven `com.alphacephei:vosk-android:0.3.75` | Apache-2.0 |
| Vosk Italian model | `vosk-model-small-it-0.22`, downloaded from the versioned Loopo release asset | Apache-2.0 |
| JNA | Maven `net.java.dev.jna:jna:5.18.1` | Apache-2.0 (selected distribution option) |

See the original notices in `app/src/main/cpp/third_party/` and the packaged Oboe license. AndroidX, Kotlin, and their transitive dependencies are resolved by Gradle and carry their upstream notices in dependency metadata and APK packaging. Review the resolved dependency graph and notices before each public release, especially after upgrades. This table identifies direct native dependencies; it is not a complete software bill of materials.

Upstream sources are [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch), [Signalsmith Linear](https://github.com/Signalsmith-Audio/linear), and [Oboe](https://github.com/google/oboe).

Every build includes the Vosk recognition runtime. The release APK does not contain the 49.7 MB compressed Italian model; it downloads that archive after the user turns voice on for the first time. Debug test builds can optionally bundle the model archive to make device testing independent of a published release. The app includes this component notice and the full Apache-2.0 text in its packaged third-party licenses.

- [Vosk API license](https://github.com/alphacep/vosk-api/blob/master/COPYING)
- [Vosk model licenses](https://alphacephei.com/vosk/models)
- [JNA 5.18.1 license](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE)
