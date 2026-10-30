# Report a security problem

For a vulnerability involving a malformed audio file, native memory safety, document access, or signing, use [GitHub private vulnerability reporting](https://github.com/p33nah/loopo/security/advisories/new) if the repository offers it. If private reporting is unavailable, open an issue requesting a private contact without including the exploit or sensitive data.

Include the affected commit or APK version, Android version, reproduction steps, and a minimal generated file when sharing is safe. Do not post signing material, private recordings, or document-provider identifiers.

Loopo is pre-release. No released version currently has a security maintenance commitment. The first supported version and update policy must be recorded when its release is published.

The application uses the Android document picker and decodes local content. It has no network permission and does not request access to the whole media library. Treat every selected file and provider response as untrusted. Signing keys stay outside source control and APKs distributed as releases must be signed with the owner's distribution key.
