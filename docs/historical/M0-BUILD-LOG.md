# M0 build log

Built overnight 2026-07-10 on branch `m0-protocol-daemon` (held unpushed
pending sign-off per the milestone gate in the internal architecture notes).
Executes M0 from §Decision 6 of those notes, grounded in the
real gateway wire protocol and verified SDK facts (see `SDK-FACTS.md`).

## What's built (all tested, `pnpm -r build && pnpm -r test` green: 21/21)

### `packages/protocol` — frozen gateway protocol v1 (zod contracts)
- `frames.ts` — JSON-RPC 2.0 request/response/event schemas + constructors,
  grounded in the wire dialect the existing Android client documents
  (`JsonRpcClient.kt`). Known-event-name enum from the authoritative
  `json-rpc-gateway.ts`. Error-code table (standard + marmalade extensions).
- `handshake.ts` — the negotiated `hello` request/result. Optional first
  client frame; legacy clients (URL `?token=`/`?ticket=` + `gateway.ready`
  first) still work — the reconciliation coh-H1 demanded.
- `methods.ts` — starter typed method contracts (session.create,
  prompt.submit, session.resume) matching the params the client really sends;
  `source=voice` tag carried (an earlier internal patch → v1 requirement).
- Tests: **frame-replay** against captured real frames (`test/fixtures.ts`) —
  every captured client request/response/event validates; malformed frames
  reject; hello parses and version-negotiates. This is the M0 contract test
  the review asked for (replay real frames, not a JSON-Schema export nobody
  reads).

### `packages/daemon` — marmaladed skeleton
- `policy.ts` — **the security core** (Decision 5), the single session
  factory + enforcement helpers. Structurally enforces: guest execution
  refused in v0.1 (sec-H2); authClass → a *dedicated* `CLAUDE_CONFIG_DIR`/
  `HOME` so a metered child can't reach `~/.claude`'s subscription OAuth
  (sec-H1); child env built as an **allowlist from empty**, positive-shape
  asserted, `sk-ant-oat*`/`CLAUDE_CODE_OAUTH_TOKEN` rejected (sec-M1/5.5);
  metered keys required from the keyring, never an inherited env var (5.3).
- `session-manager.ts` — SQLite **index** (via built-in `node:sqlite`, zero
  native deps) — the index only, not an event-sourced store (simp-H1).
  Includes the **M1.5 supervisor seed**: `findSilentlyDead()` flags a live
  session past its heartbeat timeout (the OpenClaw silent-failure antidote).
- `gateway.ts` — WS gateway: sends `gateway.ready` first (legacy-compatible),
  upgrades on `hello`, binds the principal, validates frames, routes to a
  pluggable handler (default MethodNotFound until adapters land in M1).
- `config.ts`, `index.ts` — daemon config (localhost :9130, chosen to avoid
  colliding with the predecessor gateway) + startable entry point.
- Tests: policy invariants (guest refused, allowlist env, no-leak assertion,
  metered-key requirement) + session-manager (CRUD, cwd/session-id binding
  for resume, silent-death detection).

### `deploy/marmaladed.service`
systemd **user** unit. Carries the sec-5.5 warning inline: the unit must hold
no provider token (children inherit the daemon env).

## Live smoke (verified)
Started the daemon, connected a WS client: received `gateway.ready`, sent a
`hello` → got a valid `HelloResult` (protocolVersion 1, dev principal), sent
`session.create` → got the expected `-32601 method not routable in M0`.

## Deliberately NOT done in M0 (correctly deferred)
- Method routing / harness adapters (M1). The handler is a stub.
- Real token→principal lookup + revocation (M2 pairing). `hello` validates
  presence only.
- Transcript NDJSON cache (M1), state preload (M4a), MCP servers (M5/M6).
- The supervisor *loop* (M1.5) — only its schema + detection query exist;
  real heartbeats need M1's adapter lifecycle events.

## Toolchain notes
- **Zero native deps.** `node:sqlite` + `node:test` are built-ins; only `zod`
  (boundary validation, as the architecture specified) and `ws` (WS server)
  are runtime deps — both zero-transitive-dep, supply-chain-light.
- Tests run via `node --test --experimental-strip-types` (no test-framework
  dep). Test files import the built `dist/` output.
- pnpm workspace; `pnpm -r build`, `pnpm -r test` from the root.
