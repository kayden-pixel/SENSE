import { createHmac } from "node:crypto";
import path from "node:path";

export function signLiveKitToken(claims, secret) {
  const encode = (value) =>
    Buffer.from(JSON.stringify(value)).toString("base64url");
  const input = encode({ alg: "HS256", typ: "JWT" }) + "." + encode(claims);
  return (
    input + "." + createHmac("sha256", secret).update(input).digest("base64url")
  );
}

export function validLiveKitSettings(settings) {
  try {
    const url = new URL(settings.url);
    return (
      url.protocol === "wss:" &&
      !url.username &&
      !url.password &&
      !url.search &&
      !url.hash &&
      url.pathname === "/" &&
      typeof settings.key === "string" &&
      settings.key.length > 0 &&
      typeof settings.secret === "string" &&
      settings.secret.length >= 32
    );
  } catch {
    return false;
  }
}

// Checks real server authorization; the presence of three strings is insufficient.
export async function verifyLiveKit(settings, signal, request = fetch) {
  if (!validLiveKitSettings(settings)) return false;
  const now = Math.floor(Date.now() / 1000);
  const token = signLiveKitToken(
    {
      iss: settings.key,
      sub: "sense-readiness",
      iat: now,
      nbf: now - 5,
      exp: now + 30,
      video: { roomList: true },
    },
    settings.secret,
  );
  try {
    const response = await request(
      settings.url.replace(/^wss:/, "https:").replace(/\/$/, "") +
        "/twirp/livekit.RoomService/ListRooms",
      {
        method: "POST",
        redirect: "manual",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + token,
        },
        body: "{}",
        signal: AbortSignal.any([
          AbortSignal.timeout(5000),
          ...(signal ? [signal] : []),
        ]),
      },
    );
    await response.body?.cancel();
    return response.ok;
  } catch {
    return false;
  }
}

export async function removeLiveKitParticipant(
  settings,
  room,
  identity,
  signal,
  request = fetch,
) {
  if (!validLiveKitSettings(settings)) throw Error("Media server unavailable");
  const now = Math.floor(Date.now() / 1000);
  const token = signLiveKitToken(
    {
      iss: settings.key,
      sub: "sense-server",
      iat: now,
      exp: now + 30,
      video: { roomAdmin: true, room },
    },
    settings.secret,
  );
  const url = new URL(settings.url);
  // Cloud's default cutoff has a one-minute replay window. Self-hosted servers
  // do not implement token revocation; do not claim that removal invalidates JWTs.
  const cloud = url.hostname.endsWith(".livekit.cloud");
  const response = await request(
    settings.url.replace(/^wss:/, "https:").replace(/\/$/, "") +
      "/twirp/livekit.RoomService/RemoveParticipant",
    {
      method: "POST",
      redirect: "manual",
      headers: {
        Authorization: "Bearer " + token,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        room,
        identity,
        ...(cloud ? { revoke_token_ts: now } : {}),
      }),
      signal: AbortSignal.any([
        AbortSignal.timeout(5000),
        ...(signal ? [signal] : []),
      ]),
    },
  );
  await response.body?.cancel();
  if (!response.ok && response.status !== 404)
    throw Error("Media removal failed");
}

export function validateProductionEnvironment(env) {
  if (env.NODE_ENV !== "production") return;
  if (
    !env.DATABASE_PATH ||
    !env.MEDIA_PATH ||
    !path.isAbsolute(env.DATABASE_PATH) ||
    !path.isAbsolute(env.MEDIA_PATH) ||
    !env.RADAR_SECRET ||
    env.RADAR_SECRET.length < 32
  )
    throw Error(
      "Production requires explicit durable database/media paths and a Radar secret",
    );
  if (
    !validLiveKitSettings({
      url: env.LIVEKIT_URL,
      key: env.LIVEKIT_API_KEY,
      secret: env.LIVEKIT_API_SECRET,
    })
  )
    throw Error("Production LiveKit settings are missing or invalid");
}
