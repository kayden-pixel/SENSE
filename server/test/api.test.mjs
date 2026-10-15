import test from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../src/server.mjs";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";

test("real authorization, friend state, room grants and synchronized capture", async (t) => {
  const media = mkdtempSync(path.join(tmpdir(), "sense-test-"));
  const revoked = [];
  const app = createApp({
    removeParticipant: async (room, user) => revoked.push({ room, user }),
    databasePath: ":memory:",
    mediaPath: media,
    radarSecret: "a".repeat(32),
    verifyLivekit: async () => true,
    livekit: {
      url: "wss://voice.example.org",
      key: "test-only",
      secret: "b".repeat(32),
    },
  });
  await new Promise((resolve) => app.listen(0, "127.0.0.1", resolve));
  const base = "http://127.0.0.1:" + app.address().port;
  const request = async (route, token, body) => {
    const r = await fetch(base + route, {
      method: body === undefined ? "GET" : "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: r.status, data: await r.json() };
  };
  try {
    const register = async (id) =>
      request("/auth/register", null, {
        id,
        name: id,
        password: "correct-horse-battery",
      });
    const a = (await register("alice")).data.token,
      b = (await register("bob")).data.token,
      c = (await register("carol")).data.token;
    await t.test("passwords and malformed requests are rejected", async () => {
      assert.equal(
        (
          await request("/auth/login", null, {
            id: "alice",
            password: "wrong-but-long-password",
          })
        ).status,
        401,
      );
      assert.equal((await request("/me")).status, 401);
      assert.equal(
        (await request("/friends", a, { id: "alice", action: "request" }))
          .status,
        400,
      );
    });
    await t.test("requests become accepted only by the recipient", async () => {
      assert.equal(
        (await request("/friends", a, { id: "bob", action: "request" })).status,
        200,
      );
      assert.equal(
        (await request("/me", a)).data.people[0].relationship,
        "OUTGOING",
      );
      assert.equal(
        (await request("/me", b)).data.people[0].relationship,
        "INCOMING",
      );
      assert.equal(
        (await request("/friends", a, { id: "bob", action: "accept" })).status,
        409,
      );
      assert.equal(
        (await request("/friends", b, { id: "alice", action: "accept" }))
          .status,
        200,
      );
      assert.equal(
        (await request("/me", a)).data.people[0].radarTokens[0].length,
        16,
      );
    });
    const voice = (
      await request("/sessions", a, { kind: "voice", invitees: ["bob"] })
    ).data;
    await t.test("voice tokens require an invited, joined member", async () => {
      assert.equal(
        (await request("/sessions/" + voice.id + "/token", c, {})).status,
        403,
      );
      assert.equal(
        (await request("/sessions/" + voice.id + "/token", b, {})).status,
        403,
      );
      await request("/sessions/" + voice.id + "/join", b, {});
      const grant = (await request("/sessions/" + voice.id + "/token", b, {}))
        .data;
      assert.ok(grant.url.startsWith("wss://"));
      const claims = JSON.parse(
        Buffer.from(grant.token.split(".")[1], "base64url"),
      );
      assert.equal(claims.sub, "bob");
      assert.equal(claims.video.room, voice.id);
      assert.deepEqual(claims.video.canPublishSources, ["microphone"]);
      assert.ok(claims.exp - claims.iat <= 120);
    });
    const moment = (
      await request("/sessions", a, { kind: "moment", invitees: ["bob"] })
    ).data;
    await t.test(
      "countdown waits for joined phones and host authorization",
      async () => {
        await request("/sessions/" + moment.id + "/join", b, {});
        assert.equal(
          (await request("/sessions/" + moment.id + "/schedule", a, {})).status,
          409,
        );
        await request("/sessions/" + moment.id + "/ready", a, { ready: true });
        await request("/sessions/" + moment.id + "/ready", b, { ready: true });
        assert.equal(
          (await request("/sessions/" + moment.id + "/schedule", b, {})).status,
          409,
        );
        const schedule = await request(
          "/sessions/" + moment.id + "/schedule",
          a,
          {},
        );
        assert.equal(schedule.status, 200);
        assert.ok(schedule.data.captureAt > Date.now() + 6000);
        assert.equal(
          (await request("/sessions/" + moment.id + "/schedule", a, {})).status,
          409,
        );
      },
    );
    await t.test(
      "capture integrity and transfer membership are enforced",
      async () => {
        const body = Buffer.from("test-clip-payload");
        const sha = createHash("sha256").update(body).digest("hex");
        const upload = async (token, digest) =>
          fetch(base + "/sessions/" + moment.id + "/clips", {
            method: "POST",
            headers: {
              Authorization: "Bearer " + token,
              "Content-Type": "video/mp4",
              "X-SHA256": digest,
              "X-Captured-At": String(Date.now()),
              "X-Clock-Uncertainty": "20",
            },
            body,
          });
        assert.equal((await upload(c, sha)).status, 403);
        assert.equal((await upload(a, "0".repeat(64))).status, 400);
        const r = await upload(a, sha);
        assert.equal(r.status, 201);
        const clip = await r.json();
        const download = await fetch(
          base + "/sessions/" + moment.id + "/clips/" + clip.id,
          { headers: { Authorization: "Bearer " + b } },
        );
        assert.equal(download.status, 200);
        assert.deepEqual(Buffer.from(await download.arrayBuffer()), body);
      },
    );
    await t.test("only friends exchange ranging packets", async () => {
      assert.equal(
        (await request("/oob", c, { to: "alice", payload: "AA==" })).status,
        403,
      );
      assert.equal(
        (await request("/oob", a, { to: "bob", payload: "AA==" })).status,
        200,
      );
      const packets = (await request("/oob", b)).data.packets;
      assert.equal(packets[0].sender, "alice");
      assert.equal((await request("/oob", b)).data.packets.length, 0);
    });
    await t.test("blocking revokes friendship and room access", async () => {
      await request("/friends", a, { id: "bob", action: "block" });
      assert.equal(
        (await request("/me", a)).data.people[0].relationship,
        "BLOCKED",
      );
      assert.ok(revoked.some((r) => r.room === voice.id && r.user === "bob"));
      assert.equal(
        (await request("/friends", b, { id: "alice", action: "request" }))
          .status,
        403,
      );
      assert.equal(
        (await request("/sessions/" + voice.id + "/token", b, {})).status,
        403,
      );
      assert.equal((await request("/sessions/" + moment.id, b)).status, 403);
      assert.equal(
        (await request("/oob", b, { to: "alice", payload: "AA==" })).status,
        403,
      );
    });
    await t.test("sign-out revokes session credentials", async () => {
      await request("/logout", c, {});
      assert.equal((await request("/me", c)).status, 401);
    });
  } finally {
    await new Promise((resolve) => app.close(resolve));
    rmSync(media, { recursive: true, force: true });
  }
});
