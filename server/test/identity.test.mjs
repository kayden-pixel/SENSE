import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { createApp } from "../src/server.mjs";
import { prepareOwner } from "../tools/prepare-owner.mjs";

test("prepared owner, distinct friends, private recovery and persistent username ownership", async () => {
  const folder = mkdtempSync(path.join(tmpdir(), "sense-identity-"));
  const privateDir = path.join(folder, "private");
  const owner = prepareOwner(privateDir, "kayden-pixel");
  const privateText = readFileSync(
    path.join(privateDir, "owner-access.private.txt"),
    "utf8",
  );
  const recovery = privateText.match(/Recovery key: ([\w-]+)/)[1];
  assert.ok(!JSON.stringify(owner).includes(recovery));
  assert.throws(() => prepareOwner(privateDir, "kayden-pixel"), /EEXIST/);
  const config = {
    databasePath: path.join(folder, "sense.sqlite"),
    mediaPath: path.join(folder, "media"),
    ownerAccountFile: path.join(privateDir, "owner.private.json"),
    radarSecret: "test-only".repeat(8),
  };
  let app;
  const start = async () => {
    app = createApp(config);
    await new Promise((resolve) => app.listen(0, "127.0.0.1", resolve));
    return "http://127.0.0.1:" + app.address().port;
  };
  let base = await start();
  const request = async (route, body, token) => {
    const response = await fetch(base + route, {
      method: body ? "POST" : "GET",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: response.status, data: await response.json() };
  };
  try {
    assert.equal((await request("/me")).status, 401);
    const ownerLogin = await request("/auth/login", {
      username: "KAYDEN-PIXEL",
      password: recovery,
    });
    assert.equal(ownerLogin.status, 200);
    assert.equal(ownerLogin.data.id, owner.id);
    const friend = await request("/auth/register", {
      id: "distinct-friend-identity",
      username: "a-friend",
      name: "Friend",
      password: "a-private-random-device-key",
    });
    assert.equal(friend.status, 200);
    assert.notEqual(friend.data.id, ownerLogin.data.id);
    assert.notEqual(friend.data.token, ownerLogin.data.token);
    assert.equal(
      (
        await request("/auth/register", {
          id: "attacker",
          username: "Kayden-Pixel",
          name: "Attacker",
          password: "a-different-random-device-key",
        })
      ).status,
      409,
    );
    assert.equal(
      (
        await request("/auth/login", {
          username: "kayden-pixel",
          password: "a-different-random-device-key",
        })
      ).status,
      401,
    );
    assert.equal(
      (
        await request("/auth/register", {
          id: "invalid-name",
          username: "invalid/name",
          name: "Invalid",
          password: "long-private-device-key",
        })
      ).status,
      400,
    );
    assert.equal(
      (
        await request(
          "/friends",
          { id: "@KAYDEN-PIXEL", action: "request" },
          friend.data.token,
        )
      ).status,
      200,
    );
    assert.equal(
      (await request("/me", undefined, ownerLogin.data.token)).data.people[0]
        .id,
      friend.data.id,
    );
    await new Promise((resolve) => app.close(resolve));
    base = await start();
    assert.equal(
      (
        await request("/auth/login", {
          username: "kayden-pixel",
          password: recovery,
        })
      ).data.id,
      owner.id,
    );
    assert.equal(
      (
        await request("/auth/login", {
          username: "a-friend",
          password: "a-private-random-device-key",
        })
      ).data.id,
      friend.data.id,
    );
  } finally {
    if (app?.listening) await new Promise((resolve) => app.close(resolve));
    rmSync(folder, { recursive: true, force: true });
  }
});

test("username uniqueness survives simultaneous registrations", async () => {
  const folder = mkdtempSync(path.join(tmpdir(), "sense-identity-race-"));
  const app = createApp({ databasePath: ":memory:", mediaPath: folder });
  await new Promise((resolve) => app.listen(0, "127.0.0.1", resolve));
  try {
    const results = await Promise.all(
      ["one", "two"].map((id) =>
        fetch("http://127.0.0.1:" + app.address().port + "/auth/register", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            id,
            name: id,
            username: "same-username",
            password: "a-valid-private-device-key",
          }),
        }).then(async (r) => {
          await r.text();
          return r.status;
        }),
      ),
    );
    assert.deepEqual(results.sort(), [200, 409]);
  } finally {
    await new Promise((resolve) => app.close(resolve));
    rmSync(folder, { recursive: true, force: true });
  }
});
