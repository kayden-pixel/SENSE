import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { createApp } from "../src/server.mjs";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { createHash, pbkdf2Sync } from "node:crypto";

test("schema 1 upgrades preserve profiles and hashed session credentials", async () => {
  const folder = mkdtempSync(path.join(tmpdir(), "sense-migration-"));
  const databasePath = path.join(folder, "legacy.sqlite");
  const legacy = new DatabaseSync(databasePath);
  legacy.exec(
    "CREATE TABLE users(id TEXT PRIMARY KEY,name TEXT NOT NULL,password TEXT NOT NULL,salt TEXT NOT NULL,last_seen INTEGER NOT NULL);" +
      "CREATE TABLE tokens(hash TEXT PRIMARY KEY,user TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL);" +
      "CREATE TABLE relationships(sender TEXT NOT NULL REFERENCES users(id),receiver TEXT NOT NULL REFERENCES users(id),state TEXT NOT NULL,PRIMARY KEY(sender,receiver));" +
      "CREATE TABLE sessions(id TEXT PRIMARY KEY,kind TEXT NOT NULL,host TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL,capture_at INTEGER);" +
      "CREATE TABLE members(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,user TEXT NOT NULL REFERENCES users(id),joined INTEGER NOT NULL DEFAULT 0,ready INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(session,user));" +
      "CREATE TABLE clips(id TEXT PRIMARY KEY,session TEXT NOT NULL REFERENCES sessions(id),user TEXT NOT NULL REFERENCES users(id),size INTEGER NOT NULL,sha TEXT NOT NULL,captured_at INTEGER NOT NULL,uncertainty INTEGER NOT NULL);" +
      "PRAGMA user_version=1;",
  );
  const legacyHash = pbkdf2Sync(
    "correct-horse-battery",
    "preserved-salt",
    210000,
    32,
    "sha256",
  ).toString("hex");
  legacy
    .prepare("INSERT INTO users VALUES(?,?,?,?,?)")
    .run(
      "legacy-user",
      "Preserved name",
      legacyHash,
      "preserved-salt",
      Date.now(),
    );
  const token = "public-migration-test-fixture";
  legacy
    .prepare("INSERT INTO tokens VALUES(?,?,?)")
    .run(
      createHash("sha256").update(token).digest("hex"),
      "legacy-user",
      Date.now() + 60000,
    );
  legacy.close();
  const app = createApp({
    databasePath,
    mediaPath: path.join(folder, "media"),
    radarSecret: "migration-test-only".repeat(3),
  });
  await new Promise((resolve) => app.listen(0, "127.0.0.1", resolve));
  try {
    const response = await fetch(
      "http://127.0.0.1:" + app.address().port + "/me",
      { headers: { Authorization: "Bearer " + token } },
    );
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json()).people, []);
    const login = await fetch(
      "http://127.0.0.1:" + app.address().port + "/auth/login",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id: "legacy-user",
          password: "correct-horse-battery",
        }),
      },
    );
    assert.equal(login.status, 200);
    assert.ok((await login.json()).token);
  } finally {
    await new Promise((resolve) => app.close(resolve));
  }
  const upgraded = new DatabaseSync(databasePath);
  try {
    const user = upgraded
      .prepare("SELECT * FROM users WHERE id=?")
      .get("legacy-user");
    assert.equal(user.name, "Preserved name");
    assert.notEqual(user.password, legacyHash);
    assert.equal(user.password_iterations, 600000);
    assert.equal(
      user.password,
      pbkdf2Sync(
        "correct-horse-battery",
        user.salt,
        600000,
        32,
        "sha256",
      ).toString("hex"),
    );
    assert.equal(user.avatar, null);
    assert.equal(user.ranging, 0);
    assert.equal(upgraded.prepare("PRAGMA user_version").get().user_version, 6);
    assert.equal(user.username, null);
    assert.equal(
      upgraded.prepare("SELECT COUNT(*) AS n FROM tokens").get().n,
      2,
    );
  } finally {
    upgraded.close();
    rmSync(folder, { recursive: true, force: true });
  }
});

test("an older server refuses a future schema without changing its data", () => {
  const folder = mkdtempSync(path.join(tmpdir(), "sense-future-schema-"));
  const databasePath = path.join(folder, "future.sqlite");
  try {
    const db = new DatabaseSync(databasePath);
    db.exec(
      "CREATE TABLE future_data(value TEXT); INSERT INTO future_data VALUES('preserved'); PRAGMA user_version=999;",
    );
    db.close();
    assert.throws(
      () => createApp({ databasePath, mediaPath: path.join(folder, "media") }),
      /unsafe rollback/,
    );
    const reopened = new DatabaseSync(databasePath);
    assert.equal(
      reopened.prepare("PRAGMA user_version").get().user_version,
      999,
    );
    assert.equal(
      reopened.prepare("SELECT value FROM future_data").get().value,
      "preserved",
    );
    reopened.close();
  } finally {
    rmSync(folder, { recursive: true, force: true });
  }
});
