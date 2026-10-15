# Validation

Build and test results are recorded for this implementation pass after final verification. Android hardware-dependent behavior remains unvalidated on physical devices; no device or emulator is connected to this workspace.

## Required device trial

1. Install the APK on two 64-bit phones. Deny each permission once, re-enable it through settings and verify capability updates. Test no-camera/no-mic/unsupported-UWB cases.
2. Configure an HTTPS backend and WSS LiveKit server. Test replay of a previously issued room token after block/remove on the actual LiveKit deployment; self-hosted instant token revocation is not guaranteed. Register, sign in on another install, invite/accept/remove/block/unblock. Confirm room grants fail for outsiders and removed members, including active-call ejection.
3. Open Radar on both phones. Compare BLE estimates against known distances; verify direction is absent for RSSI, stale data is labeled, blocked friends disappear, rotating identifiers update and scanning stops on background/exit. Test Android 16 ranging on supported hardware.
4. Join Whisper with wired stereo headphones. Verify bidirectional voice, mute, reconnection, participant updates and disconnect. Test measured left/right movement with real UWB direction; test mono Bluetooth routes and PCM fallback. Confirm Hear and Whisper cannot capture simultaneously.
5. Train three distinct Silent gestures with 3–6 examples. Measure false accepts/rejects in varied light. Test threshold/margin, confirmation, cooldown, deletion and Silent mute controls inside a room. Background during training and verify no camera/microphone leak.
6. Enable Veil on People and You. Test zero faces, extra faces, off-center face, detector interruption, camera denial/revocation, app background and screen transitions, including protected content during exit animations. Content should fail closed and restore only after stable single-viewer detection. Veil is not owner authentication.
7. Use a trusted test catalog with known bytes. Check that an interrupted replacement preserves the previous installed record/artifact, including replacement with the same version and different bytes. Test valid download, hash mismatch, size mismatch, cancellation/retry, app restart, tampered installed file and unavailable hardware. No runtime may bypass verifiedFile.
8. Use wired/USB headphones for Hear. Start at low volume; test disconnect, noise suppression availability, focus filter, clipping and background stop. Measure actual end-to-end latency externally; the buffer metric is not total latency.
9. Join Moment on both phones, mark cameras ready and schedule. Join an already scheduled session without marking ready and verify it does not record. Withdraw readiness during countdown and verify capture is cancelled. Lose network/background one phone before countdown. Compare Start-event callback timestamps with requested timestamps and independently filmed capture timing. The uncertainty covers network sampling only; callback time is not the first sensor-frame timestamp. Transfer, retrieve, share, delete and reopen clips after restart. Inspect camera errors and expired sessions.
10. Boot a 16 KB Android system and install/run the release build. Verify getconf PAGE_SIZE returns 16384. Re-run the ELF/ZIP audit for every dependency change. Build a signed AAB with your private release key kept outside this repository before Play distribution.

## Commands

~~~sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
node --test server/test/*.test.mjs
python -m unittest discover -s tools -p "test_*.py"
python tools/check_native.py app/build/outputs/apk/debug/app-debug.apk
python tools/check_native.py app/build/outputs/apk/release/app-release-unsigned.apk
~~~

Release signing and public server deployment are operator steps. An unsigned release APK is a build check, not an installable signed release. The debug APK is signed with the local Android debug key for testing.

## Earlier 0.1.0 verification — 2026-10-15

- assembleDebug and assembleRelease: passed; release is unsigned and R8-minified.
- testDebugUnitTest: 21 tests, zero failures/errors/skips, including temporal tracking, malformed DTW input, clock freshness, limiter safety, privacy reset, atomic artifact failure and stalled HTTP body cancellation.
- Backend: 24 tests reported, zero failures (including parent tests). Covers block ownership, unrelated-room isolation, durable ejection retries, expiry, password rehash migration, bounded queues, concurrent registration, rate limits, and credentials revoked during JSON/media streaming, owner bootstrap, case-insensitive username ownership and distinct friend identities.
- lintDebug and release vital lint: passed with no errors; 22 dependency-update/style warnings remain. Release manifest is non-debuggable, min API 28, target/compile API 36, with cleartext transport and backup disabled.
- Debug and release native audits: 16 libraries per APK (8 per ABI); every packaged .so is read as ELF, including compressed ZIP entries. Checks architecture, ET_DYN, program-header bounds, every PT_LOAD p_align >= 16384, power-of-two alignment, congruent file/virtual offsets and segment bounds, plus stored ZIP placement. Five validator regression tests pass. Final per-library SHA-256 and segment results are retained in verification/native-*.json. No 32-bit slices are shipped. This is ELF/load-layout compatibility, not a 16 KB device runtime test.
- First-party TODO/FIXME and credential-pattern scans: no matches. Explicit Labs simulation is the only synthetic participant path.
- Hardware interaction, end-to-end voice/media, real ranging and on-device camera/recognition accuracy were not tested: no connected Android device/emulator or deployed backend/LiveKit server was available.
- Source is published at https://github.com/kayden-pixel/SENSE. The CI workflow can publish a debug-signed prerelease after Android/backend checks pass. CI SDK setup explicitly installs platform-tools because the old tools package has been removed.
- The prepared kayden-pixel owner identity is verified against a durable local SQLite database. Its private access/configuration files are outside the repository and absent from both APKs and the source archive. No public SENSE backend is deployed. Saved LiveKit CLI project credentials returned HTTP 401 and were not used.

## Runtime hardening 0.1.1 — 2026-10-15

- Developer APK build, release Kotlin compilation and lint pass. Android JVM suite: 27 tests, zero failures. Backend HTTP/unit suite: 31 reported tests, zero failures. Native validator regression suite: 5 tests pass. The current developer APK passes actual ELF and ZIP-layout checks for all 16 packaged libraries.
- First run authenticates and persists the session before opening the main UI. Release ignores developer endpoint preferences and offline preview. URL editing is debug Labs only. HTTP/SDK failures map to controlled user messages; recovery keys use a protected dialog. Failed signup-response retries can recover using the device-held credential, without taking another identity.
- Real runtime probe using the saved LiveKit configuration: local API /health returned 200; /ready returned 503 with api=true and voice=false. An actual LiveKit room join returned HTTP 401, invalid API key. No successful production media or Android-to-production path is claimed.
- No official public production endpoint or authorized persistent host is available. Four encrypted release-signing secrets are provisioned in GitHub; deployment and valid LiveKit credentials remain unavailable. No Android device/emulator is connected.
- Private RSA-3072 PKCS12 release signing is provisioned locally and as encrypted GitHub secrets. Release packaging correctly fails its live-readiness gate. A new production APK was not built or published. The retained native-release.json and unsigned 0.1.0 APK describe the earlier build, not a current production release.
