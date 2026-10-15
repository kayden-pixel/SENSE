import http from "node:http";
import { DatabaseSync } from "node:sqlite";
import {
  randomBytes,
  randomUUID,
  pbkdf2,
  timingSafeEqual,
  createHash,
  createHmac,
} from "node:crypto";
import { promisify } from "node:util";
import {
  mkdirSync,
  createWriteStream,
  createReadStream,
  statSync,
  unlinkSync,
  readFileSync,
} from "node:fs";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import {
  signLiveKitToken as jwt,
  verifyLiveKit,
  validateProductionEnvironment,
} from "./livekit.mjs";
const derive = promisify(pbkdf2);
const hash = (s) => createHash("sha256").update(s).digest("hex");
const problem = (status, message) =>
  Object.assign(new Error(message), { status });
const id = (s) => typeof s === "string" && /^[a-zA-Z0-9-]{1,64}$/.test(s);
const str = (s, min, max) =>
  typeof s === "string" && s.trim().length >= min && s.length <= max;
const usernameOf = (s) =>
  typeof s === "string" && /^[a-zA-Z0-9][a-zA-Z0-9-]{2,23}$/.test(s)
    ? s.toLowerCase()
    : null;
export function createApp(config = {}) {
  const dbPath =
    config.databasePath ?? process.env.DATABASE_PATH ?? "./data/sense.sqlite";
  if (dbPath !== ":memory:")
    mkdirSync(path.dirname(path.resolve(dbPath)), { recursive: true });
  const media = path.resolve(
    config.mediaPath ?? process.env.MEDIA_PATH ?? "./data/media",
  );
  mkdirSync(media, { recursive: true });
  const db = new DatabaseSync(dbPath);
  db.exec("PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;");
  const version = db.prepare("PRAGMA user_version").get().user_version;
  if (version < 1)
    db.exec(
      "BEGIN; CREATE TABLE users(id TEXT PRIMARY KEY,name TEXT NOT NULL,password TEXT NOT NULL,salt TEXT NOT NULL,last_seen INTEGER NOT NULL);" +
        "CREATE TABLE tokens(hash TEXT PRIMARY KEY,user TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL);" +
        "CREATE TABLE relationships(sender TEXT NOT NULL REFERENCES users(id),receiver TEXT NOT NULL REFERENCES users(id),state TEXT NOT NULL,PRIMARY KEY(sender,receiver));" +
        "CREATE TABLE sessions(id TEXT PRIMARY KEY,kind TEXT NOT NULL,host TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL,capture_at INTEGER);" +
        "CREATE TABLE members(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,user TEXT NOT NULL REFERENCES users(id),joined INTEGER NOT NULL DEFAULT 0,ready INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(session,user));" +
        "CREATE TABLE clips(id TEXT PRIMARY KEY,session TEXT NOT NULL REFERENCES sessions(id),user TEXT NOT NULL REFERENCES users(id),size INTEGER NOT NULL,sha TEXT NOT NULL,captured_at INTEGER NOT NULL,uncertainty INTEGER NOT NULL); PRAGMA user_version=1; COMMIT;",
    );
  if (version < 2)
    db.exec(
      "BEGIN; CREATE TABLE oob(sender TEXT NOT NULL,receiver TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL); CREATE INDEX oob_recipient ON oob(receiver,created); PRAGMA user_version=2; COMMIT;",
    );
  if (version < 3)
    db.exec(
      "BEGIN; ALTER TABLE users ADD COLUMN avatar TEXT; PRAGMA user_version=3; COMMIT;",
    );
  if (version < 4)
    db.exec(
      "BEGIN; ALTER TABLE users ADD COLUMN ranging INTEGER NOT NULL DEFAULT 0; PRAGMA user_version=4; COMMIT;",
    );
  if (version < 5)
    db.exec(
      "BEGIN; ALTER TABLE users ADD COLUMN password_iterations INTEGER NOT NULL DEFAULT 210000; CREATE TABLE voice_revocations(room TEXT NOT NULL,user TEXT NOT NULL,PRIMARY KEY(room,user)); PRAGMA user_version=5; COMMIT;",
    );
  if (version < 6)
    db.exec(
      "BEGIN; ALTER TABLE users ADD COLUMN username TEXT; CREATE UNIQUE INDEX users_username ON users(username COLLATE NOCASE) WHERE username IS NOT NULL; PRAGMA user_version=6; COMMIT;",
    );
  const q = (sql, ...args) => db.prepare(sql).all(...args);
  const one = (sql, ...args) => db.prepare(sql).get(...args);
  const run = (sql, ...args) => db.prepare(sql).run(...args);
  const buckets = new Map();
  const activeUploads = new Map();
  let activeAuth = 0;
  const passwordIterations = 600000;
  // Only an operator's server-side configuration can prepare an owner identity.
  // The shared Android package never contains this record or its recovery key.
  const ownerFile = config.ownerAccountFile ?? process.env.OWNER_ACCOUNT_FILE;
  const ownerRecord = config.ownerAccount ?? process.env.OWNER_ACCOUNT;
  if (ownerFile || ownerRecord) {
    try {
      const owner = JSON.parse(
        ownerFile ? readFileSync(ownerFile, "utf8") : ownerRecord,
      );
      if (
        !id(owner.id) ||
        !str(owner.name, 1, 40) ||
        !owner.username ||
        usernameOf(owner.username) !== owner.username ||
        !/^[0-9a-f]{64}$/.test(owner.password) ||
        !/^[0-9a-f]{32}$/.test(owner.salt) ||
        owner.iterations !== passwordIterations
      ) {
        throw Error("Invalid private owner configuration");
      }
      const previous = one(
        "SELECT id,username FROM users WHERE id=? OR username=? COLLATE NOCASE",
        owner.id,
        owner.username,
      );
      if (
        previous &&
        (previous.id !== owner.id || previous.username !== owner.username)
      ) {
        throw Error("Owner identity conflicts with an existing user");
      }
      if (!previous)
        run(
          "INSERT INTO users(id,name,username,password,salt,last_seen,password_iterations) VALUES(?,?,?,?,?,0,?)",
          owner.id,
          owner.name.trim(),
          owner.username,
          owner.password,
          owner.salt,
          owner.iterations,
        );
    } catch (error) {
      db.close();
      throw error;
    }
  }
  const rate = (key, limit) => {
    const now = Date.now();
    if (buckets.size >= 10000)
      for (const [k, b] of buckets) if (now - b.at > 60000) buckets.delete(k);
    if (!buckets.has(key) && buckets.size >= 10000)
      throw problem(429, "Rate limiter capacity reached");
    const b = buckets.get(key);
    if (!b || now - b.at > 60000) {
      buckets.set(key, { at: now, count: 1 });
      return;
    }
    if (++b.count > limit)
      throw problem(429, "Too many requests. Try again shortly.");
  };
  const blocked = (a, b) =>
    !!one(
      "SELECT 1 FROM relationships WHERE ((sender=? AND receiver=?) OR (sender=? AND receiver=?)) AND state='BLOCKED'",
      a,
      b,
      b,
      a,
    );
  const friends = (a, b) =>
    !blocked(a, b) &&
    !!one(
      "SELECT 1 FROM relationships WHERE ((sender=? AND receiver=?) OR (sender=? AND receiver=?)) AND state='ACCEPTED'",
      a,
      b,
      b,
      a,
    );
  const member = (s, u) => {
    const session = one(
      "SELECT * FROM sessions WHERE id=? AND expires>?",
      s,
      Date.now(),
    );
    if (
      !session ||
      !one("SELECT 1 FROM members WHERE session=? AND user=?", s, u)
    )
      throw problem(403, "Session unavailable");
    if (session.host !== u && !friends(session.host, u))
      throw problem(403, "Friend authorization removed");
    if (
      q("SELECT user FROM members WHERE session=? AND joined=1", s).some(
        (p) => p.user !== u && blocked(u, p.user),
      )
    )
      throw problem(403, "Blocked participant in session");
    return session;
  };
  const radarSecret = config.radarSecret ?? process.env.RADAR_SECRET;
  const radar = (u, epoch) =>
    radarSecret
      ? createHmac("sha256", radarSecret)
          .update(u + ":" + epoch)
          .digest("hex")
          .slice(0, 16)
      : null;
  const livekit = config.livekit ?? {
    url: process.env.LIVEKIT_URL,
    key: process.env.LIVEKIT_API_KEY,
    secret: process.env.LIVEKIT_API_SECRET,
  };
  const queueRevocation = (room, user) =>
    run("INSERT OR IGNORE INTO voice_revocations VALUES(?,?)", room, user);
  const shutdown = new AbortController();
  let voiceCheck;
  let voiceCheckedUntil = 0;
  let voiceReady = false;
  const isVoiceReady = () => {
    if (Date.now() < voiceCheckedUntil) return Promise.resolve(voiceReady);
    if (!voiceCheck)
      voiceCheck = Promise.resolve()
        .then(() =>
          config.verifyLivekit
            ? config.verifyLivekit(livekit)
            : verifyLiveKit(livekit, shutdown.signal),
        )
        .catch(() => false)
        .then((result) => {
          voiceReady = result === true;
          voiceCheckedUntil = Date.now() + 15000;
          return voiceReady;
        })
        .finally(() => {
          voiceCheck = undefined;
        });
    return voiceCheck;
  };
  let closed = false;
  let revoking = false;
  const flushRevocations = async () => {
    if (closed || revoking) return false;
    revoking = true;
    try {
      for (const task of q(
        "SELECT room,user FROM voice_revocations LIMIT 64",
      )) {
        try {
          if (closed) return false;
          if (config.removeParticipant)
            await config.removeParticipant(task.room, task.user);
          else {
            if (
              !livekit?.url?.startsWith("wss://") ||
              !livekit?.key ||
              !livekit?.secret
            )
              throw Error("Media server unavailable");
            const now = Math.floor(Date.now() / 1000);
            const token = jwt(
              {
                iss: livekit.key,
                sub: "sense-server",
                iat: now,
                exp: now + 30,
                video: { roomAdmin: true, room: task.room },
              },
              livekit.secret,
            );
            const response = await fetch(
              livekit.url.replace(/^wss:/, "https:") +
                "/twirp/livekit.RoomService/RemoveParticipant",
              {
                method: "POST",
                redirect: "error",
                headers: {
                  Authorization: "Bearer " + token,
                  "Content-Type": "application/json",
                },
                body: JSON.stringify({ room: task.room, identity: task.user }),
                signal: AbortSignal.any([
                  AbortSignal.timeout(5000),
                  shutdown.signal,
                ]),
              },
            );
            if (!response.ok && response.status !== 404)
              throw Error("Media removal failed");
          }
          if (closed) return false;
          run(
            "DELETE FROM voice_revocations WHERE room=? AND user=?",
            task.room,
            task.user,
          );
        } catch {
          /* Persist the exact room/identity for the next retry. No credentials are logged. */
        }
      }
      return !closed && !one("SELECT 1 FROM voice_revocations LIMIT 1");
    } finally {
      revoking = false;
    }
  };
  const revokeVoice = async (u, onlyRoom) => {
    for (const row of q(
      "SELECT s.id FROM sessions s JOIN members m ON m.session=s.id WHERE m.user=? AND s.kind='voice' AND s.expires>?",
      u,
      Date.now(),
    )) {
      if (!onlyRoom || row.id === onlyRoom) queueRevocation(row.id, u);
    }
    if (!(await flushRevocations()))
      throw problem(
        503,
        "Voice access revoked locally; media removal is queued for retry",
      );
  };
  const sessionJson = (s) => ({
    id: s.id,
    kind: s.kind,
    host: s.host,
    expiresAt: s.expires,
    captureAt: s.capture_at,
    participants: q(
      "SELECT user FROM members WHERE session=? AND joined=1",
      s.id,
    ).map((x) => x.user),
    ready: q(
      "SELECT user FROM members WHERE session=? AND ready>? AND joined=1",
      s.id,
      Date.now() - 6000,
    ).map((x) => x.user),
    clips: q(
      "SELECT id,user,size,captured_at,uncertainty,sha FROM clips WHERE session=?",
      s.id,
    ),
  });
  const readBody = async (req) => {
    let bytes = 0;
    const chunks = [];
    for await (const c of req) {
      bytes += c.length;
      if (bytes > 16384) throw problem(413, "Request too large");
      chunks.push(c);
    }
    try {
      const value = JSON.parse(Buffer.concat(chunks).toString() || "{}");
      if (value === null || Array.isArray(value) || typeof value !== "object")
        throw Error();
      return value;
    } catch {
      throw problem(400, "Invalid JSON");
    }
  };
  const server = http.createServer(async (req, res) => {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    const send = (status, data) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(data));
    };
    try {
      const route = new URL(req.url, "http://localhost").pathname
        .split("/")
        .filter(Boolean);
      const method = req.method;
      const ip =
        (process.env.TRUST_PROXY === "true"
          ? String(req.headers["x-forwarded-for"] ?? "").split(",")[0]
          : req.socket.remoteAddress) ?? "unknown";
      rate("ip:" + ip, 600);
      if (method === "GET" && route.length === 1 && route[0] === "ready") {
        one("SELECT 1");
        const voice = await isVoiceReady();
        send(voice ? 200 : 503, {
          ready: voice,
          version: "0.1.0",
          services: { api: true, voice },
        });
        return;
      }
      if (method === "GET" && route.length === 1 && route[0] === "health") {
        send(200, { ok: true, version: "0.1.0" });
        return;
      }
      if (route[0] === "auth" && method === "POST") {
        rate("auth:" + ip, 10);
        if (activeAuth >= 4)
          throw problem(503, "Authentication busy. Try again shortly.");
        const b = await readBody(req);
        const username =
          b.username === undefined ? null : usernameOf(b.username);
        if (
          (b.username !== undefined && !username) ||
          (!id(b.id) && !(route[1] === "login" && username)) ||
          !str(b.password, 12, 128)
        )
          throw problem(
            400,
            "Use a valid username or ID and a 12–128 character private key",
          );
        if (route.length !== 2 || !["register", "login"].includes(route[1]))
          throw problem(404, "Not found");
        if (activeAuth >= 4)
          throw problem(503, "Authentication busy. Try again shortly.");
        activeAuth++;
        try {
          if (route[1] === "register") {
            if (!str(b.name, 1, 40))
              throw problem(400, "Name must be 1–40 characters");
            if (one("SELECT 1 FROM users WHERE id=?", b.id))
              throw problem(409, "ID already registered");
            if (
              username &&
              one(
                "SELECT 1 FROM users WHERE username=? COLLATE NOCASE OR id=? COLLATE NOCASE",
                username,
                username,
              )
            )
              throw problem(409, "Username unavailable");
            const salt = randomBytes(16).toString("hex");
            const key = (
              await derive(b.password, salt, passwordIterations, 32, "sha256")
            ).toString("hex");
            // Another registration may have completed while PBKDF2 was running.
            if (one("SELECT 1 FROM users WHERE id=?", b.id))
              throw problem(409, "ID already registered");
            if (
              username &&
              one(
                "SELECT 1 FROM users WHERE username=? COLLATE NOCASE OR id=? COLLATE NOCASE",
                username,
                username,
              )
            )
              throw problem(409, "Username unavailable");
            run(
              "INSERT INTO users(id,name,password,salt,last_seen,password_iterations,username) VALUES(?,?,?,?,?,?,?)",
              b.id,
              b.name.trim(),
              key,
              salt,
              Date.now(),
              passwordIterations,
              username,
            );
          }
          const user = username
            ? one(
                "SELECT * FROM users WHERE username=? COLLATE NOCASE",
                username,
              )
            : one("SELECT * FROM users WHERE id=?", b.id);
          const iterations = user?.password_iterations ?? passwordIterations;
          const key = await derive(
            b.password,
            user?.salt ?? "invalid-user-salt",
            iterations,
            32,
            "sha256",
          );
          const expected = Buffer.from(user?.password ?? "", "hex");
          if (
            !user ||
            expected.length !== key.length ||
            !timingSafeEqual(key, expected)
          )
            throw problem(401, "Invalid credentials");
          if (iterations < passwordIterations) {
            const salt = randomBytes(16).toString("hex");
            const upgraded = (
              await derive(b.password, salt, passwordIterations, 32, "sha256")
            ).toString("hex");
            run(
              "UPDATE users SET password=?,salt=?,password_iterations=? WHERE id=?",
              upgraded,
              salt,
              passwordIterations,
              user.id,
            );
          }
          const token = randomBytes(32).toString("base64url");
          run(
            "INSERT INTO tokens VALUES(?,?,?)",
            hash(token),
            user.id,
            Date.now() + 30 * 86400000,
          );
          send(200, {
            token,
            id: user.id,
            name: user.name,
            avatar: user.avatar,
            username: user.username,
          });
          return;
        } finally {
          activeAuth--;
        }
      }
      const bearer = String(req.headers.authorization ?? "").replace(
        /^Bearer /,
        "",
      );
      const credential = one(
        "SELECT user FROM tokens WHERE hash=? AND expires>?",
        hash(bearer),
        Date.now(),
      );
      if (!credential) throw problem(401, "Sign in required");
      const u = credential.user;
      const assertAuthorized = () => {
        if (
          !one(
            "SELECT 1 FROM tokens WHERE hash=? AND user=? AND expires>?",
            hash(bearer),
            u,
            Date.now(),
          )
        )
          throw problem(401, "Session credentials revoked");
        if (route[0] === "sessions" && route[1]) member(route[1], u);
      };
      const readAuthorizedBody = async () => {
        const body = await readBody(req);
        assertAuthorized();
        return body;
      };
      rate("user:" + u, 480);
      run("UPDATE users SET last_seen=? WHERE id=?", Date.now(), u);
      if (route[0] === "logout" && method === "POST") {
        run("DELETE FROM tokens WHERE hash=?", hash(bearer));
        await revokeVoice(u);
        send(200, { ok: true });
        return;
      }
      if (route[0] === "me" && method === "GET") {
        const epoch = Math.floor(Date.now() / 300000);
        const people = q(
          "SELECT r.*,p.name,p.last_seen,p.avatar,p.ranging FROM relationships r JOIN users p ON p.id=CASE WHEN r.sender=? THEN r.receiver ELSE r.sender END WHERE r.sender=? OR r.receiver=?",
          u,
          u,
          u,
        )
          .filter((r) =>
            r.state === "BLOCKED"
              ? r.sender === u
              : !blocked(u, r.sender === u ? r.receiver : r.sender),
          )
          .map((r) => {
            const pid = r.sender === u ? r.receiver : r.sender;
            return {
              id: pid,
              name: r.name,
              avatar: r.state === "BLOCKED" ? null : r.avatar,
              ranging: r.state === "ACCEPTED" && r.ranging === 1,
              relationship:
                r.state === "REQUEST"
                  ? r.sender === u
                    ? "OUTGOING"
                    : "INCOMING"
                  : r.state,
              online:
                r.state === "ACCEPTED" && Date.now() - r.last_seen < 20000,
              radarTokens:
                r.state === "ACCEPTED"
                  ? [radar(pid, epoch), radar(pid, epoch - 1)].filter(Boolean)
                  : [],
            };
          });
        const sessions = q(
          "SELECT s.* FROM sessions s JOIN members m ON s.id=m.session WHERE m.user=? AND s.expires>?",
          u,
          Date.now(),
        )
          .filter((s) => s.host === u || friends(s.host, u))
          .map(sessionJson);
        send(200, {
          serverTime: Date.now(),
          people,
          sessions,
          radarToken: radar(u, epoch),
        });
        return;
      }
      if (route[0] === "presence" && method === "POST") {
        const b = await readAuthorizedBody();
        run(
          "UPDATE users SET ranging=? WHERE id=?",
          b.ranging === true ? 1 : 0,
          u,
        );
        send(200, { ok: true });
        return;
      }
      if (route[0] === "profile" && method === "POST") {
        const b = await readAuthorizedBody();
        if (!str(b.name, 1, 40)) throw problem(400, "Invalid name");
        if (b.avatar !== undefined) {
          if (
            b.avatar !== null &&
            (!str(b.avatar, 1, 12000) ||
              !/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(b.avatar))
          )
            throw problem(400, "Invalid avatar");
          run("UPDATE users SET avatar=? WHERE id=?", b.avatar, u);
        }
        run("UPDATE users SET name=? WHERE id=?", b.name.trim(), u);
        send(200, { ok: true });
        return;
      }
      if (route[0] === "friends" && method === "POST") {
        const b = await readAuthorizedBody();
        // Preserve UUID authorization internally; usernames are just an invite address.
        if (
          typeof b.id === "string" &&
          !one("SELECT 1 FROM users WHERE id=?", b.id)
        ) {
          const target = one(
            "SELECT id FROM users WHERE username=? COLLATE NOCASE",
            b.id.replace(/^@/, ""),
          );
          if (target) b.id = target.id;
        }
        if (
          !id(b.id) ||
          b.id === u ||
          !one("SELECT 1 FROM users WHERE id=?", b.id)
        )
          throw problem(400, "User unavailable");
        if (b.action === "request") {
          if (blocked(u, b.id)) throw problem(403, "User unavailable");
          if (
            !friends(u, b.id) &&
            !one(
              "SELECT 1 FROM relationships WHERE sender=? AND receiver=?",
              b.id,
              u,
            )
          )
            run(
              "INSERT OR IGNORE INTO relationships VALUES(?,?,'REQUEST')",
              u,
              b.id,
            );
        } else if (b.action === "accept") {
          if (blocked(u, b.id)) throw problem(403, "User unavailable");
          if (
            !run(
              "UPDATE relationships SET state='ACCEPTED' WHERE sender=? AND receiver=? AND state='REQUEST'",
              b.id,
              u,
            ).changes
          )
            throw problem(409, "Request unavailable");
        } else if (b.action === "remove" || b.action === "block") {
          const common = q(
            "SELECT s.id,s.kind,s.host FROM sessions s JOIN members a ON a.session=s.id JOIN members b ON b.session=s.id WHERE a.user=? AND b.user=? AND s.expires>?",
            u,
            b.id,
            Date.now(),
          );
          db.exec("BEGIN");
          try {
            // A user cannot delete the other person's block, including via remove/block.
            run(
              "DELETE FROM relationships WHERE ((sender=? AND receiver=?) OR (sender=? AND receiver=?)) AND (state!='BLOCKED' OR sender=?)",
              u,
              b.id,
              b.id,
              u,
              u,
            );
            if (b.action === "block")
              run(
                "INSERT OR REPLACE INTO relationships VALUES(?,?,'BLOCKED')",
                u,
                b.id,
              );
            for (const room of common) {
              // In a third-party room the blocker leaves; unrelated rooms stay untouched.
              const evicted = room.host === u ? b.id : u;
              run(
                "DELETE FROM members WHERE session=? AND user=?",
                room.id,
                evicted,
              );
              if (room.kind === "voice") queueRevocation(room.id, evicted);
            }
            run(
              "DELETE FROM oob WHERE (sender=? AND receiver=?) OR (sender=? AND receiver=?)",
              u,
              b.id,
              b.id,
              u,
            );
            db.exec("COMMIT");
          } catch (e) {
            db.exec("ROLLBACK");
            throw e;
          }
          if (!(await flushRevocations()))
            throw problem(
              503,
              "Friend state updated; media removal is queued for retry",
            );
        } else if (b.action === "unblock") {
          run(
            "DELETE FROM relationships WHERE sender=? AND receiver=? AND state='BLOCKED'",
            u,
            b.id,
          );
        } else throw problem(400, "Invalid action");
        send(200, { ok: true });
        return;
      }
      if (route[0] === "sessions") {
        if (route.length === 1 && method === "POST") {
          const b = await readAuthorizedBody();
          if (
            !["voice", "moment"].includes(b.kind) ||
            !Array.isArray(b.invitees) ||
            b.invitees.length > 8 ||
            b.invitees.some((p) => !id(p) || !friends(u, p))
          )
            throw problem(400, "Invite accepted friends only");
          if (
            one(
              "SELECT COUNT(*) AS n FROM sessions WHERE host=? AND expires>?",
              u,
              Date.now(),
            ).n >= 5
          )
            throw problem(429, "Too many active sessions");
          const s = randomUUID();
          run(
            "INSERT INTO sessions VALUES(?,?,?,?,NULL)",
            s,
            b.kind,
            u,
            Date.now() + 3600000,
          );
          run("INSERT INTO members VALUES(?,?,1,0)", s, u);
          for (const p of new Set(b.invitees))
            run("INSERT INTO members VALUES(?,?,0,0)", s, p);
          send(201, sessionJson(one("SELECT * FROM sessions WHERE id=?", s)));
          return;
        }
        if (
          route.length < 2 ||
          route.length > 4 ||
          (route.length === 4 && route[2] !== "clips")
        )
          throw problem(404, "Not found");
        const s = member(route[1], u);
        if (route[2] === "join" && method === "POST") {
          run(
            "UPDATE members SET joined=1 WHERE session=? AND user=?",
            s.id,
            u,
          );
          send(200, sessionJson(s));
          return;
        }
        if (route[2] === "leave" && method === "POST") {
          run(
            "UPDATE members SET joined=0,ready=0 WHERE session=? AND user=?",
            s.id,
            u,
          );
          await revokeVoice(u, s.id);
          send(200, { ok: true });
          return;
        }
        if (route[2] === "invite" && method === "POST") {
          const b = await readAuthorizedBody();
          if (s.host !== u || !id(b.id) || !friends(u, b.id))
            throw problem(403, "Invite unavailable");
          if (
            one("SELECT COUNT(*) AS n FROM members WHERE session=?", s.id).n >=
            9
          )
            throw problem(400, "Session full");
          run("INSERT OR IGNORE INTO members VALUES(?,?,0,0)", s.id, b.id);
          send(200, sessionJson(s));
          return;
        }
        if (route[2] === "token" && method === "POST") {
          if (
            s.kind !== "voice" ||
            !one(
              "SELECT 1 FROM members WHERE session=? AND user=? AND joined=1",
              s.id,
              u,
            )
          )
            throw problem(403, "Join this voice session first");
          if (!(await isVoiceReady()))
            throw problem(503, "Whisper is temporarily unavailable");
          assertAuthorized();
          if (
            !one(
              "SELECT 1 FROM members WHERE session=? AND user=? AND joined=1",
              s.id,
              u,
            )
          )
            throw problem(403, "Join this voice session first");
          if (
            one(
              "SELECT 1 FROM voice_revocations WHERE room=? AND user=?",
              s.id,
              u,
            )
          )
            throw problem(503, "Voice removal pending; try again shortly");
          const now = Math.floor(Date.now() / 1000);
          const name = one("SELECT name FROM users WHERE id=?", u).name;
          send(200, {
            url: livekit.url,
            token: jwt(
              {
                iss: livekit.key,
                sub: u,
                name,
                iat: now,
                nbf: now - 5,
                exp: Math.min(now + 120, Math.floor(s.expires / 1000)),
                video: {
                  roomJoin: true,
                  room: s.id,
                  canPublish: true,
                  canPublishSources: ["microphone"],
                  canSubscribe: true,
                  canPublishData: false,
                },
              },
              livekit.secret,
            ),
          });
          return;
        }
        if (method === "GET" && route.length === 2) {
          send(200, sessionJson(s));
          return;
        }
        if (s.kind !== "moment") throw problem(400, "Capture session required");
        if (route[2] === "ready" && method === "POST") {
          const b = await readAuthorizedBody();
          run(
            "UPDATE members SET ready=? WHERE session=? AND user=? AND joined=1",
            b.ready === true ? Date.now() : 0,
            s.id,
            u,
          );
          send(200, sessionJson(s));
          return;
        }
        if (route[2] === "schedule" && method === "POST") {
          if (
            s.host !== u ||
            s.capture_at ||
            !one("SELECT 1 FROM members WHERE session=? AND joined=1", s.id) ||
            one(
              "SELECT 1 FROM members WHERE session=? AND joined=1 AND ready<?",
              s.id,
              Date.now() - 6000,
            )
          )
            throw problem(409, "All joined participants must be ready");
          const at = Date.now() + 8000;
          run("UPDATE sessions SET capture_at=? WHERE id=?", at, s.id);
          send(200, sessionJson({ ...s, capture_at: at }));
          return;
        }
        if (route[2] === "clips" && method === "POST") {
          if (
            !s.capture_at ||
            !one(
              "SELECT 1 FROM members WHERE session=? AND user=? AND joined=1",
              s.id,
              u,
            )
          )
            throw problem(403, "Capture not authorized");
          if (
            one(
              "SELECT COUNT(*) AS n FROM clips WHERE session=? AND user=?",
              s.id,
              u,
            ).n >= 3
          )
            throw problem(429, "Clip limit reached");
          const uploadKey = s.id + ":" + u;
          if (
            (activeUploads.get(uploadKey) ?? 0) >= 1 ||
            [...activeUploads.values()].reduce((a, b) => a + b, 0) >= 8
          )
            throw problem(429, "Transfer busy");
          const size = Number(req.headers["content-length"]);
          const sha = String(req.headers["x-sha256"] ?? "");
          const at = Number(req.headers["x-captured-at"]);
          const uncertainty = Number(req.headers["x-clock-uncertainty"]);
          if (
            !Number.isInteger(size) ||
            size <= 0 ||
            size > 100 * 1024 * 1024 ||
            !/^[a-f0-9]{64}$/.test(sha) ||
            !Number.isSafeInteger(at) ||
            !Number.isSafeInteger(uncertainty) ||
            uncertainty < 0 ||
            uncertainty > 30000
          )
            throw problem(400, "Invalid capture metadata");
          const clip = randomUUID(),
            file = path.join(media, clip + ".mp4");
          activeUploads.set(uploadKey, 1);
          let bytes = 0;
          const digest = createHash("sha256");
          req.on("data", (c) => {
            bytes += c.length;
            digest.update(c);
            if (bytes > size) req.destroy();
          });
          try {
            await pipeline(req, createWriteStream(file, { flags: "wx" }));
            if (bytes !== size || digest.digest("hex") !== sha)
              throw problem(400, "Clip integrity failure");
            member(s.id, u);
            if (
              !one(
                "SELECT 1 FROM tokens WHERE hash=? AND user=? AND expires>?",
                hash(bearer),
                u,
                Date.now(),
              ) ||
              !one(
                "SELECT 1 FROM members WHERE session=? AND user=? AND joined=1",
                s.id,
                u,
              )
            )
              throw problem(403, "Transfer authorization removed");
            if (
              one(
                "SELECT COUNT(*) AS n FROM clips WHERE session=? AND user=?",
                s.id,
                u,
              ).n >= 3
            )
              throw problem(429, "Clip limit reached");
            run(
              "INSERT INTO clips VALUES(?,?,?,?,?,?,?)",
              clip,
              s.id,
              u,
              size,
              sha,
              at,
              uncertainty,
            );
          } catch (e) {
            try {
              unlinkSync(file);
            } catch {}
            throw e;
          } finally {
            activeUploads.delete(uploadKey);
          }
          send(201, { id: clip });
          return;
        }
        if (route[2] === "clips" && route[3] && method === "GET") {
          const clip = one(
            "SELECT * FROM clips WHERE id=? AND session=?",
            route[3],
            s.id,
          );
          if (!clip) throw problem(404, "Clip unavailable");
          res.writeHead(200, {
            "Content-Type": "video/mp4",
            "Content-Length": clip.size,
            "X-SHA256": clip.sha,
          });
          await pipeline(
            createReadStream(path.join(media, clip.id + ".mp4")),
            res,
          );
          return;
        }
        if (method === "GET" && route.length === 2) {
          send(200, sessionJson(s));
          return;
        }
      }
      if (route[0] === "oob") {
        if (method === "POST") {
          const b = await readAuthorizedBody();
          if (!id(b.to) || !friends(u, b.to) || !str(b.payload, 1, 12000))
            throw problem(403, "Ranging exchange denied");
          run("DELETE FROM oob WHERE created<?", Date.now() - 60000);
          if (
            !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(
              b.payload,
            )
          )
            throw problem(400, "Invalid ranging packet");
          if (
            one("SELECT COUNT(*) AS n FROM oob WHERE receiver=?", b.to).n >= 64
          )
            throw problem(429, "Ranging queue full");
          run(
            "INSERT INTO oob VALUES(?,?,?,?)",
            u,
            b.to,
            b.payload,
            Date.now(),
          );
          send(200, { ok: true });
          return;
        }
        if (method === "GET") {
          const packets = q(
            "SELECT rowid,sender,payload FROM oob WHERE receiver=? AND created>? ORDER BY rowid LIMIT 64",
            u,
            Date.now() - 60000,
          ).filter((p) => friends(u, p.sender));
          run("DELETE FROM oob WHERE receiver=?", u);
          send(200, { packets });
          return;
        }
      }
      throw problem(404, "Not found");
    } catch (e) {
      if (!res.headersSent)
        send(e.status ?? 500, {
          error: e.status ? e.message : "Internal server error",
        });
      else res.destroy();
    }
  });
  const cleanup = setInterval(() => {
    for (const row of q(
      "SELECT s.id,m.user FROM sessions s JOIN members m ON m.session=s.id WHERE s.kind='voice' AND s.expires<=? AND m.joined=1",
      Date.now(),
    )) {
      queueRevocation(row.id, row.user);
      run(
        "UPDATE members SET joined=0,ready=0 WHERE session=? AND user=?",
        row.id,
        row.user,
      );
    }
    void flushRevocations().catch(() => {});
    run("DELETE FROM tokens WHERE expires<?", Date.now());
    run("DELETE FROM oob WHERE created<?", Date.now() - 60000);
  }, 60000);
  cleanup.unref();
  server.requestTimeout = 60000;
  server.headersTimeout = 15000;
  server.timeout = 60000;
  server.on("close", () => {
    closed = true;
    shutdown.abort();
    clearInterval(cleanup);
    db.close();
  });
  return server;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  validateProductionEnvironment(process.env);
  if (!process.env.RADAR_SECRET || process.env.RADAR_SECRET.length < 32)
    throw Error("Set RADAR_SECRET to a random 32+ character value");
  const server = createApp();
  server.listen(
    Number(process.env.PORT ?? 8787),
    process.env.HOST ?? "127.0.0.1",
    () => console.log("SENSE backend listening"),
  );
  const stop = () => {
    const deadline = setTimeout(() => process.exit(1), 10000);
    deadline.unref();
    server.close(() => {
      clearTimeout(deadline);
      process.exit(0);
    });
    server.closeIdleConnections();
  };
  process.once("SIGTERM", stop);
  process.once("SIGINT", stop);
}
