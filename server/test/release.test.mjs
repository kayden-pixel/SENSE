import test from "node:test";
import assert from "node:assert/strict";
import {
  productionUrl,
  verifyProductionEndpoint,
  verifySigning,
} from "../../tools/check_release.mjs";

test("release refuses unconfigured, insecure, local and placeholder endpoints", () => {
  for (const value of [
    "",
    "http://sense.onrender.com",
    "https://localhost",
    "https://127.0.0.1",
    "https://192.168.1.1",
    "https://api.example.org",
    "https://example.org",
    "https://user:secret@sense.onrender.com",
    "https://sense.onrender.com?secret=private",
  ])
    assert.throws(() => productionUrl("backendUrl=" + value));
  assert.equal(
    productionUrl("backendUrl=https://sense.onrender.com/"),
    "https://sense.onrender.com",
  );
});

test("successful liveness alone cannot authorize a production release", async () => {
  await assert.rejects(
    verifyProductionEndpoint(
      "https://sense.onrender.com",
      async () =>
        new Response(JSON.stringify({ ok: true }), {
          headers: { "Content-Type": "application/json" },
        }),
    ),
  );
  await assert.rejects(
    verifyProductionEndpoint(
      "https://sense.onrender.com",
      async () => new Response("<html>offline</html>", { status: 503 }),
    ),
  );
  await assert.rejects(
    verifyProductionEndpoint(
      "https://sense.onrender.com",
      async () =>
        new Response(
          JSON.stringify({
            ready: true,
            services: { api: true, voice: false },
          }),
          { headers: { "Content-Type": "application/json" } },
        ),
    ),
  );
  assert.equal(
    await verifyProductionEndpoint(
      "https://sense.onrender.com",
      async () =>
        new Response(
          JSON.stringify({ ready: true, services: { api: true, voice: true } }),
          { headers: { "Content-Type": "application/json" } },
        ),
    ),
    true,
  );
});

test("a debug build or missing signing secret cannot be distributed as a production release", () => {
  assert.throws(() => verifySigning({}), /signing/);
  assert.throws(
    () =>
      verifySigning({ SENSE_KEYSTORE_PATH: "/nonexistent/private/key.jks" }),
    /signing/,
  );
});
