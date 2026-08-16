// pairing.ts — device pairing + token auth (M2, hardening.md critical path #1).
//
// The flow (clean-roomed from the Odysseus AGPL idea; mechanics adapted from
// MIT sources — see below):
//   1. A trusted context (loopback CLI) calls `pairing.start` → the daemon
//      mints a single-use BOOTSTRAP token (10 min TTL) and returns a setup
//      code (base64url JSON {url, token, expires_at_ms}) rendered as a QR.
//   2. The new device connects (unauthenticated) and calls `pairing.claim`
//      with the bootstrap token + its declared device id → the daemon mints a
//      long-lived PER-DEVICE bearer token, stores only its SHA-256 hash, and
//      binds the (sanitized) device id to it.
//   3. Thereafter the device authenticates every connection via `?token=` or
//      hello's auth.token; the token's device id is the VERIFIED origin
//      identity — a hello cannot override it.
//   4. `device.revoke` deletes the tokens + the roster row and drops live
//      connections. Revocation is immediate.
//
// Adapted patterns (attribution — see CREDITS.md):
//   - hermes-agent gateway/pairing.py (MIT): hash-at-rest with constant-time
//     compare, single-use pending entries with TTL, pending cap, failed-
//     attempt lockout.
//   - OpenClaw extensions/device-pair (MIT): the setup-code payload shape
//     {url, token, expiry} encoded base64url for QR/paste delivery.
//
// Bootstrap tokens live only in memory: a daemon restart invalidates pending
// pairings (re-run `pair` — cheap), and nothing secret is ever on disk in
// plaintext. Device tokens are 256-bit random, hashed (SHA-256) in SQLite.

import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { networkInterfaces } from "node:os";
import type { DatabaseSync } from "node:sqlite";

const BOOTSTRAP_TTL_MS = 10 * 60 * 1000;
const MAX_PENDING_BOOTSTRAPS = 3;
const MAX_FAILED_CLAIMS = 5;
const LOCKOUT_MS = 60 * 60 * 1000;

const SCHEMA = `
CREATE TABLE IF NOT EXISTS device_tokens (
  token_hash TEXT PRIMARY KEY,
  device_id  TEXT NOT NULL,
  principal  TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_used  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_tokens_device ON device_tokens(device_id);
`;

function hashToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

/** Constant-time hex-digest comparison (both sides are our own hashes, so
 *  lengths always match; a length mismatch is an immediate false). */
function digestsEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a, "hex");
  const bb = Buffer.from(b, "hex");
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

export interface TokenIdentity {
  deviceId: string;
  principal: string;
}

interface PendingBootstrap {
  hash: string;
  expiresAt: number;
}

export class PairingStore {
  private pending: PendingBootstrap[] = [];
  private failedClaims = 0;
  private lockedUntil = 0;

  constructor(private db: DatabaseSync, private log: (line: string) => void = () => {}) {
    this.db.exec(SCHEMA);
    // One-time data migration for the 2026-08-15 principal rename: device
    // tokens minted before it carry principal "max" and would silently lose
    // the subscription authClass gate (policy.ts requires "owner"). Idempotent.
    this.db.exec(`UPDATE device_tokens SET principal = 'owner' WHERE principal = 'max'`);
  }

  /** Mint a single-use bootstrap token. Returns null when the pending cap is
   *  hit or a lockout is active (caller maps that to a clean error). */
  startPairing(now: number): { token: string; expiresAt: number } | null {
    this.prune(now);
    if (now < this.lockedUntil) return null;
    if (this.pending.length >= MAX_PENDING_BOOTSTRAPS) return null;
    const token = randomBytes(32).toString("base64url");
    const expiresAt = now + BOOTSTRAP_TTL_MS;
    this.pending.push({ hash: hashToken(token), expiresAt });
    return { token, expiresAt };
  }

  /** Exchange a bootstrap token for a per-device bearer token. Single-use:
   *  a matched bootstrap is consumed whether or not anything else succeeds.
   *  Returns null on invalid/expired token or active lockout. */
  claim(bootstrapToken: string, deviceId: string, principal: string, now: number): string | null {
    this.prune(now);
    if (now < this.lockedUntil) return null;
    const hash = hashToken(bootstrapToken);
    const idx = this.pending.findIndex((p) => digestsEqual(p.hash, hash));
    if (idx < 0) {
      this.failedClaims++;
      if (this.failedClaims >= MAX_FAILED_CLAIMS) {
        this.lockedUntil = now + LOCKOUT_MS;
        this.failedClaims = 0;
        this.log(`[pairing] locked out for ${LOCKOUT_MS / 60000} min after ${MAX_FAILED_CLAIMS} failed claims`);
      }
      return null;
    }
    this.pending.splice(idx, 1); // single-use
    this.failedClaims = 0;
    const deviceToken = randomBytes(32).toString("base64url");
    this.db
      .prepare(`INSERT INTO device_tokens (token_hash, device_id, principal, created_at, last_used) VALUES (?,?,?,?,?)`)
      .run(hashToken(deviceToken), deviceId, principal, now, now);
    return deviceToken;
  }

  /** token → verified identity, or null. Touches last_used on success. */
  authenticate(token: string, now: number): TokenIdentity | null {
    const row = this.db
      .prepare(`SELECT device_id, principal FROM device_tokens WHERE token_hash = ?`)
      .get(hashToken(token)) as { device_id: string; principal: string } | undefined;
    if (!row) return null;
    this.db.prepare(`UPDATE device_tokens SET last_used = ? WHERE token_hash = ?`).run(now, hashToken(token));
    return { deviceId: row.device_id, principal: row.principal };
  }

  /** Device holds at least one live token. */
  isPaired(deviceId: string): boolean {
    const row = this.db.prepare(`SELECT 1 FROM device_tokens WHERE device_id = ? LIMIT 1`).get(deviceId);
    return row !== undefined;
  }

  /** Delete every token for a device. Returns how many were revoked. */
  revokeDevice(deviceId: string): number {
    const res = this.db.prepare(`DELETE FROM device_tokens WHERE device_id = ?`).run(deviceId);
    return Number(res.changes);
  }

  private prune(now: number): void {
    this.pending = this.pending.filter((p) => p.expiresAt > now);
  }
}

/** The QR/paste payload (OpenClaw device-pair setup-code shape, MIT). */
export function encodeSetupCode(payload: { url: string; token: string; expires_at_ms: number }): string {
  return Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
}

/** True for hosts inside the Tailscale CGNAT range (100.64.0.0/10). */
export function isTailnetIPv4(host: string): boolean {
  const m = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(host);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  return a === 100 && b >= 64 && b <= 127;
}

/** This machine's tailnet IPv4, if any — used to build the setup-code URL
 *  when the gateway itself is bound to loopback (the code then warns that the
 *  daemon isn't reachable there yet; the URL is still the right target once
 *  MARMALADE_BIND_HOST widens the bind). */
export function detectTailnetIPv4(): string | null {
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.family === "IPv4" && !entry.internal && isTailnetIPv4(entry.address)) return entry.address;
    }
  }
  return null;
}
