// pairing.ts — device pairing for the webui (M2).
//
// The daemon's pairing.claim exchanges a single-use setup code (printed by
// `marmalade pair`) for THIS install's durable per-device bearer token, so a
// remote (tailnet) browser authenticates with a real token instead of relying
// on loopback trust. Mirrors the Android client's MarmaladeRuntime.claimPairing.
//
// An unauthenticated connection may call ONLY pairing.claim (the daemon sends no
// gateway.ready to unauthenticated remotes), so the claim goes out right after
// the socket opens — no hello first. The browser WebSocket API can't set the
// X-Marmalade-Device-Id header the Android client uses, so the device id rides
// in the claim params instead (the daemon accepts it either way).

import { browserSocketFactory, type SocketFactory } from "./socket.js";

export interface SetupCode {
  /** The daemon WS URL to claim against, then connect to (ws(s)://host/api/ws). */
  url: string;
  /** Single-use ~10-min bootstrap token from `marmalade pair`. */
  token: string;
  /** Optional expiry (ms epoch) — for a friendlier "expired" message. */
  expires_at_ms?: number;
}

export interface PairingResult {
  device_id: string;
  device_token: string;
}

/** Decode the base64url JSON a `marmalade pair` setup code / QR carries. */
export function decodeSetupCode(raw: string): SetupCode {
  const trimmed = raw.trim();
  let json: string;
  try {
    json = base64urlDecode(trimmed);
  } catch {
    throw new Error("That doesn't look like a setup code.");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    throw new Error("Setup code is malformed.");
  }
  const c = parsed as Record<string, unknown>;
  if (typeof c.url !== "string" || typeof c.token !== "string") {
    throw new Error("Setup code is missing its url/token.");
  }
  return {
    url: c.url,
    token: c.token,
    expires_at_ms: typeof c.expires_at_ms === "number" ? c.expires_at_ms : undefined,
  };
}

function base64urlDecode(s: string): string {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(s.length / 4) * 4, "=");
  return atob(b64);
}

/**
 * Redeem a setup code for this install's durable device token. Opens a
 * throwaway socket, sends pairing.claim immediately on open, and resolves with
 * {device_id, device_token}. Never touches the app's live connection — the
 * caller persists the token + url and lets the gateway context reconnect.
 */
export function claimPairing(
  setup: SetupCode,
  deviceId: string,
  makeSocket: SocketFactory = browserSocketFactory,
  timeoutMs = 10_000,
): Promise<PairingResult> {
  return new Promise<PairingResult>((resolve, reject) => {
    if (setup.expires_at_ms !== undefined && Date.now() > setup.expires_at_ms) {
      reject(new Error("Setup code expired — run `marmalade pair` again."));
      return;
    }
    const sock = makeSocket(setup.url);
    const reqId = `claim-${deviceId}-${Date.now()}`;
    let settled = false;
    const timer = setTimeout(() => finish(() => reject(new Error("Pairing timed out."))), timeoutMs);
    function finish(fn: () => void): void {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        sock.close();
      } catch {
        /* already closing */
      }
      fn();
    }
    sock.onOpen = () => {
      sock.send(
        JSON.stringify({
          jsonrpc: "2.0",
          id: reqId,
          method: "pairing.claim",
          params: { token: setup.token, device_id: deviceId, platform: "web" },
        }),
      );
    };
    sock.onMessage = (data) => {
      let msg: { id?: unknown; result?: unknown; error?: { message?: string } };
      try {
        msg = JSON.parse(data) as typeof msg;
      } catch {
        return;
      }
      if (msg.id !== reqId) return; // ignore any pre-claim frames
      if (msg.error) {
        finish(() => reject(new Error(msg.error?.message ?? "Pairing was rejected.")));
        return;
      }
      const r = msg.result as Partial<PairingResult> | undefined;
      if (!r || typeof r.device_token !== "string") {
        finish(() => reject(new Error("Pairing returned no token.")));
        return;
      }
      finish(() => resolve({ device_id: String(r.device_id ?? deviceId), device_token: r.device_token as string }));
    };
    sock.onError = () => finish(() => reject(new Error("Pairing connection failed.")));
    sock.onClose = () => finish(() => reject(new Error("Pairing socket closed before completing.")));
  });
}
