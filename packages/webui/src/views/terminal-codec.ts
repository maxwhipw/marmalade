// terminal-codec.ts — base64 ↔ bytes for the terminal wire (terminal.data /
// terminal.input / attach snapshots). Pure, browser-API only (atob/btoa,
// TextEncoder) — vitest covers the roundtrips.

/** Decode a base64 payload (terminal.data / snapshot) to bytes for xterm's
 *  write(). */
export function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Encode keyboard text (xterm onData — a JS string, possibly multi-byte
 *  unicode) as base64 UTF-8 for terminal.input. */
export function textToB64(text: string): string {
  return bytesToB64(new TextEncoder().encode(text));
}

/** Encode xterm onBinary data (a "binary string": one byte per charCode) —
 *  non-UTF-8 paste path. */
export function binaryToB64(bin: string): string {
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i) & 0xff;
  return bytesToB64(out);
}

function bytesToB64(bytes: Uint8Array): string {
  // btoa takes a binary string; chunk to keep fromCharCode off giant spreads.
  let bin = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}
