// The checked-in wire-fixture corpus — `packages/protocol/fixtures/` — loaded
// as typed frames.
//
// The corpus is the ONE shared source of truth for both sides of the wire:
// this package validates every fixture against the zod contracts
// (fixtures.test.ts), and the Android client round-trips the same files
// through its own serializers (apps/android :shared ProtocolFixtureTest). A
// host change that alters a shape therefore fails BOTH suites, mechanically,
// instead of surfacing as a MethodNotFound toast on a phone.
//
// Layout and naming (the only convention there is):
//
//   fixtures/requests/<method>[-<variant>].json    a whole client→server frame
//   fixtures/responses/<method>[-<variant>].json   a whole result frame
//   fixtures/events/<event>[-<variant>].json       a whole server→client event
//   fixtures/errors/<name>.json                    a whole error frame
//
// A file holds nothing but the frame — no metadata wrapper — so a fixture can
// be pasted onto a socket verbatim. The method/event NAME is the basename up
// to the first "-" (no protocol method or event name contains a hyphen, and
// the variant suffix is free text: `session.create-full.json` is a
// `session.create` request).
//
// Content is neutral and synthetic by rule: RFC 5737 documentation IPs
// (192.0.2.x), RFC 2606 names (host.example), `/home/user/...` paths, and ids
// in the daemon's documented shapes (`s_<uuid>`, 12-char base64url message
// ids, `w_`/`t_<uuid>`, `cj_<base64url>`).

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

export const FIXTURES_DIR = fileURLToPath(new URL("../fixtures/", import.meta.url));

export type FixtureKind = "requests" | "responses" | "events" | "errors";

export interface Fixture {
  /** e.g. "requests/session.create-full.json" — the id used in failure output. */
  readonly path: string;
  readonly kind: FixtureKind;
  /** The method or event name: basename up to the first "-". */
  readonly name: string;
  /** The variant suffix after the first "-", or "" for the base fixture. */
  readonly variant: string;
  /** The parsed frame, exactly as it sits on disk. */
  readonly frame: Record<string, unknown>;
}

function loadKind(kind: FixtureKind): Fixture[] {
  const dir = join(FIXTURES_DIR, kind);
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((file) => {
      const base = file.slice(0, -".json".length);
      // requests/responses/events are named for a protocol method or event
      // (never hyphenated), so the first "-" starts a free-text variant suffix.
      // errors/ names are free-form and hyphenated — no split there.
      const dash = kind === "errors" ? -1 : base.indexOf("-");
      return {
        path: `${kind}/${file}`,
        kind,
        name: dash === -1 ? base : base.slice(0, dash),
        variant: dash === -1 ? "" : base.slice(dash + 1),
        frame: JSON.parse(readFileSync(join(dir, file), "utf8")) as Record<string, unknown>,
      };
    });
}

/** Every fixture in the corpus, in a stable (kind, filename) order. */
export function loadFixtures(): Fixture[] {
  return (["requests", "responses", "events", "errors"] as const).flatMap(loadKind);
}

export const REQUEST_FIXTURES = loadKind("requests");
export const RESPONSE_FIXTURES = loadKind("responses");
export const EVENT_FIXTURES = loadKind("events");
export const ERROR_FIXTURES = loadKind("errors");

// ── Views kept for the frame-envelope tests ─────────────────────────────────
// (These used to be hand-written literals here; they are now projections of
// the corpus, so the envelope tests grow with it automatically.)

export const CLIENT_REQUESTS = REQUEST_FIXTURES.map((f) => f.frame);
export const SERVER_RESPONSES = [...RESPONSE_FIXTURES, ...ERROR_FIXTURES].map((f) => f.frame);
export const SERVER_EVENTS = EVENT_FIXTURES.map((f) => f.frame);

/** The v1 hello handshake, as a v1-capable client sends it. */
export const HELLO_REQUEST = REQUEST_FIXTURES.find(
  (f) => f.name === "hello" && f.variant === "",
)!.frame;

/** A method with no schema — must still parse as a valid request envelope.
 *  Deliberately NOT a corpus file: the corpus asserts that every fixture maps
 *  to a schema, and this frame exists precisely to have none. */
export const UNKNOWN_METHOD_REQUEST = {
  jsonrpc: "2.0",
  id: "6",
  method: "config.set",
  params: { key: "voice.enabled", value: true },
} as const;

/** Frames that MUST be rejected — malformed / wrong version. Inline for the
 *  same reason: they are not wire fixtures, they are the negative space. */
export const BAD_FRAMES = [
  { jsonrpc: "1.0", id: "1", method: "x" }, // wrong jsonrpc version
  { id: "1", method: "x" }, // missing jsonrpc
  { jsonrpc: "2.0", method: "x" }, // request missing id
  { jsonrpc: "2.0", id: "1" }, // request missing method
] as const;
