# Gateway protocol surface (grounding for M1/M2)


> **2026-07-11 note:** fork surface only. The daemon has since diverged
> additively: `model.options` is superseded by the daemon's `model.list`;
> the live daemon method surface is `packages/protocol/src/methods.ts` +
> `router.ts`, not this inventory.
The full method + event inventory the fork gateway exposes, captured
2026-07-10 from the hermes-agent upstream (`apps/shared/json-rpc-gateway.ts`,
`apps/desktop`, `web`) + the Android client (`MarmaladeRpc.kt`). This is the
target surface v1 must stay compatible with. `packages/protocol` schemas the
chat-essential subset now and grows it as adapters (M1) need each method;
everything else passes through the gateway envelope-validated but not
param-schema'd.

## Methods (client → server requests)

**Session lifecycle:** `session.create` · `session.list` · `session.resume` ·
`session.history` · `session.interrupt` (+ desktop: title/delete/branch/
compress/undo/usage — land with the UI that needs them)

**Turn:** `prompt.submit` (carries `source: text|voice` — fork patch 4e)

**Interactive responses:** `approval.respond` · `clarify.respond` ·
`sudo.respond` · `secret.respond`

**Config / model:** `config.set` · `model.options` (the only model RPC —
selection rides session.create params, there is no `model.set`)

**Tools / plugins:** `reload.mcp` · `commands.catalog` · `command.dispatch` ·
`slash.exec` · `complete.slash` · `complete.path`

**Process / terminal:** `process.list` · `process.kill` · `terminal.read`

## Events (server → client notifications, envelope `method:"event"`)

`gateway.ready` (first frame) · `session.info` · `message.start` ·
`message.delta` · `message.complete` · `thinking.delta` · `reasoning.delta` ·
`reasoning.available` · `status.update` · `tool.start` · `tool.progress` ·
`tool.complete` · `tool.generating` · `clarify.request` · `approval.request` ·
`sudo.request` · `secret.request` · `background.complete` · `error` ·
`skin.changed` · `subagent.spawn_requested` · `subagent.start`

The union is **intentionally open** — the fork's own type ends with
`(string & {})`. `packages/protocol` validates the event *envelope*, not each
payload; per-payload typing happens at the handler that consumes it.

## Auth (today, legacy)
WS URL query: `?token=<loopback session token>` or `?ticket=<single-use OAuth
ticket, 30s TTL>`. v1 adds the negotiated `hello` bearer-token handshake on
top (handshake.ts); legacy remains accepted until cutover (M5).

## Adapter mapping note (M1)
These gateway methods are the *client-facing* surface. The ClaudeCodeAdapter
translates them onto the Agent SDK: `session.create`/`resume` → `query()` with
`resume`/`fork`; `prompt.submit` → the prompt; SDK message stream → the
`message.*`/`tool.*`/`reasoning.*` events; `approval.request`/`.respond` ↔
`canUseTool` (except device tools — those approve in the bridge, coh-H2).
