# Backend deployment

Use Node 22.16 or later. The server has no npm dependencies. Copy server/.env.example to .env and generate a stable RADAR_SECRET with a cryptographically secure random generator. Configure a production LiveKit server with TLS, UDP/TCP ICE reachability and TURN as required by your network. LIVEKIT_URL must use wss://. LiveKit API credentials remain server-side.

The API listens on 127.0.0.1:8787 by default. Put Caddy, nginx or another reverse proxy in front with an HTTPS certificate, a 100 MB upload body limit, request timeouts and sensible connection limits. TRUST_PROXY must remain false unless only your trusted proxy can reach the API. No wildcard CORS is provided: this is a native-app API.

Persist DATABASE_PATH and MEDIA_PATH. The SQLite database contains profiles, salted password hashes, hashed session tokens, friendships, memberships, timestamps and clip metadata. The media directory contains only explicitly transferred capture files. Back up both consistently; encrypt the disk/backups and restrict filesystem permissions. Keep secrets in environment configuration, not in an image or repository.

Sessions expire in one hour; account tokens expire in 30 days. Initial API-issued voice join tokens last at most one minute and are scoped to one room and microphone publication. Pending media ejections survive process restarts; expired-room ejections are queued by the one-minute cleanup. See SECURITY.md for self-hosted token replay limits. Passwords use PBKDF2-SHA256 at 600,000 iterations, with transparent legacy rehash after successful login. A scheduled cleanup removes expired token/OOB data. Capture files and session history intentionally remain until an operator's retention policy removes them; establish that policy before a public deployment. Do not run several API processes against independent state: rate buckets are in-process and the app expects one authoritative store. Password reset, account deletion and distributed rate limiting are follow-up work.

API endpoints: /auth/register, /auth/login, /me, /profile, /friends, /sessions, /sessions/:id/{join,leave,invite,token,ready,schedule,clips}, /oob, /logout and /health. All except auth/health/ready require a bearer token. Presence updates use /presence. Use accepted friend IDs for invites. Clients must not send a LiveKit API key or secret.

No server is automatically deployed by this repository. Production packaging refuses an unconfigured endpoint; only debug Labs provides an explicit offline preview for local sensing with permissions.

## Username setup and a private prepared owner

New users choose a unique username during first-run onboarding. The device generates a random 256-bit recovery key; the API hashes it using the same PBKDF2 implementation as legacy passwords. Keystore stores the device credential, scoped to the HTTPS server. A username alone cannot authenticate. On another phone, use the username and saved recovery key. Old ID/password identities still work through Use an older SENSE ID. There is no email requirement. Lost-key recovery is not implemented. Legacy IDs and usernames share a case-insensitive invite-address namespace; registrations cannot shadow an existing user's address, including while password hashing is in flight.

To prepare only the operator's identity before opening registration:

```sh
node server/tools/prepare-owner.mjs /absolute/private/sense-owner kayden-pixel
```

This writes three private files with exclusive creation and restrictive POSIX permissions: owner.private.json (salted hash), backend.private.env (owner record and Radar secret), and owner-access.private.txt (username/recovery key). Keep them out of repositories, images, APKs and shared archives. On Windows, restrict their directory's ACL to the operator. The script refuses to replace an existing record. Configure OWNER_ACCOUNT_FILE to point at the private JSON, or supply OWNER_ACCOUNT from the private environment file. On startup, the backend inserts this one identity only if absent and refuses conflicting existing identities; restarts never overwrite credentials. Users still explicitly create their own distinct identities. The first visitor receives no special privileges.

The Dockerfile runs as a non-root user and accepts server secrets through environment configuration. Mount a durable writable volume at /var/lib/sense. npm start accepts platform-provided environment variables even when no .env file exists. SIGTERM/SIGINT close the listener and SQLite database, with a ten-second shutdown deadline.

render.yaml is a deployment proposal for one API instance and a 1 GB persistent disk. It needs a paid Render service; free ephemeral hosting would lose accounts and clips on restart. Creating a service and attaching its disk are deployment steps, not performed by committing the file. The current Render MCP service-creation tool cannot attach a disk. LiveKit requires its own authorized Cloud project or a self-hosted server with public ICE/TURN connectivity; a Render HTTP service does not replace the media server. See [LiveKit self-hosting](https://docs.livekit.io/transport/self-hosting/vm/). No LiveKit Cloud login is required when self-hosting.

## Production readiness and delivery

GET /health reports process liveness. GET /ready returns 200 only after a real authenticated LiveKit RoomService check succeeds; missing credentials, rejected keys and network failures return 503. Checks coalesce and cache for 15 seconds. Voice-token issuance uses the same readiness result and rechecks user credentials, membership and joined state after the network check. Response payloads and logs never contain server credentials. NODE_ENV=production refuses missing explicit absolute database/media paths, a missing Radar secret or invalid LiveKit settings. Persistent storage must still be provisioned and restart-tested by deployment.

The official public URL belongs in config/production.properties. Release signing uses SENSE_KEYSTORE_PATH, SENSE_KEYSTORE_PASSWORD, SENSE_KEY_ALIAS and SENSE_KEY_PASSWORD; no signing or service secret is a BuildConfig field. The manual CI publish_release path additionally uses encrypted GitHub SENSE_KEYSTORE_BASE64 and signing secrets, packages a release-signed APK, and verifies native ELF layout. Normal users see only username/sign-in and contextual permissions. Developer server configuration exists only in debug Labs.

Current deployment (2026-10-16): the official HTTPS API is https://sense-api.jarvis-mesh.workers.dev. It runs on Cloudflare's free SQLite-backed Durable Objects with persistent accounts and bounded clip storage. New LiveKit credentials are encrypted Worker secrets; real `/ready` authorization succeeds. The previous unconfigured developer APKs must be replaced with the connected release. See [the Cloudflare adapter and deployment instructions](CLOUDFLARE.md).

## Operator configuration

Infrastructure is managed by the operator; app users only create or recover their own identity:

| Required input | Current state |
|---|---|
| Persistent host | Cloudflare Workers Free with SQLite-backed Durable Objects; no Render billing or R2 subscription |
| LIVEKIT_URL | The reachable WSS URL for the operator's LiveKit Cloud project |
| LIVEKIT_API_KEY and LIVEKIT_API_SECRET | Matching new key/secret stored only in encrypted Worker settings |

The public API URL is in `config/production.properties`. The stable Radar secret is an encrypted Worker setting. Release signing uses the existing encrypted GitHub secrets. The optional Node/Render deployment remains available for self-hosting; its local database/media paths require a persistent disk. `TRUST_PROXY=true` is safe there only if the isolated gateway overwrites untrusted forwarding headers with a verified client address. The Cloudflare adapter replaces forwarding headers using Cloudflare's trusted client address.

Release publication verifies `/ready`, signing, the compiled APK's official URL, package/SDK, non-debuggable manifest, signature and exclusion of available private values, followed by the ELF/ZIP native audit. The two-phone acceptance record remains mandatory even after a successful publication check.
