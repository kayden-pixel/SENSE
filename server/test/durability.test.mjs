import test from "node:test";
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { createApp } from "../src/server.mjs";

test("accounts, friends, Radar authorization, Moment metadata and ejection retries survive a disk restart", async () => {
  const folder = mkdtempSync(path.join(tmpdir(), "sense-durable-journey-"));
  const databasePath = path.join(folder, "sense.sqlite");
  let app,
    base,
    failRemoval = true;
  const removed = [];
  const start = async () => {
    app = createApp({
      databasePath,
      mediaPath: path.join(folder, "media"),
      radarSecret: "test-only-stable-radar-secret".repeat(2),
      // Backend contract tests do not establish an actual media-server connection.
      verifyLivekit: async () => true,
      livekit: {
        url: "wss://test-only.example.org",
        key: "test-key",
        secret: "a".repeat(32),
      },
      removeParticipant: async (room, user) => {
        if (failRemoval) throw Error("test media outage");
        removed.push({ room, user });
      },
    });
    await new Promise((resolve) => app.listen(0, "127.0.0.1", resolve));
    base = "http://127.0.0.1:" + app.address().port;
  };
  const request = async (route, token, body) => {
    const response = await fetch(base + route, {
      method: body === undefined ? "GET" : "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: response.status, data: await response.json() };
  };
  const password = "test-only-private-recovery-credential";
  try {
    await start();
    const register = async (username) => {
      const result = await request("/auth/register", null, {
        id: randomUUID(),
        username,
        name: username,
        password,
      });
      assert.equal(result.status, 200);
      return result.data;
    };
    const a = await register("phone-a"),
      b = await register("phone-b"),
      outsider = await register("outsider");
    assert.notEqual(a.id, b.id);
    assert.notEqual(a.token, b.token);
    assert.equal(
      (await request("/friends", a.token, { id: "phone-b", action: "request" }))
        .status,
      200,
    );
    assert.equal(
      (await request("/friends", b.token, { id: a.id, action: "accept" }))
        .status,
      200,
    );
    let person = (await request("/me", a.token)).data.people[0];
    assert.equal(person.id, b.id);
    assert.equal(person.online, true);
    const radarTokens = person.radarTokens;
    const db = new DatabaseSync(databasePath);
    db.prepare("UPDATE users SET last_seen=0 WHERE id=?").run(b.id);
    db.close();
    assert.equal((await request("/me", a.token)).data.people[0].online, false);
    assert.equal(
      (await request("/presence", b.token, { ranging: true })).status,
      200,
    );
    person = (await request("/me", a.token)).data.people[0];
    assert.equal(person.online, true);
    assert.equal(person.ranging, true);
    assert.deepEqual(person.radarTokens, radarTokens);
    assert.equal(
      (await request("/oob", outsider.token, { to: a.id, payload: "AA==" }))
        .status,
      403,
    );
    assert.equal(
      (await request("/oob", a.token, { to: b.id, payload: "AA==" })).status,
      200,
    );
    assert.equal((await request("/oob", b.token)).data.packets[0].sender, a.id);
    const voice = (
      await request("/sessions", a.token, { kind: "voice", invitees: [b.id] })
    ).data;
    assert.equal(
      (await request(`/sessions/${voice.id}/join`, b.token, {})).status,
      200,
    );
    const grant = (await request(`/sessions/${voice.id}/token`, b.token, {}))
      .data;
    const claims = JSON.parse(
      Buffer.from(grant.token.split(".")[1], "base64url"),
    );
    assert.equal(claims.video.room, voice.id);
    assert.equal(claims.sub, b.id);
    const moment = (
      await request("/sessions", a.token, { kind: "moment", invitees: [b.id] })
    ).data;
    await request(`/sessions/${moment.id}/join`, b.token, {});
    await request(`/sessions/${moment.id}/ready`, a.token, { ready: true });
    assert.equal(
      (await request(`/sessions/${moment.id}/schedule`, a.token, {})).status,
      409,
    );
    await request(`/sessions/${moment.id}/ready`, b.token, { ready: true });
    const scheduled = await request(
      `/sessions/${moment.id}/schedule`,
      a.token,
      {},
    );
    assert.equal(scheduled.status, 200);
    const bytes = Buffer.from("HTTP transfer fixture; not a camera capture");
    const sha = createHash("sha256").update(bytes).digest("hex");
    const capturedAt = scheduled.data.captureAt;
    const upload = await fetch(base + `/sessions/${moment.id}/clips`, {
      method: "POST",
      body: bytes,
      headers: {
        Authorization: "Bearer " + a.token,
        "Content-Type": "video/mp4",
        "X-SHA256": sha,
        "X-Captured-At": String(capturedAt),
        "X-Clock-Uncertainty": "37",
      },
    });
    assert.equal(upload.status, 201);
    const clip = await upload.json();
    await new Promise((resolve) => app.close(resolve));
    await start();
    assert.equal(
      (await request("/auth/login", null, { username: "phone-a", password }))
        .data.id,
      a.id,
    );
    assert.equal(
      (await request("/auth/login", null, { username: "phone-b", password }))
        .data.id,
      b.id,
    );
    assert.equal((await request("/me", a.token)).data.people[0].id, b.id);
    const persisted = await request(`/sessions/${moment.id}`, b.token);
    assert.equal(persisted.data.id, moment.id);
    assert.equal(persisted.data.captureAt, capturedAt);
    assert.deepEqual(persisted.data.clips[0], {
      id: clip.id,
      user: a.id,
      size: bytes.length,
      captured_at: capturedAt,
      uncertainty: 37,
      sha,
    });
    const download = await fetch(
      base + `/sessions/${moment.id}/clips/${clip.id}`,
      { headers: { Authorization: "Bearer " + b.token } },
    );
    assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes);
    assert.equal(
      (await request("/friends", a.token, { id: b.id, action: "block" }))
        .status,
      503,
    );
    await new Promise((resolve) => app.close(resolve));
    failRemoval = false;
    await start();
    assert.equal(
      (await request(`/sessions/${voice.id}/token`, b.token, {})).status,
      403,
    );
    assert.equal(
      (await request(`/sessions/${moment.id}`, b.token)).status,
      403,
    );
    assert.equal(
      (await request("/friends", a.token, { id: b.id, action: "block" }))
        .status,
      200,
    );
    assert.ok(
      removed.some((task) => task.room === voice.id && task.user === b.id),
    );
    const inspection = new DatabaseSync(databasePath);
    assert.equal(
      inspection.prepare("SELECT COUNT(*) AS n FROM voice_revocations").get().n,
      0,
    );
    assert.equal(
      inspection
        .prepare("SELECT COUNT(*) AS n FROM tokens WHERE hash=?")
        .get(a.token).n,
      0,
    );
    inspection.close();
    assert.equal((await request("/logout", outsider.token, {})).status, 200);
    assert.equal((await request("/me", outsider.token)).status, 401);
  } finally {
    if (app?.listening) await new Promise((resolve) => app.close(resolve));
    rmSync(folder, { recursive: true, force: true });
  }
});

test("revoking a credential interrupts an in-flight clip download", async () => {
  const folder = mkdtempSync(path.join(tmpdir(), "sense-download-revoke-"));
  const databasePath = path.join(folder, "sense.sqlite"),
    media = path.join(folder, "media");
  const app = createApp({ databasePath, mediaPath: media });
  await new Promise((resolve) => app.listen(0, "127.0.0.1", resolve));
  const base = "http://127.0.0.1:" + app.address().port;
  let db;
  try {
    const registration = await fetch(base + "/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        id: "download-owner",
        name: "Download test",
        password: "test-only-private-credential",
      }),
    });
    const account = await registration.json();
    db = new DatabaseSync(databasePath);
    const room = randomUUID(),
      clip = randomUUID(),
      bytes = Buffer.alloc(32 * 1024 * 1024, 7);
    db.prepare("INSERT INTO sessions VALUES(?,?,?,?,NULL)").run(
      room,
      "moment",
      account.id,
      Date.now() + 60000,
    );
    db.prepare("INSERT INTO members VALUES(?,?,1,0)").run(room, account.id);
    db.prepare("INSERT INTO clips VALUES(?,?,?,?,?,?,?)").run(
      clip,
      room,
      account.id,
      bytes.length,
      createHash("sha256").update(bytes).digest("hex"),
      Date.now(),
      10,
    );
    writeFileSync(path.join(media, clip + ".mp4"), bytes);
    const response = await fetch(base + `/sessions/${room}/clips/${clip}`, {
      headers: { Authorization: "Bearer " + account.token },
    });
    assert.equal(response.status, 200);
    const reader = response.body.getReader();
    let received = (await reader.read()).value.length;
    db.prepare("DELETE FROM tokens WHERE user=?").run(account.id);
    let interrupted = false;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        received += value.length;
      }
    } catch {
      interrupted = true;
    }
    assert.equal(interrupted, true);
    assert.ok(received < bytes.length);
  } finally {
    db?.close();
    await new Promise((resolve) => app.close(resolve));
    rmSync(folder, { recursive: true, force: true });
  }
});
