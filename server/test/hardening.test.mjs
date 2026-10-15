import test from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../src/server.mjs";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

test("block ownership, scoped media revocation, retry and input limits", async (t) => {
  const media = mkdtempSync(path.join(tmpdir(), "sense-hardening-"));
  const revoked = [];
  let fail = false;
  const app = createApp({
    databasePath: ":memory:",
    mediaPath: media,
    radarSecret: "a".repeat(32),
    livekit: {
      url: "wss://voice.example.org",
      key: "test-only",
      secret: "b".repeat(32),
    },
    removeParticipant: async (room, user) => {
      if (fail) throw Error("offline");
      revoked.push({ room, user });
    },
  });
  await new Promise((r) => app.listen(0, "127.0.0.1", r));
  const base = "http://127.0.0.1:" + app.address().port;
  const req = async (route, token, body) => {
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
    const accounts = {};
    for (const id of ["a", "b", "c"])
      accounts[id] = (
        await req("/auth/register", null, {
          id,
          name: id,
          password: "correct-horse-battery",
        })
      ).data.token;
    const friend = async (a, b) => {
      await req("/friends", accounts[a], { id: b, action: "request" });
      assert.equal(
        (await req("/friends", accounts[b], { id: a, action: "accept" }))
          .status,
        200,
      );
    };
    const room = async (host, invited) => {
      const s = (
        await req("/sessions", accounts[host], {
          kind: "voice",
          invitees: invited,
        })
      ).data;
      for (const user of invited)
        await req("/sessions/" + s.id + "/join", accounts[user], {});
      return s.id;
    };
    await friend("a", "b");
    await friend("b", "c");
    await friend("a", "c");
    const ab = await room("a", ["b"]);
    const bc = await room("b", ["c"]);
    const third = await room("c", ["a", "b"]);
    await t.test("JSON primitives and path suffixes are rejected", async () => {
      assert.equal((await req("/profile", accounts.a, null)).status, 400);
      assert.equal((await req("/profile", accounts.a, [])).status, 400);
      assert.equal(
        (await req("/sessions/" + ab + "/garbage", accounts.a)).status,
        400,
      );
      assert.equal((await req("/sessions/" + ab, accounts.a)).status, 200);
    });
    await t.test("leaving one room does not eject another room", async () => {
      revoked.length = 0;
      assert.equal(
        (await req("/sessions/" + ab + "/leave", accounts.b, {})).status,
        200,
      );
      assert.deepEqual(revoked, [{ room: ab, user: "b" }]);
      await req("/sessions/" + ab + "/join", accounts.b, {});
    });
    await t.test(
      "failed revocation persists exact room identities for retry",
      async () => {
        fail = true;
        revoked.length = 0;
        assert.equal(
          (await req("/friends", accounts.a, { id: "b", action: "block" }))
            .status,
          503,
        );
        assert.equal(
          (await req("/sessions/" + ab + "/token", accounts.b, {})).status,
          403,
        );
        fail = false;
        assert.equal(
          (await req("/friends", accounts.a, { id: "b", action: "block" }))
            .status,
          200,
        );
        assert.ok(revoked.some((r) => r.room === ab && r.user === "b"));
        assert.ok(revoked.some((r) => r.room === third && r.user === "a"));
        assert.ok(!revoked.some((r) => r.room === bc));
        assert.equal(
          (await req("/sessions/" + bc + "/token", accounts.b, {})).status,
          200,
        );
      },
    );
    await t.test(
      "another user's block survives remove and reciprocal block/unblock",
      async () => {
        await req("/friends", accounts.b, { id: "a", action: "remove" });
        assert.equal(
          (await req("/friends", accounts.b, { id: "a", action: "request" }))
            .status,
          403,
        );
        await req("/friends", accounts.b, { id: "a", action: "block" });
        await req("/friends", accounts.b, { id: "a", action: "unblock" });
        assert.equal(
          (await req("/friends", accounts.b, { id: "a", action: "request" }))
            .status,
          403,
        );
        const blocked = (await req("/me", accounts.a)).data.people.find(
          (p) => p.id === "b",
        );
        assert.equal(blocked.relationship, "BLOCKED");
        assert.equal(blocked.online, false);
        assert.equal(blocked.ranging, false);
        assert.deepEqual(blocked.radarTokens, []);
        assert.equal(blocked.avatar, null);
      },
    );
    await t.test(
      "ranging queues are bounded and payloads validated",
      async () => {
        assert.equal(
          (await req("/oob", accounts.b, { to: "c", payload: "invalid!" }))
            .status,
          400,
        );
        for (let i = 0; i < 64; i++)
          assert.equal(
            (await req("/oob", accounts.b, { to: "c", payload: "AA==" }))
              .status,
            200,
          );
        assert.equal(
          (await req("/oob", accounts.b, { to: "c", payload: "AA==" })).status,
          429,
        );
        assert.equal((await req("/oob", accounts.c)).data.packets.length, 64);
      },
    );
    await t.test(
      "concurrent registration returns conflict rather than server error",
      async () => {
        const result = await Promise.all([
          req("/auth/register", null, {
            id: "race",
            name: "Race",
            password: "correct-horse-battery",
          }),
          req("/auth/register", null, {
            id: "race",
            name: "Race",
            password: "correct-horse-battery",
          }),
        ]);
        assert.deepEqual(result.map((r) => r.status).sort(), [200, 409]);
      },
    );
    await t.test("authentication rate limit is enforced", async () => {
      let limited = false;
      for (let i = 0; i < 10; i++)
        if (
          (
            await req("/auth/login", null, {
              id: "a",
              password: "wrong-but-long-password",
            })
          ).status === 429
        )
          limited = true;
      assert.ok(limited);
    });
  } finally {
    await new Promise((r) => app.close(r));
    rmSync(media, { recursive: true, force: true });
  }
});
