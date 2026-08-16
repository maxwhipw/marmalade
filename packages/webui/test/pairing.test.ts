// pairing.test.ts — the webui device-pairing claim flow (M2).
//
// Mirrors the daemon's pairing contract (packages/daemon/test/pairing.test.ts):
// a setup code is base64url(JSON {url, token, expires_at_ms}); pairing.claim
// takes {token, device_id, platform} and returns {device_token, device_id,
// principal}. No real socket — a scripted fake drives open/message, exactly
// like gateway-client.test.ts.

import { describe, expect, test } from "vitest";
import { decodeSetupCode, claimPairing } from "../src/gateway/pairing.js";
import type { GatewaySocket } from "../src/gateway/socket.js";

function encodeSetupCode(payload: { url: string; token: string; expires_at_ms: number }): string {
  return Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
}

class FakePairSocket implements GatewaySocket {
  sent: string[] = [];
  closed = false;
  onOpen: (() => void) | null = null;
  onClose: (() => void) | null = null;
  onError: (() => void) | null = null;
  onMessage: ((data: string) => void) | null = null;
  send(data: string): void {
    this.sent.push(data);
  }
  close(): void {
    this.closed = true;
  }
}

describe("decodeSetupCode", () => {
  test("decodes the daemon's base64url(JSON) payload", () => {
    const code = encodeSetupCode({ url: "ws://100.64.1.2:9130/api/ws", token: "boot-tok", expires_at_ms: 9_999_999_999_999 });
    const setup = decodeSetupCode(code);
    expect(setup).toEqual({ url: "ws://100.64.1.2:9130/api/ws", token: "boot-tok", expires_at_ms: 9_999_999_999_999 });
  });

  test("rejects garbage and payloads missing url/token", () => {
    expect(() => decodeSetupCode("not a code!!")).toThrow();
    expect(() => decodeSetupCode(Buffer.from("{}").toString("base64url"))).toThrow(/url\/token/);
  });
});

describe("claimPairing", () => {
  test("sends pairing.claim with {token, device_id, platform} and resolves the device token", async () => {
    const setup = decodeSetupCode(encodeSetupCode({ url: "ws://100.64.1.2:9130/api/ws", token: "boot-tok", expires_at_ms: 9_999_999_999_999 }));
    const sock = new FakePairSocket();
    const promise = claimPairing(setup, "web-abc", () => sock);

    sock.onOpen!();
    const req = JSON.parse(sock.sent[0]);
    expect(req.method).toBe("pairing.claim");
    expect(req.params).toEqual({ token: "boot-tok", device_id: "web-abc", platform: "web" });

    sock.onMessage!(JSON.stringify({ jsonrpc: "2.0", id: req.id, result: { device_token: "dtok-xyz", device_id: "web-abc", principal: "owner" } }));
    const result = await promise;
    expect(result).toEqual({ device_id: "web-abc", device_token: "dtok-xyz" });
    expect(sock.closed).toBe(true); // throwaway socket is closed after the claim
  });

  test("rejects with the daemon's error message (invalid/expired bootstrap token)", async () => {
    const setup = decodeSetupCode(encodeSetupCode({ url: "ws://h/api/ws", token: "stale", expires_at_ms: 9_999_999_999_999 }));
    const sock = new FakePairSocket();
    const promise = claimPairing(setup, "web-abc", () => sock);
    sock.onOpen!();
    const req = JSON.parse(sock.sent[0]);
    sock.onMessage!(JSON.stringify({ jsonrpc: "2.0", id: req.id, error: { message: "invalid or expired pairing token" } }));
    await expect(promise).rejects.toThrow(/expired pairing token/);
  });

  test("rejects an already-expired setup code before opening a socket", async () => {
    const setup = { url: "ws://h/api/ws", token: "t", expires_at_ms: 1 };
    let opened = false;
    await expect(claimPairing(setup, "web-abc", () => { opened = true; return new FakePairSocket(); })).rejects.toThrow(/expired/);
    expect(opened).toBe(false);
  });
});
