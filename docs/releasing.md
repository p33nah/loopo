# Prepare the first signed release

The first planned version is `0.0.1`, with Android `versionCode` 1. Do not publish it until every gate in [release readiness](release-readiness.md) has evidence. Later releases must increase `versionCode`; changing only `versionName` is insufficient for Android updates.

1. Merge reviewed pull requests through the repository owner. Keep application changes off direct pushes to `main`.
2. Run CI at the exact intended commit and complete the physical-device matrix in [Testing](testing.md).
3. Keep the distribution signing key offline with a separate encrypted backup. Use the same key for future updates. Never commit a keystore or put passwords in a Gradle file.
4. Verify the GitHub `release` environment still requires approval from `p33nah` and only accepts protected branches. That rule was configured on 2026-10-30. Store `LOOPO_KEYSTORE_BASE64`, `LOOPO_KEYSTORE_PASSWORD`, `LOOPO_KEY_ALIAS`, and `LOOPO_KEY_PASSWORD` as environment secrets. The workflow does not create a key or choose one for the owner.
5. Create an annotated `v0.0.1` tag at the verified `main` commit. Push the tag after reviewing its target.
6. Run **Prepare signed release draft** on `main`, supplying that existing tag. The workflow repeats verification, checks that tag and version match the dispatched commit, signs the APK, verifies signature and native packaging, and creates a draft GitHub release with SHA-256 checksums.
7. Review the draft APK, install it on physical devices, test an update signed by the same key, and record the signing-certificate fingerprint. Publish the draft manually only after owner approval.

The workflow creates a draft. It does not publish a release or upload to a store. Missing secrets, open release-blocker issues, failed checks, mismatched versions, or a tag pointing elsewhere must stop it.

Debug builds use a development key and are CI artifacts for testing. An installed development APK usually cannot be updated with the distribution key. Export any important study state before uninstalling it. No migration of Android's app-private storage across an uninstall is provided.

The APK includes `armeabi-v7a`, `arm64-v8a`, and `x86_64`. Android API 26 is the minimum. Compilation for an ABI does not prove device support. Native libraries need both ELF segment and APK ZIP alignment for [16 KB page devices](https://developer.android.com/guide/practices/page-sizes). The checked script verifies packaged ELF LOAD segments and uncompressed ZIP offsets, and the Android build uses explicit NDK r27 linker flags. Test on a 16 KB runtime as well.

Review all runtime dependency notices before publishing. Loopo is MIT licensed, while dependencies retain their own licenses. Check [third-party notices](../THIRD_PARTY_NOTICES.md) and the resolved Gradle graph when dependencies change.
