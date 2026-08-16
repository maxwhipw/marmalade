// @marmalade/protocol — the frozen gateway protocol v1 surface.
// Daemon and future TS clients (web UI) import from here.
//
// Wire dialect is unchanged from the predecessor gateway (Decision 1); v1 adds
// a negotiated `hello` handshake (handshake.ts). Behaviours that were local
// patches on that gateway are protocol v1 requirements here:
//   - `source=voice` origin tagging on prompt submission
//   - cross-client `seen_at` read tracking
//   - session-delete lineage cascade semantics

export * from "./frames.js";
export * from "./handshake.js";
export * from "./methods.js";
export * from "./events.js";
export * from "./usage-format.js";
