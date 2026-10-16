import { pbkdf2Async } from "@noble/hashes/pbkdf2.js";
import { sha256 } from "@noble/hashes/sha2.js";

// Production Workers cap native PBKDF2 below the API's existing 600,000 rounds.
// Preserve the exact PBKDF2-HMAC-SHA256 format, including UTF-8 salt encoding.
export async function derivePassword(password, salt, iterations, length, digest) {
  if (digest !== "sha256") throw Error("Unsupported password digest");
  const encode = (value) => typeof value === "string" ? new TextEncoder().encode(value) : value;
  return Buffer.from(await pbkdf2Async(sha256, encode(password), encode(salt), {
    c: iterations, dkLen: length, asyncTick: 10,
  }));
}
