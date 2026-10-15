import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { createApp } from "../src/server.mjs";
import {
  verifyLiveKit,
  validateProductionEnvironment,
} from "../src/livekit.mjs";

test("media readiness proves authorization with a short-lived least-privilege grant", async () => {
  const settings = {
    url: "wss://voice.example.org",
    key: "test-api-key",
    secret: "a".repeat(32),
  };
  let calls = 0;
  const request = async (url, options) => {
    calls++;
    assert.equal(
      url,
      "https://voice.example.org/twirp/livekit.RoomService/ListRooms",
    );
    assert.equal(options.redirect, "error");
    const token = options.headers.Authorization.slice(7);
    const [header, payload, signature] = token.split(".");
    assert.equal(
      signature,
      createHmac("sha256", settings.secret)
        .update(header + "." + payload)
        .digest("base64url"),
    );
    const grant = JSON.parse(Buffer.from(payload, "base64url"));
    assert.deepEqual(grant.video, { roomList: true });
    assert.ok(grant.exp - grant.iat <= 30);
    return new Response("{}", { status: 401 });
  };
  assert.equal(await verifyLiveKit(settings, undefined, request), false);
  assert.equal(calls, 1);
  assert.equal(
    await verifyLiveKit(
      { ...settings, url: "wss://user:secret@voice.example.org" },
      undefined,
      request,
    ),
    false,
  );
  assert.equal(calls, 1);
});

test("production refuses missing runtime secrets", () => {
  assert.throws(
    () => validateProductionEnvironment({ NODE_ENV: "production" }),
    /durable/,
  );
  assert.throws(
    () =>
      validateProductionEnvironment({
        NODE_ENV: "production",
        DATABASE_PATH: "/data/db",
        MEDIA_PATH: "/data/media",
        RADAR_SECRET: "a".repeat(32),
      }),
    /LiveKit/,
  );
});

test("liveness stays available when the media service is unavailable", async () => {
  const folder = mkdtempSync(path.join(tmpdir(), "sense-readiness-"));
  const app = createApp({ databasePath: ":memory:", mediaPath: folder });
  await new Promise((resolve) => app.listen(0, "127.0.0.1", resolve));
  const base = "http://127.0.0.1:" + app.address().port;
  try {
    assert.equal((await fetch(base + "/health")).status, 200);
    const response = await fetch(base + "/ready");
    assert.equal(response.status, 503);
    assert.deepEqual((await response.json()).services, {
      api: true,
      voice: false,
    });
  } finally {
    await new Promise((resolve) => app.close(resolve));
    rmSync(folder, { recursive: true, force: true });
  }
});

test("readiness probes coalesce and delayed voice authorization cannot revive a revoked session", async () => {
  const folder = mkdtempSync(path.join(tmpdir(), "sense-readiness-race-"));
  let calls = 0,
    release,
    started;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const probeStarted = new Promise((resolve) => {
    started = resolve;
  });
  const app = createApp({
    databasePath: ":memory:",
    mediaPath: folder,
    livekit: {
      url: "wss://voice.example.org",
      key: "test",
      secret: "a".repeat(32),
    },
    verifyLivekit: async () => {
      calls++;
      started();
      return gate;
    },
    removeParticipant: async () => {},
  });
  await new Promise((resolve) => app.listen(0, "127.0.0.1", resolve));
  const base = "http://127.0.0.1:" + app.address().port;
  const request = (route, body, token) =>
    fetch(base + route, {
      method: body ? "POST" : "GET",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
  try {
    const login = await (
      await request("/auth/register", {
        id: "probe-user",
        name: "Probe",
        password: "private-test-only-key",
      })
    ).json();
    const room = await (
      await request("/sessions", { kind: "voice", invitees: [] }, login.token)
    ).json();
    const tokenResponse = request(
      "/sessions/" + room.id + "/token",
      {},
      login.token,
    );
    await probeStarted;
    const checks = [request("/ready"), request("/ready")];
    assert.equal((await request("/logout", {}, login.token)).status, 200);
    release(true);
    assert.equal((await tokenResponse).status, 401);
    assert.ok(
      (await Promise.all(checks)).every((response) => response.status === 200),
    );
    assert.equal(calls, 1);
  } finally {
    release(false);
    await new Promise((resolve) => app.close(resolve));
    rmSync(folder, { recursive: true, force: true });
  }
});
