import test from "node:test";
import assert from "node:assert/strict";
import { Miniflare, Response as WorkerResponse, convertV4MiniflareOptions } from "miniflare";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";

test("Cloudflare runtime preserves accounts, friends, capture bytes and revocation across restart", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "sense-worker-"));
  const options = convertV4MiniflareOptions({
    name: "sense-runtime",
    modules: true,
    scriptPath: ".wrangler/test-build/worker.js",
    compatibilityDate: "2026-10-16",
    compatibilityFlags: ["nodejs_compat"],
    durableObjects: { SENSE_API: { className: "SenseApi", useSQLite: true, unsafeUniqueKey: "sense-runtime-test" } },
    resourcePersistencePath: directory,
    bindings: {
      RADAR_SECRET: "test-radar-secret-".repeat(3),
      LIVEKIT_URL: "wss://voice.example.org",
      LIVEKIT_API_KEY: "test-only",
      LIVEKIT_API_SECRET: "test-voice-secret-".repeat(3),
    },
    outboundService: async () => new WorkerResponse("{}", { headers: { "Content-Type": "application/json" } }),
  });
  let runtime = new Miniflare(options);
  const request = async (route, token, body) => {
    const response = await runtime.dispatchFetch("https://sense.test" + route, {
      method: body === undefined ? "GET" : "POST",
      headers: { Connection: "close", "Content-Type": "application/json", "CF-Connecting-IP": "198.51.100.1", ...(token ? { Authorization: "Bearer " + token } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    }).catch((error) => { throw new Error(`Request failed: ${route}`, { cause: error }); });
    const data = await response.json();
    assert.ok(response.ok, `${route}: ${response.status} ${JSON.stringify(data)}`);
    return data;
  };
  try {
    assert.equal((await request("/health")).ok, true);
    assert.equal((await request("/ready")).ready, true);
    const password = "test-only-recovery-key-123456789";
    const a = await request("/auth/register", null, { id: "alice", username: "alice", name: "Alice", password });
    const b = await request("/auth/register", null, { id: "bob", username: "bobby", name: "Bob", password });
    await request("/friends", a.token, { id: "@bobby", action: "request" });
    await request("/friends", b.token, { id: "alice", action: "accept" });
    const s = await request("/sessions", a.token, { kind: "moment", invitees: ["bob"] });
    await request(`/sessions/${s.id}/join`, b.token, {});
    await request(`/sessions/${s.id}/ready`, a.token, { ready: true });
    await request(`/sessions/${s.id}/ready`, b.token, { ready: true });
    const capture = await request(`/sessions/${s.id}/schedule`, a.token, {});
    const bytes = Buffer.alloc(250000, 73);
    const sha = createHash("sha256").update(bytes).digest("hex");
    const upload = await runtime.dispatchFetch(`https://sense.test/sessions/${s.id}/clips`, {
      method: "POST", body: bytes,
      headers: { Authorization: "Bearer " + a.token, "Content-Length": String(bytes.length), "X-SHA256": sha, "X-Captured-At": String(capture.captureAt), "X-Clock-Uncertainty": "10" },
    });
    const clip = await upload.json();
    assert.equal(upload.status, 201, JSON.stringify(clip));
    await runtime.dispose();
    runtime = new Miniflare(options);
    assert.equal((await request("/me", a.token)).people[0].relationship, "ACCEPTED");
    const download = await runtime.dispatchFetch(`https://sense.test/sessions/${s.id}/clips/${clip.id}`, { headers: { Authorization: "Bearer " + b.token } });
    assert.equal(download.status, 200);
    assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes);
    await request("/friends", a.token, { id: "bob", action: "block" });
    const blocked = await runtime.dispatchFetch(`https://sense.test/sessions/${s.id}/clips/${clip.id}`, { headers: { Authorization: "Bearer " + b.token } });
    assert.equal(blocked.status, 403);
    await blocked.body?.cancel();
  } finally {
    await runtime.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});
