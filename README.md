# SENSE

<img src="branding/sense-mark.svg" width="72" height="72" alt="SENSE logo">

A native, local-first Android sensing app. Kotlin, Compose and Material 3. Apache-2.0 for original source.

SENSE is an early beta implementation, not a hardware-validated release. No AI provider account, subscription, or user API key is required. Friends, voice and coordinated capture use your own HTTPS backend; voice media uses your own LiveKit deployment.

## Status

| Status | Feature | What is actually implemented |
|---|---|---|
| WORKING | App foundation | Five-tab dark UI, onboarding, contextual setup, capability StateFlow, permissions, Room and DataStore, encrypted credentials and mouth training |
| WORKING | People backend | Unique usernames with device-held recovery keys, compatible legacy accounts, requests, accept/remove/block, heartbeat presence, invitations and expiring sessions |
| PARTIAL | Radar fallback | BLE primary-packet advertising/scanning with rotating friend identifiers; RSSI estimates, smoothing and stale state; no invented direction. Physical two-phone discovery/calibration remains unverified |
| PARTIAL | Precise Radar | Android 16 Ranging with HTTPS OOB exchange, platform negotiation may select UWB; needs testing on supported phones; pre-16 standalone UWB is not integrated |
| PARTIAL | Whisper | LiveKit room join, real microphone publication, remote audio, mute/disconnect, custom headphone stereo PCM renderer with SDK speaker fallback; needs a configured LiveKit server and two-device playback/routing validation |
| PARTIAL | Silent | CameraX + bundled face contours, normalized mouth geometry, local DTW command classification, 3–6 examples, similarity/margin, cooldown and optional confirmation; personal calibration and accuracy testing required |
| PARTIAL | Veil | Conservative local multi-face/absence checks, selected SENSE People/You screens hidden until stable single-viewer state; single centered face is presumed to be owner, not authenticated identity |
| PARTIAL | Packs | HTTPS catalog/download, cancellation/retry, SHA-256 before atomic content-addressed installation and every access. Host filesystem regressions pass; Android download/Room/crash testing remains. Single opaque files only; no archive extraction or model integration |
| PARTIAL | Hear | Local PCM monitoring, headphone routing, platform noise suppression where available, focus high-pass filter, safe gain and soft limiter; device latency and route testing required |
| PARTIAL | Moment | Invite/join/readiness, host countdown, monotonic server-clock estimate with freshness/latency checks, 4-second CameraX clips, immutable session and capture-time uncertainty metadata, explicit transfer with progress, local history/share/delete; synchronization is best-effort, not frame-accurate |
| LABS | Hear separation | Processor and keyword hooks; no trained audiovisual separation or keyword model integrated |
| LABS | Moment reconstruction | Reconstruction interface; no 4D or bullet-time generation |
| LABS | Simulation | Explicit synthetic Radar mode confined to Labs, off by default and stopped on exit |
| PLANNED | Follow-up | Standalone pre-16 UWB, verified model integrations, hardware-driven regression tests, background voice foreground service, lost-key recovery and account deletion |

## Build

This beta ships arm64-v8a and x86_64 only. Older 32-bit devices are not supported; those upstream native slices did not pass the 16 KB alignment audit.

Install JDK 17 or 21, Android SDK Platform 36 (platforms;android-36) and Build Tools 35.0.0. Compile/target SDK is Android 16/API 36; minimum is API 28. Build Tools 35.0.0 is explicitly pinned for AGP 8.13 and is separate from the platform API level. The wrapper pins Gradle 8.13; Android Gradle Plugin 8.13.0 and Kotlin 2.1.20 are pinned.

~~~sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
python -m unittest discover -s tools -p "test_*.py"
python tools/check_native.py app/build/outputs/apk/debug/app-debug.apk
python tools/check_native.py app/build/outputs/apk/release/app-release-unsigned.apk
cd server
node --test test/*.test.mjs
~~~

On Windows use gradlew.bat. Set JAVA_HOME to your JDK. If a Windows JBR build reports a Unix-domain loopback connection error with a long temporary path, set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp for that build. No machine-specific path is committed.

An endpoint can be supplied at build time with -Psense.backendUrl=https://sense.example.org or SENSE_BACKEND_URL. Users can also configure an HTTPS endpoint in You. Only a public URL goes into the APK; backend and LiveKit secrets stay on the server. HTTP is deliberately disabled in the app, including debug.

## Run the backend

Node 22.16+ is required (built-in SQLite is experimental in Node 22). No npm dependencies are needed.

~~~sh
cd server
cp .env.example .env
# Set a stable, random RADAR_SECRET of at least 32 characters.
# Set LIVEKIT_URL, LIVEKIT_API_KEY and LIVEKIT_API_SECRET on the server.
node --env-file=.env src/server.mjs
~~~

Place it behind an HTTPS reverse proxy. Bind to localhost unless a private container network is used. Store database/media on a durable disk and back it up; protect the disk and backups. Do not expose the plain HTTP listener publicly. See [server setup](docs/SERVER.md).

In You, choose a username against the shared HTTPS server. The phone generates a private 256-bit recovery key and stores it with Android Keystore; save this key to use your identity on another phone. A username alone never authorizes access. Friends choose their own usernames explicitly; the APK contains no owner login. To use a prepared identity, turn off Choose a new username and enter its username and private recovery key. Older SENSE ID/password accounts remain supported. Exchange usernames and send/accept requests in People. Both phones must open Radar to advertise and range. Radar requests Nearby devices and precise location permission because it derives relative distance from scans; Android location services must be enabled. SENSE does not request GPS coordinates. Rotating BLE hints are not authenticated proof of proximity. In Whisper, create a room, invite the friend, then both join. In Moment, create/invite/join, mark both cameras ready, and the host starts the shared countdown. Transfer remains an explicit user action.

## Architecture

One application graph owns each concern. CapabilityService is the hardware/permission authority. Backend is the account/session client; SenseHttp supplies the shared cancellable HTTP transport for backend and pack requests. RadarService merges multiple ranging sources. CameraPipeline owns the single CameraX binding; Silent and Veil never start duplicate cameras. Whisper and Hear are mutually exclusive microphone consumers. Sensors stop when the activity stops or their feature exits. There is no background audio service in this beta.

The face detector is bundled so training/privacy work without network. Only normalized mouth geometry is persisted, encrypted with an Android Keystore AES-GCM key. No mouth video or Veil frames are uploaded. Capture clips are the only saved camera video and are shared/uploaded only through explicit actions. There is no first-party analytics or training-upload endpoint. The bundled ML Kit detector is subject to Google’s SDK privacy terms.

Moment timestamps describe CameraX Start-event delivery, not the first sensor frame. Network uncertainty excludes camera startup/scheduling error. Stale estimates, high round-trip latency and missed countdowns are rejected; frame-accurate synchronization is not claimed.

Existing credentials from builds without server binding require sign-in again; profile, training and captures are preserved. Credentials are never reused for a different configured server.

Local Room schemas are exported. Version 1→2 adds an index without deleting records. Server schema migrations run transactionally. Destructive fallback migrations are not used.

## Verification and release

See [validation](docs/VALIDATION.md), [security](SECURITY.md), [contributing](CONTRIBUTING.md) and [third-party notices](THIRD_PARTY_NOTICES.md). Compiling and unit tests cannot establish sensor accuracy, BLE distance calibration, voice quality, camera start precision or audio latency. The hardware checklist remains required before promoting this beta.
