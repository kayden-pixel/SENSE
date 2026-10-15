import test from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../src/server.mjs";
import { DatabaseSync } from "node:sqlite";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

test("expired voice sessions queue media ejection and deny new grants", async (t) => {
  t.mock.timers.enable({ apis: ["setInterval"] });
  const folder = mkdtempSync(path.join(tmpdir(), "sense-expiry-"));
  const databasePath = path.join(folder, "sense.sqlite");
  const removed = [];
  const app = createApp({
    databasePath,
    mediaPath: path.join(folder, "media"),
    removeParticipant: async (room, user) => removed.push({ room, user }),
    verifyLivekit: async () => true,
    livekit: {
      url: "wss://voice.example.org",
      key: "test-only",
      secret: "a".repeat(32),
    },
  });
  await new Promise((r) => app.listen(0, "127.0.0.1", r));
  const base = "http://127.0.0.1:" + app.address().port;
  const post = async (route, body, token) => {
    const r = await fetch(base + route, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: JSON.stringify(body),
    });
    return { status: r.status, data: await r.json() };
  };
  try {
    const token = (
      await post("/auth/register", {
        id: "owner",
        name: "Owner",
        password: "correct-horse-battery",
      })
    ).data.token;
    const session = (
      await post("/sessions", { kind: "voice", invitees: [] }, token)
    ).data.id;
    const db = new DatabaseSync(databasePath);
    try {
      db.prepare("UPDATE sessions SET expires=? WHERE id=?").run(
        Date.now() + 60000,
        session,
      );
      const grant = (await post("/sessions/" + session + "/token", {}, token))
        .data;
      const claims = JSON.parse(
        Buffer.from(grant.token.split(".")[1], "base64url"),
      );
      assert.ok(claims.exp <= Math.floor((Date.now() + 60000) / 1000));
      db.prepare("UPDATE sessions SET expires=? WHERE id=?").run(
        Date.now() - 1,
        session,
      );
    } finally {
      db.close();
    }
    assert.equal(
      (await post("/sessions/" + session + "/token", {}, token)).status,
      403,
    );
    t.mock.timers.tick(60000);
    await new Promise(setImmediate);
    assert.deepEqual(removed, [{ room: session, user: "owner" }]);
  } finally {
    await new Promise((r) => app.close(r));
    t.mock.timers.reset();
    rmSync(folder, { recursive: true, force: true });
  }
});
