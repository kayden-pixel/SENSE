# Two real phones: release acceptance record

Date: 2026-10-15. Overall result: **NOT RUN / BLOCKED**. No Android phone or emulator is attached. There is no deployed SENSE HTTPS endpoint; the saved LiveKit project rejects its API key. Automated HTTP fixtures are not phone, camera, BLE or media evidence.

Record the APK SHA-256, signing certificate, production API/LiveKit hostnames, phone models, Android versions, headset routes and tester/date before changing any result below. Use two fresh installations with different usernames. Save recovery keys privately; never attach tokens, audio, frames or keys to public issues. A PASS requires direct observation on both real phones, with permission and interruption cases repeated.

| ID | Action and required observation | Actual result |
|---|---|---|
| A1 | A creates a username; B creates another. Both reach Home without any service settings | NOT RUN |
| A2 | A requests B by username; only B can accept; both show the same accepted relationship | NOT RUN |
| A3 | Restart both apps and the API; each retains its own identity, profile and friendship | NOT RUN |
| A4 | Try taken/invalid username, wrong recovery key, no internet and API outage; show safe errors without losing the existing identity | NOT RUN |
| A5 | Expire/revoke A's API credential; private screens reset and reauthentication is required | NOT RUN |
| A6 | Remove/block B; B cannot invite, negotiate ranging, get room tokens or retrieve A's shared clips. B cannot remove A's block | NOT RUN |
| P1 | Both foreground: online appears within polling interval. Stop B/network: B becomes offline after the 20-second freshness window | NOT RUN |
| P2 | Expire a temporary session: API access stops; voice participants are ejected within cleanup/retry bounds | NOT RUN |
| R1 | Enable Radar on both with Nearby/location permission, Bluetooth and location services on. Each discovers the accepted friend | NOT RUN |
| R2 | Use non-UWB phones/BLE fallback: source is BLE RSSI, distance updates with movement and noisy estimates remain labeled | NOT RUN |
| R3 | No real azimuth: no directional arrow or invented bearing. With supported precise hardware, compare actual bearing and distance | NOT RUN |
| R4 | Turn off B's Bluetooth/exit Radar: stale measurement is labeled and expires; denied permissions stop discovery | NOT RUN |
| R5 | Nearby unrelated phone and removed/blocked friend cannot initiate precise ranging; accepted friend still needs a recent BLE observation | NOT RUN |
| W1 | A creates/invites; B joins. Verify microphone publication and intelligible two-way remote audio on both | NOT RUN |
| W2 | Mute/unmute each phone. Muted indicator matches remote silence. Denied/revoked mic access or publication failure never reports successful unmuted connection | NOT RUN |
| W3 | Interrupt Wi-Fi briefly, restore it and observe reconnect. Longer failure produces a clear state; retry obtains authorization automatically | NOT RUN |
| W4 | Wired/USB stereo route: compare directional movement with real Radar azimuth. Hear exactly one playback path; mono/no direction stays centered | NOT RUN |
| W5 | Disconnect/change headphones during custom PCM playback: custom output stops before SDK fallback; no doubled sound or lingering player | NOT RUN |
| W6 | Leave room, change feature, background and close app: microphone indicator stops, audio ceases and resources release | NOT RUN |
| W7 | Block/remove a connected member: media ejection occurs. Replay previously issued and recently refreshed tokens against the actual server; removed member cannot reconnect or get a new token | NOT RUN |
| W8 | Wait beyond initial join-token expiry and repeat joining/reconnection/removal checks. Record Cloud vs self-hosted revocation limitations | NOT RUN |
| M1 | A creates/invites; B joins. Both mark camera ready; host cannot schedule with a joined unready phone | NOT RUN |
| M2 | A schedules: countdown reaches both. Film both displays with an independent clock; both capture four-second clips | NOT RUN |
| M3 | Inspect each saved clip: immutable correct session ID, requested timestamp, Start-event timestamp and clock uncertainty stay together across restart | NOT RUN |
| M4 | Measure actual capture skew independently. Do not interpret network uncertainty as camera/scheduler accuracy or first-frame timing | NOT RUN |
| M5 | Withdraw readiness, background, deny camera, interrupt detector/camera or lose network before countdown. Stale/late capture must cancel or fail clearly | NOT RUN |
| M6 | Transfer each clip with progress; other joined participant retrieves matching bytes/hash and correct metadata after API restart | NOT RUN |
| M7 | Attempt wrong-session transfer, outsider retrieval and revoked credentials during upload/download; no unauthorized clip is committed or fully delivered | NOT RUN |
| S1 | Silent: camera deny/grant/revoke; train distinct examples and verify real live tracking, DTW similarity, threshold, cooldown and confirmation | NOT RUN |
| S2 | Veil: extra face, no face, tracking interruption and rapid screen transitions hide protected content; restoration requires stable single-viewer evidence | NOT RUN |
| S3 | Switch Silent/Veil/Moment repeatedly and background mid-operation: exactly one CameraPipeline binding; no camera remains active on exit | NOT RUN |
| S4 | Hear with wired/USB headset: low initial volume, actual monitoring, clipping/limiter check and route removal stop. Never feed back through the speaker | NOT RUN |
| S5 | Switch Hear ↔ Whisper and background during transition: one microphone consumer; focus loss/disconnect releases input/output | NOT RUN |
| S6 | Release build exposes neither a server selector nor simulation; restored developer preferences cannot activate them | NOT RUN |
| N1 | Install/run the signed release on a real 16 KB page-size system; verify PAGE_SIZE=16384 and exercise native camera/audio paths | NOT RUN |

Server tests separately establish HTTP authorization, scopes, persistence, queue limits, metadata integrity and restart behavior. They inject the media-service boundary explicitly and do not establish actual LiveKit connectivity, microphone publication, remote playback or synchronized physical captures. Cloud removal sends an explicit revocation cutoff; its real effect, including refreshed-token replay, still needs W7/W8. Self-hosted token revocation remains limited.
