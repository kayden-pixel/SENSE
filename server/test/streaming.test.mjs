import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { DatabaseSync } from "node:sqlite";
import { createHash } from "node:crypto";
import { mkdtempSync, rmSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as pause } from "node:timers/promises";
import { createApp } from "../src/server.mjs";

test("revoked credentials cannot finish an already-authorized JSON or media stream", async (t) => {
  const folder = mkdtempSync(path.join(tmpdir(), "sense-stream-"));
  const dbPath = path.join(folder, "sense.sqlite"),
    media = path.join(folder, "media");
  const app = createApp({ databasePath: dbPath, mediaPath: media });
  await new Promise((r) => app.listen(0, "127.0.0.1", r));
  const base = "http://127.0.0.1:" + app.address().port;
  const db = new DatabaseSync(dbPath);
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
  const account = {
    id: "owner",
    name: "Original name",
    password: "correct-horse-battery",
  };
  let token = (await post("/auth/register", account)).data.token;
  let pending;
  async function waitFor(predicate) {
    const deadline = Date.now() + 3000;
    while (!predicate()) {
      assert.ok(Date.now() < deadline, "server did not begin reading stream");
      await pause(5);
    }
  }
  function stream(route, body, extra = {}) {
    let resolve, reject;
    const result = new Promise((yes, no) => {
      resolve = yes;
      reject = no;
    });
    pending = http.request(
      base + route,
      {
        method: "POST",
        headers: {
          Authorization: "Bearer " + token,
          "Content-Length": body.length,
          ...extra,
        },
      },
      (r) => {
        const chunks = [];
        r.on("data", (c) => chunks.push(c));
        r.on("end", () =>
          resolve({
            status: r.statusCode,
            body: Buffer.concat(chunks).toString(),
          }),
        );
      },
    );
    pending.on("error", reject);
    pending.setTimeout(5000, () => pending.destroy(Error("stream timeout")));
    pending.write(body.subarray(0, 1));
    return { result, finish: () => pending.end(body.subarray(1)) };
  }
  try {
    await t.test(
      "JSON request checks credential again after body arrives",
      async () => {
        db.prepare("UPDATE users SET last_seen=0 WHERE id='owner'").run();
        const body = Buffer.from(
          JSON.stringify({ name: "Unauthorized mutation" }),
        );
        const active = stream("/profile", body, {
          "Content-Type": "application/json",
        });
        await waitFor(
          () =>
            db.prepare("SELECT last_seen FROM users WHERE id='owner'").get()
              .last_seen > 0,
        );
        assert.equal((await post("/logout", {}, token)).status, 200);
        active.finish();
        assert.equal((await active.result).status, 401);
        const login = await post("/auth/login", account);
        assert.equal(login.status, 200);
        assert.equal(login.data.name, "Original name");
        token = login.data.token;
      },
    );
    await t.test(
      "media transfer rechecks authorization before committing",
      async () => {
        const room = (
          await post("/sessions", { kind: "moment", invitees: [] }, token)
        ).data.id;
        await post("/sessions/" + room + "/ready", { ready: true }, token);
        await post("/sessions/" + room + "/schedule", {}, token);
        const body = Buffer.from("public-test-capture-bytes");
        const active = stream("/sessions/" + room + "/clips", body, {
          "Content-Type": "video/mp4",
          "X-SHA256": createHash("sha256").update(body).digest("hex"),
          "X-Captured-At": Date.now(),
          "X-Clock-Uncertainty": 20,
        });
        await waitFor(() => readdirSync(media).length > 0);
        assert.equal((await post("/logout", {}, token)).status, 200);
        active.finish();
        assert.equal((await active.result).status, 403);
        assert.equal(db.prepare("SELECT COUNT(*) AS n FROM clips").get().n, 0);
        assert.deepEqual(readdirSync(media), []);
      },
    );
  } finally {
    pending?.destroy();
    app.closeAllConnections();
    await new Promise((r) => app.close(r));
    db.close();
    rmSync(folder, { recursive: true, force: true });
  }
});
