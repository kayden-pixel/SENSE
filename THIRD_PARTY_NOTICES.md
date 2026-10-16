# Third-party notices

The Apache-2.0 license in LICENSE applies to original SENSE source. Third-party libraries, native components and model weights retain their own licenses. Gradle dependency resolution is the authoritative inventory; inspect transitive notices before distributing a modified release.

| Component | Upstream / license reference |
|---|---|
| AndroidX Compose, Room, DataStore, Lifecycle, CameraX | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) — Apache-2.0 |
| Kotlin and kotlinx.coroutines | [JetBrains Kotlin](https://github.com/JetBrains/kotlin) and [coroutines](https://github.com/Kotlin/kotlinx.coroutines) — Apache-2.0 |
| OkHttp / Okio | [Square OkHttp](https://github.com/square/okhttp) — Apache-2.0 |
| LiveKit Android SDK | [LiveKit](https://github.com/livekit/client-sdk-android) — Apache-2.0; includes native/transitive code with separate notices |
| WebRTC | [WebRTC license](https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE) — BSD-style; retain bundled third-party notices |
| Google ML Kit face detector | [ML Kit terms](https://developers.google.com/ml-kit/terms) and [SDK licenses](https://developers.google.com/ml-kit/terms#third_party_licenses) — Google SDK terms / third-party notices; detector weights are not relicensed Apache-2.0 |
| JNA | [JNA license](https://github.com/java-native-access/jna/blob/master/LICENSE) — Apache-2.0 / LGPL dual license |
| UniFFI native runtime and LiveKit FFI | [UniFFI](https://github.com/mozilla/uniffi-rs) / [LiveKit Rust SDK](https://github.com/livekit/rust-sdks) — retain their runtime and transitive license notices |
| JUnit | [JUnit](https://github.com/junit-team/junit4) — EPL-1.0 (test-only) |
| Gradle wrapper | [Gradle](https://github.com/gradle/gradle) — Apache-2.0 |
| Cloudflare Worker password hashing | [noble-hashes](https://github.com/paulmillr/noble-hashes) — MIT; server bundle only |

No third-party separation, lip-reading or reconstruction weights are distributed as optional packs. Each pack catalog entry must include its own license and source. A checksum verifies bytes, not a license or publisher's trustworthiness.
