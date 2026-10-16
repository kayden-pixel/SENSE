import test from "node:test";
import assert from "node:assert/strict";
import { pbkdf2Sync } from "node:crypto";
import { derivePassword } from "./password.mjs";

test("Worker derivation matches current and legacy Node password hashes exactly", async () => {
  for (const rounds of [210000, 600000]) {
    const password = "private-test-key-ä-123456789";
    const salt = "0123456789abcdef0123456789abcdef";
    assert.deepEqual(await derivePassword(password, salt, rounds, 32, "sha256"),
      pbkdf2Sync(password, salt, rounds, 32, "sha256"));
  }
});
