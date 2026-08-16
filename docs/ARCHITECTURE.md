# Architecture

How marmalade fits together. This is the durable design doc; for the exact
method and event names see [PROTOCOL-SURFACE.md](PROTOCOL-SURFACE.md) and the
zod contracts in `packages/protocol/src`, which are the wire truth.

## The one-sentence version

One long-running daemon (`marmaladed`) owns sessions, state, and policy; it
spawns **harness** child processes (Claude Code, OpenCode) to do the actual
agent work, normalizes whatever they emit into a single event vocabulary, and
fans that out to clients over a JSON-RPC WebSocket gateway.

```
  clients                    daemon (marmaladed)                harnesses
 ┌─────────┐            ┌──────────────────────────┐        ┌──────────────┐
 │ webui   │            │  gateway (WS, JSON-RPC)  │        │ Claude Code  │
 │ CLI     │◄── ws ────►│  router                  │◄─spawn─│  (Agent SDK) │
 │ Android │            │  session manager (SQLite)│        ├──────────────┤
 │ desktop │            │  policy / identity       │        │ OpenCode     │
 └─────────┘            │  adapters + normalize    │◄─spawn─│  (ACP)       │
                        │  transcripts (NDJSON)    │        └──────────────┘
                        │  search / cron / terminal│
                        └──────────────────────────┘
```

The dividing line is deliberate and load-bearing:

- **The harness owns the agent loop** — model calls, context, tools, MCP
  servers, skills, its own transcript.
- **The daemon owns everything around it** — which sessions exist, what they're
  named, who may talk to them, when they run, what's searchable, what gets
  pushed to your phone.

Marmalade implements no tool-use loop of its own. Every capability the agent has
is a capability the harness already had.

## Components

### `packages/protocol`

Frozen protocol v1: a JSON-RPC 2.0 envelope, zod schemas for every method's
params, and the negotiated `hello` handshake. Requests are validated at the
envelope *and* param level; events are validated at the envelope level only —
the event union is intentionally open, and per-payload typing happens at the
handler that consumes it.

Protocol v1's shape is stable in practice, not a compatibility promise yet.
Its surface grows additively, and clients
learn what a given daemon supports from the feature list in the handshake
response (`stable-ids`, `subscribe`, `pairing`, `attachments`, `undo`,
`clarify`, `workspaces`, `search`, `search_archive`, `settings`, plus
host-conditional ones like `terminal`). A client that doesn't see a feature must
hide that UI rather than call and fail.

### `packages/daemon` — `marmaladed`

The whole orchestrator. Its internal seams:

| Module | Responsibility |
|---|---|
| `gateway.ts` | WebSocket listeners, connection trust, handshake, envelope validation |
| `router.ts` | Method dispatch; owns the live session map and the emit path |
| `policy.ts` | Session factory + the structural security invariants |
| `session-manager.ts` | SQLite session index; the supervisor seed |
| `identity.ts` | Stamps every outbound event with stable ids and `seq` |
| `adapter.ts` | The versioned harness seam |
| `claude-code-adapter.ts` / `opencode-adapter.ts` | The two concrete harnesses |
| `normalize.ts` / `acp-normalize.ts` | Harness messages → one event vocabulary |
| `transcript-cache.ts` | Flat NDJSON transcript per session (replay cache) |
| `message-store.ts` | The identity/ordering index for domain messages |
| `search-store.ts` / `archive-indexer.ts` | FTS5 search sidecar + archive corpus |
| `supervisor.ts` | Detects silently-dead harness children |
| `pairing.ts` / `device-store.ts` | Device pairing and bearer tokens |
| `cron-*.ts` | Scheduled unattended turns |
| `terminal.ts` | PTY terminals (not sessions — see below) |
| `behavior.ts` / `state-preload.ts` | The main session's system prompt and cold context |

### `packages/ui-tree`

The Marmalade UI v1 node-tree parser: tolerant JSON repair plus a response
grammar, so an agent can emit a structured UI (buttons, inputs, lists) instead
of prose and every client renders it the same way. Shared by the webui and CLI
renderers. Spec: [dynamic-ui/marmalade-ui-v1.md](dynamic-ui/marmalade-ui-v1.md).

### Clients

All clients are peers over the same protocol; none is privileged.

- **`packages/webui`** — React + Vite SPA, the reference client. Built with
  `base: "./"` so the static bundle runs from a server root, a subpath, or a
  `file://` WebView. It is shell-agnostic on purpose: a desktop shell loads the
  same bundle rather than getting its own UI.
- **`packages/cli`** — interactive terminal client, plus `pair` / `cron` /
  `usage` subcommands that run one-shot RPC sessions.
- **Android and desktop clients** speak the same protocol v1 from a separate,
  not-yet-public repository.

## The session model

A **session** is a durable conversation with a harness child behind it. Two
things are worth understanding, because most of the design falls out of them.

### Lifecycle and run-state are orthogonal

Sessions do not have one status enum. They have two independent fields:

- `lifecycle`: `active` | `ended` — does this session exist?
- `runState`: `starting` | `idle` | `running` | `awaiting_input` | `hung` — is a
  turn in progress *right now*?

Protocol v1 clients read a derived `status` for convenience, but that view is
computed, never stored. **No state transition ever mints or changes an id.**
"Is the agent mid-run?" is a field flip, not a new session.

That matters because a session's harness child is disposable. Sessions past
`idle_reap_ms` get their child stopped — the session stays `active` and
resumable with the **same id**, and the next `prompt.submit` revives it
transparently. The default reap window sits just past the harness's prompt-cache
TTL, so reaping never throws away a warm cache that a follow-up turn would have
ridden.

### Two id spaces, strictly separated

Clients see only **domain ids** — `sessionId`, `messageId` — which are minted
once and never change. The **harness's own ids** (its session id, its per-message
uuids) are captured privately by the daemon for resume and rewind, and never
cross the gateway.

Ordering is by `seq`, a plain per-session integer written by the daemon, which is
the single writer. Timestamps are metadata for display; nothing orders by
wall-clock.

Identity stamping happens at **one seam**: the router passes every outbound
session event through `SessionIdentity.stampEvent()`. No adapter can forget to
stamp, including events an adapter synthesizes itself.

### Storage: SQLite indexes, NDJSON content

A locked division of labor:

- **SQLite** (`sessions.db`) holds *indexes* — the session rows, the message
  identity/ordering table, devices, cron jobs, workspaces, the usage meter.
- **NDJSON** (one file per session) holds the *transcript* — the already
  normalized gateway events, appended verbatim, one JSON object per line.
  Replay is reading the file back.

The transcript cache is a **cache**, not a source of truth; the harness owns the
canonical transcript. There is no second event-sourcing store.

One wrinkle worth knowing if you read transcripts: during a turn the file holds
raw streaming deltas (that's deliberate crash durability — mid-turn they're the
only copy of a half-written message). At turn end, each message's delta run is
folded into one consolidated event carrying the concatenated text. Both shapes
replay to the same rendered output, so readers must **accumulate delta text per
`message_id`** rather than assume chunk counts.

Search lives in a **separate** `search.db` FTS5 sidecar, precisely because an
FTS table stores content and would otherwise break the sentence above. The
sidecar is disposable by declaration: delete it and the boot reconcile rebuilds
it from the transcripts. Every consistency question has the same answer —
rebuild.

### The main session

One session is special: a daemon-managed **singleton "main" session**, get-or-
created at boot and kept warm (exempt from idle reaping). This is the assistant
home surface — the conversation that's just *there* when you open a client.

It's the only session that gets a **custom system prompt**, rendered from the
canonical spec in `behavior/` (identity, state-upkeep, self-improve) plus an
optional user addendum at `~/.marmalade/behavior.md`. The core spec is locked;
the addendum can only append. The rendered prompt is a static string, so it
shares a prompt-cache entry for free.

Rollup state (the daily/weekly/monthly summaries the assistant keeps about you)
is injected as **first-message context**, not into the system prompt — that
keeps the prompt static and cacheable, and the injection happens once per
session generation.

Coding sessions instead run on the harness's own preset.

## Harness adapters

`adapter.ts` is a thin, versioned seam. Its budget is 1–2 breaking changes per
adapter per year; that constraint is what keeps it thin.

An adapter's job is narrow: spawn a harness child for a `SessionSpec`, feed it
prompts, and call back with **already-normalized gateway events**. Normalization
happens once, at the adapter, and everything downstream — the router, the
transcript cache, the search indexer, every client — sees one vocabulary
regardless of which harness produced it.

Two concrete adapters exist:

- **`ClaudeCodeAdapter`** — drives the official
  `@anthropic-ai/claude-agent-sdk`. `session.create` / `resume` map onto
  `query()` with `resume` / `fork`; SDK messages map onto `message.*`, `tool.*`,
  and `reasoning.*` events; approvals map onto `canUseTool`.
- **`OpenCodeAdapter`** — drives `opencode acp` as a subprocess over Zed's Agent
  Client Protocol, with its own normalizer (`acp-normalize.ts`).

Having a second adapter is the point, not a feature: it is the proof that the
seam is real and not accidentally shaped like one SDK. A generic `AcpAdapter`
gets extracted on the rule of three, not before.

Adapters declare their capabilities rather than being assumed uniform — e.g.
`supportsResumeAt` gates whether `session.undo` is offered for that session,
and a harness with no per-message ids simply never reports one.

### Supervision

The daemon babysits long-running children. The supervisor scans for sessions
whose heartbeat has gone stale while still marked live, marks them `hung`, and
fires exactly one loud alert. Heartbeats come from the adapter's `onActivity`
callback on any stream activity.

This exists because the failure mode it prevents — a background agent that dies
silently and is never noticed — is the one that actually hurts. A failure must
be visible; it must never be eternal silence.

## The gateway

### Connecting

A client opens `ws://<host>:9130/api/ws`, optionally with `?token=`, and waits
for a `gateway.ready` event as the first frame. It then sends `hello` to
negotiate: the daemon replies with the protocol version and its feature list.

Events for a session go only to connections **subscribed** to that session
(`session.subscribe` / `session.unsubscribe`); the subscriber registry is a
separate module from the router so the fan-out path stays small.

### Trust boundary

Two tiers, decided by the connection's **remote address**, never by anything in
the message body:

**Loopback = trusted as the local user.** A process connecting from `127.0.0.1`
already runs as you, with your filesystem and your harness credentials. A shared
secret between two processes with identical privilege adds no boundary. This is
why `packages/cli` connects with a literal `?token=cli` — on a loopback
connection the token value is never checked. It is not a credential.

**Non-loopback = trusted by nothing until paired.** Such a connection routes
**no method at all** except `pairing.claim` until it presents a valid device
token. The flow:

1. A trusted (loopback) context calls `pairing.start`. The daemon mints a
   single-use bootstrap token with a 10-minute TTL and returns a base64url
   setup code, rendered as a QR.
2. The new device connects unauthenticated and calls `pairing.claim` with that
   bootstrap token and its declared device id.
3. The daemon mints a long-lived 256-bit per-device bearer token, stores only
   its SHA-256 hash, and binds the sanitized device id to it.
4. Thereafter the device authenticates every connection with that token. The
   token's device id is the **verified** origin identity; a `hello` cannot
   override it.
5. `device.revoke` deletes the tokens and the roster row and drops live
   connections. Revocation is immediate.

Bootstrap tokens live only in memory, so a daemon restart invalidates pending
pairings (re-pair; it's cheap) and nothing secret is ever written in plaintext.
Pending codes are capped and repeated failed claims lock out.

**Binding is constrained at startup.** The daemon binds loopback always, plus
optionally one tailnet interface (`100.64.0.0/10`). Any other bind host —
`0.0.0.0`, a LAN address — is refused with an explanatory error rather than
silently accepted. When a second interface is configured the daemon **dual-binds**
rather than replacing the loopback listener: a same-box connection to the
tailnet IP arrives with a non-loopback source address, so a single tailnet bind
would quietly destroy the loopback trust path.

Origin identity (user, device, platform) is derived by the daemon from the
authenticated connection, never trusted from the message body.

## Auth posture (BYO-auth)

Marmalade never stores, reads, or fronts a provider credential. There is no key
to give it. This is enforced structurally in `packages/daemon/src/policy.ts`,
which is the single choke point every spawn routes through — because a
record-field check is not enforcement.

What it enforces:

- **An `authClass` selects a real filesystem auth context.** The official
  harness binary discovers its auth from disk, so isolation has to be a real
  `HOME` and `CLAUDE_CONFIG_DIR`, not a flag. The `subscription` class points at
  your real home so the binary finds `~/.claude` and does its own auth;
  `metered` and `local` get dedicated scratch contexts under
  `~/.marmalade/auth-contexts/` with **no** subscription OAuth reachable.
- **The child environment is an allowlist built from empty** — `PATH`, `HOME`,
  `CLAUDE_CONFIG_DIR`, and (for `metered` only) an API key fetched from the OS
  keyring. Never `process.env` minus a denylist: a strip-list only catches the
  names you thought of.
- **A positive-shape assertion runs on every spawn.** It throws if a forbidden
  token variable is present, if any env value looks like a subscription OAuth
  token, or if a non-subscription context points `HOME` at your real home.
- **No guest execution.** The session factory refuses `principal=guest`
  outright; that lane stays closed until OS-level confinement exists. The
  failure mode is "no answer", never "leak".

The systemd unit deliberately carries no provider credentials either, which is
why a PTY terminal (which inherits the daemon's own environment) inherits
nothing the agent's children wouldn't already have.

## Terminals are not sessions

`terminal.*` hosts real PTYs alongside agent sessions, and they are deliberately
a different kind of object: no identity stamping, no transcript, no replay
cache, no supervisor. Output is transient and attach-scoped — `terminal.data`
goes only to currently-attached connections, and scrollback recovery is the
ring-buffer snapshot returned by `terminal.attach`. Terminals die with the
daemon, and clients render that honestly.

Security-wise a terminal is an arbitrary shell as the daemon's user. That is not
a *new* trust boundary — any paired device can already drive an agent that runs
commands — but it does bypass the harness's approval layer, so the whole surface
has a config kill-switch (`terminal_enabled`). `node-pty` is loaded dynamically:
a broken native build degrades to "feature not advertised", never a daemon
crash.

## Design rules

A few principles that explain otherwise-surprising choices:

- **Enforce structurally, not by convention.** If an invariant can be forgotten
  at a call site, route every call site through one function instead (policy,
  identity stamping, the session factory).
- **Normalize once.** The adapter is the only place that knows a harness's
  dialect.
- **SQLite indexes, files hold content.** Anything that violates this becomes a
  disposable, rebuildable sidecar.
- **Ids are names, not state.** Mint once, never change, express progress as a
  field.
- **Silent failure is the enemy.** Strict config validation that fails startup
  loudly, a supervisor for silently-dead children, errors that reach the client
  instead of hanging.
- **Degrade to "not advertised."** A missing optional dependency or directory
  turns a feature off cleanly rather than crashing or half-working.
- **Don't build an engine where a file will do.** Several of these subsystems
  are one file and a table on purpose.

## Further reading

- [PROTOCOL-SURFACE.md](PROTOCOL-SURFACE.md) — method and event inventory
- [SDK-FACTS.md](SDK-FACTS.md) — empirically verified harness SDK behavior
- [ROBUSTNESS-REVIEW.md](ROBUSTNESS-REVIEW.md) — failure-mode review
- [dynamic-ui/marmalade-ui-v1.md](dynamic-ui/marmalade-ui-v1.md) — dynamic UI spec
- [plans/](plans/) — per-subsystem design notes (terminal, undo, webui, approvals,
  desktop client, hardening)
- [historical/M0-BUILD-LOG.md](historical/M0-BUILD-LOG.md) — how the core got built
