import { DurableObject } from "cloudflare:workers";
import { httpServerHandler } from "cloudflare:node";
import { createApp } from "../src/server.mjs";
import { durableDatabase } from "./sqlite.mjs";
import { durableMedia } from "./media.mjs";
import { validLiveKitSettings } from "../src/livekit.mjs";

export class SenseApi extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    const livekit = { url: env.LIVEKIT_URL, key: env.LIVEKIT_API_KEY, secret: env.LIVEKIT_API_SECRET };
    if (!env.RADAR_SECRET || env.RADAR_SECRET.length < 32 || !validLiveKitSettings(livekit))
      throw Error("Server secrets are not configured");
    this.media = durableMedia(ctx.storage);
    this.server = createApp({
      database: durableDatabase(ctx.storage),
      transaction: (operation) => ctx.storage.transactionSync(operation),
      mediaStore: this.media,
      radarSecret: env.RADAR_SECRET,
      livekit,
      ownerAccount: env.OWNER_ACCOUNT,
      externalCleanup: true,
      // Only the outer Worker creates this internal header. Client-provided
      // forwarding headers are discarded before reaching this private object.
      clientAddress: (req) => req.headers["x-sense-client-ip"] ?? "unknown",
    });
    this.handler = httpServerHandler(this.server);
  }

  async fetch(request) {
    if (await this.ctx.storage.getAlarm() === null)
      await this.ctx.storage.setAlarm(Date.now() + 60000);
    const response = await this.handler.fetch(request);
    await this.ctx.storage.sync();
    return response;
  }

  async alarm() {
    await this.server.senseCleanup();
    this.media.cleanup();
    const tasks = this.ctx.storage.sql.exec("SELECT 1 FROM voice_revocations UNION ALL SELECT 1 FROM sessions WHERE kind='voice' AND expires>? LIMIT 1", Date.now()).toArray();
    if (tasks.length) await this.ctx.storage.setAlarm(Date.now() + 60000);
  }
}

export default {
  async fetch(request, env) {
    const headers = new Headers(request.headers);
    headers.set("x-sense-client-ip", request.headers.get("CF-Connecting-IP") ?? "unknown");
    headers.delete("x-forwarded-for");
    const id = env.SENSE_API.idFromName("sense-production-v1");
    try {
      return await env.SENSE_API.get(id).fetch(new Request(request, { headers }));
    } catch {
      return Response.json({ error: "SENSE service is unavailable" }, { status: 503, headers: { "Cache-Control": "no-store" } });
    }
  },
};
