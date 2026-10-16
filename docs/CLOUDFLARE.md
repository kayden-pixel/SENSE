# Free persistent hosting

The Cloudflare adapter runs the same authenticated SENSE API in one SQLite-backed
Durable Object. Accounts, relationships, tokens, pending voice removals, and shared
clip chunks survive Worker restarts and deployments. LiveKit remains the separate
voice transport. This path needs neither a Render disk nor an R2 subscription.

## Deploy

Use a verified Cloudflare account on the Workers Free plan. From `server/cloudflare`:

```sh
npm ci
npx wrangler login --scopes account:read user:read workers:write workers_scripts:write workers_routes:write
npm test
npm run deploy
npx wrangler secret put RADAR_SECRET
npx wrangler secret put LIVEKIT_URL
npx wrangler secret put LIVEKIT_API_KEY
npx wrangler secret put LIVEKIT_API_SECRET
```

Supply a generated random stable Radar secret of at least 32 characters and the
matching LiveKit project's WSS URL/key/secret. These are **server secrets**, never
Android settings or source files. An optional private `OWNER_ACCOUNT` is supported.
No first visitor gains operator privileges. Missing secrets fail closed.

The Worker uses `@noble/hashes` PBKDF2-HMAC-SHA256 to retain the existing 600,000
round password format. Cloudflare's production native PBKDF2 limit is lower than
the local simulator's, so native derivation must not be substituted. Cross-runtime
tests verify both current and legacy hashes byte for byte.

The stable Durable Object name is `sense-production-v1`. Do not change this name,
the Worker name, or the binding's class during updates: they select the persistent
database. Preserve the namespace when deleting/recreating deployments.

Verify the public `/ready` response contains `ready=true`, `services.api=true`,
and `services.voice=true`. Then set the public HTTPS URL in
`config/production.properties` and build through the signed release workflow.
`/health` alone does not prove voice readiness. Never publish an unconfigured APK.

## Storage and free limits

Cloudflare's free request, CPU, SQL read/write, and storage quotas apply. See the
[current free limits](https://developers.cloudflare.com/durable-objects/platform/limits/)
and [pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).
Keep the account on Free to avoid enabling paid overages. This is a small-beta
deployment, not unlimited hosting; quotas can stop new requests until reset.

Clip capacity is capped at 800 MiB to leave room for identities and SQLite metadata.
Uploads reserve capacity before receiving data, stream in chunks of at most 64 KiB,
and become downloadable only after the shared API validates length, SHA-256, and
current authorization. Failed uploads are removed; abandoned uploads are reclaimed
after one hour. Shared clips from sessions expired for more than a day are reclaimed
by the next maintenance alarm. Local phone captures are unaffected.

Durable Object alarms run expiry and pending voice-ejection cleanup even when no
phone is open. An alarm reschedules while an active voice room or failed ejection
needs attention. Cloudflare may delay alarms; token replay still requires testing
against the actual LiveKit project.

## Verification

`npm test` bundles the Worker and runs it in Cloudflare's local workerd runtime with
persistent SQLite. The HTTP journey covers readiness, separate signup identities,
friend acceptance, Moment readiness/scheduling, chunked upload, a full runtime
restart, authenticated byte-exact retrieval, and block denial. Its media-service
response is an explicit fixture; it is not evidence of real voice playback.
The original Node backend regression suite must also pass.
