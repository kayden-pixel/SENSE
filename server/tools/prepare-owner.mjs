import { randomBytes, randomUUID, pbkdf2Sync } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export function prepareOwner(directory, username) {
  if (
    !path.isAbsolute(directory) ||
    !/^[a-z0-9][a-z0-9-]{2,23}$/.test(username)
  )
    throw Error(
      "Provide an absolute private directory and a lowercase username",
    );
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const recoveryKey = randomBytes(32).toString("base64url");
  const salt = randomBytes(16).toString("hex");
  const owner = {
    id: randomUUID(),
    name: username,
    username,
    salt,
    iterations: 600000,
    password: pbkdf2Sync(recoveryKey, salt, 600000, 32, "sha256").toString(
      "hex",
    ),
  };
  const exclusive = { flag: "wx", mode: 0o600 };
  // Exclusive creation intentionally refuses to replace an existing identity.
  writeFileSync(
    path.join(directory, "owner.private.json"),
    JSON.stringify(owner) + "\n",
    exclusive,
  );
  writeFileSync(
    path.join(directory, "backend.private.env"),
    "RADAR_SECRET=" +
      randomBytes(32).toString("hex") +
      "\n" +
      "OWNER_ACCOUNT='" +
      JSON.stringify(owner) +
      "'\n",
    exclusive,
  );
  writeFileSync(
    path.join(directory, "owner-access.private.txt"),
    "SENSE private owner access — do not share or commit this file.\n\n" +
      "Username: " +
      username +
      "\nRecovery key: " +
      recoveryKey +
      "\n\n" +
      "On your phone, choose You → Your identity, disable Choose a new username,\n" +
      "then enter this username and recovery key. Friends choose their own new username.\n" +
      "The backend must first start with backend.private.env. This reserves the owner\n" +
      "in its durable database; no owner credentials are included in any APK.\n" +
      "Keep this private directory safe. Changing it does not reset an existing account.\n",
    exclusive,
  );
  return owner;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  prepareOwner(path.resolve(process.argv[2] ?? ""), process.argv[3] ?? "");
  console.log(
    "Private owner configuration prepared; recovery key was not printed.",
  );
}
