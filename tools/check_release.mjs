import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";

export function productionUrl(configuration) {
  const value =
    configuration
      .split(/\r?\n/)
      .find((line) => /^backendUrl\s*=/.test(line))
      ?.split("=")
      .slice(1)
      .join("=")
      .trim() ?? "";
  if (!value)
    throw Error(
      "Production release blocked: the official HTTPS service has not been deployed.",
    );
  const url = new URL(value);
  const host = url.hostname.toLowerCase();
  if (
    url.protocol !== "https:" ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    !host.includes(".") ||
    ["example.com", "example.org", "example.net"].some(
      (reserved) => host === reserved || host.endsWith("." + reserved),
    ) ||
    /(^|\.)(localhost|local|internal|invalid|example|test)$/.test(host) ||
    /^(127\.|10\.|192\.168\.|169\.254\.|0\.|172\.(1[6-9]|2\d|3[01])\.)/.test(
      host,
    )
  )
    throw Error(
      "Production release requires a public HTTPS endpoint without credentials.",
    );
  return url.href.replace(/\/$/, "");
}

export async function verifyProductionEndpoint(url, request = fetch) {
  const response = await request(url + "/ready", {
    redirect: "error",
    signal: AbortSignal.timeout(10000),
  });
  if (
    !response.ok ||
    !response.headers.get("content-type")?.includes("application/json")
  ) {
    await response.body?.cancel();
    throw Error(
      "Production readiness failed: the API and LiveKit must both be available.",
    );
  }
  const reader = response.body.getReader();
  let body = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (body.length + value.length > 8192)
        throw Error("Invalid readiness response");
      body += new TextDecoder().decode(value);
    }
  } finally {
    await reader.cancel();
  }
  const state = JSON.parse(body);
  if (
    state.ready !== true ||
    state.services?.api !== true ||
    state.services?.voice !== true
  )
    throw Error(
      "Production readiness failed: voice authorization is unavailable.",
    );
  return true;
}

export function verifySigning(env) {
  if (
    !env.SENSE_KEYSTORE_PATH ||
    !existsSync(env.SENSE_KEYSTORE_PATH) ||
    !env.SENSE_KEYSTORE_PASSWORD ||
    !env.SENSE_KEY_ALIAS ||
    !env.SENSE_KEY_PASSWORD
  )
    throw Error(
      "Production release blocked: private release signing is not provisioned.",
    );
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const endpointOnly = process.argv.includes("--endpoint-only");
    const config = process.argv.slice(2).find((arg) => !arg.startsWith("--"));
    const url = productionUrl(readFileSync(config ?? "config/production.properties", "utf8"));
    await verifyProductionEndpoint(url);
    if (!endpointOnly) verifySigning(process.env);
    console.log(
      endpointOnly ? "Production endpoint and voice readiness verified." :
        "Production endpoint, voice readiness and release signing verified.",
    );
  } catch {
    console.error(
      "Production release blocked: an official ready HTTPS service and private release signing are required.",
    );
    process.exitCode = 1;
  }
}
