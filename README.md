<p align="center">
  <img src="docs/assets/mascot.png" width="200" alt="The Marmalade mascot, a smiling jar of marmalade">
</p>

# marmalade

**A personal assistant that runs on your machine, connected to your stuff.**

Think of the assistant on your phone, but it lives on hardware you own. It
remembers, it can actually *do* things on your system, and it reaches you from
your phone, your browser, or your terminal.

What makes it stand out:

- **One assistant, every screen.** A single always-warm "main" conversation,
  plus as many scoped sessions as you want, shared live across the Android
  app, the web client, and the CLI.
- **It remembers.** Sessions survive restarts, and full-text search covers
  every conversation you have ever had with it.
- **It acts on your machine.** Your files, your commands, your MCP servers,
  your skills: the assistant works with the real system it lives on.
- **Hands-free on Android.** Wake word, on-device speech recognition and
  speech output, and system-assistant integration.
- **It works while you sleep.** Scheduled turns run unattended on a cron,
  with push notifications when something needs you.

Under the hood, marmalade is an orchestrator over coding harnesses: it drives
an agent you already have installed and signed in (Claude Code or OpenCode
today, more planned) and owns everything around the agent loop. Sessions,
identity, a gateway protocol, scheduling, search, device pairing, and policy
all live here; the harness keeps doing what it is good at.

> **Alpha.** This is early software built for one person's daily use and being
> opened up. It works (it is used every day), but expect rough edges, missing
> docs, and breaking changes. See [Status](#status).

## Why it exists

Coding harnesses are extremely capable general agents that happen to be shipped
as terminal tools. They can already read your files, run your commands, call
your MCP servers, and use your skills. What they can't do is *persist*: close
the terminal and the assistant is gone. There is no session that is still there
tomorrow, no way to talk to it from your phone, no cron, no shared state across
the half-dozen conversations you have going.

Marmalade adds exactly that layer, and nothing more:

- **The harness owns the agent loop.** Tools, context, model calls, approvals.
- **Marmalade owns the world around it.** Sessions, transcripts, search,
  scheduling, notifications, devices, workspaces, policy.

The result is a personal assistant rather than a coding tool: one long-running
"main" conversation that is always warm, plus as many scoped sessions as you
want, reachable from any device you have paired.

## Bring your own harness

Marmalade doesn't handle provider credentials. There is no API key to paste in
and no sign-in flow of its own. It launches the harness you already installed
and authenticated, and the harness does its own auth exactly as it does when
you run it by hand. If `claude` works in your shell, marmalade works.

## Prerequisites

- **Node.js >= 22** (the daemon uses `node:sqlite`, which needs a recent Node)
- **pnpm** (this repo is pnpm-only; it's pinned via `packageManager`)
- **Claude Code, installed and signed in**: run `claude` once so it has a
  working session. (Alternatively OpenCode: the `opencode` binary on `PATH`
  and logged in.)

Optional, feature-gated (absent means the feature is simply not advertised to
clients):

- `node-pty` builds: enables PTY terminals
- a Whisper-family CLI (default: `whisper-ctranslate2`): enables voice
  transcription

## Quickstart

```bash
pnpm install
pnpm -r build
pnpm -r test        # 874 tests

node packages/daemon/dist/index.js     # marmaladed, listening on 127.0.0.1:9130
```

In a second terminal, talk to it:

```bash
node packages/cli/dist/index.js        # interactive terminal client
node packages/cli/dist/index.js pair   # show a QR setup code for a new device
node packages/cli/dist/index.js cron   # manage scheduled jobs
node packages/cli/dist/index.js usage  # token/spend summary
node packages/cli/dist/index.js secret # get/set/ls/rm/check keyring entries
```

Or run the web client:

```bash
pnpm --filter @marmalade/webui dev     # http://127.0.0.1:9131
```

The webui is a plain static SPA (`base: "./"`), so `pnpm --filter
@marmalade/webui build` produces a `dist/` you can serve from any static host or
load inside a WebView shell.

### Clients

- **webui**: React SPA in this repo (`packages/webui`), the reference client.
- **CLI**: `packages/cli`, interactive terminal client with the dynamic-UI
  renderer.
- **Android**: native Kotlin/Compose client in
  [`apps/android`](apps/android), same protocol v1, plus voice (wake word,
  on-device STT/TTS) and system-assistant integration. See its
  [README](apps/android/README.md), and note it needs a one-time
  `scripts/fetch-assets.sh` for the model/terminal binaries.

## Devices and pairing

- **Local processes are trusted as you.** A process connecting from loopback
  is already running as your user, so it needs no credential. (The `?token=cli`
  in the CLI's URL is decorative; loopback trust comes from the remote
  address, not the token value.)
- **Remote devices must pair.** A trusted context runs `pairing.start`, which
  renders a single-use QR setup code (10-minute TTL). The device claims it and
  receives a long-lived per-device bearer token; only its hash is stored, and
  `device.revoke` cuts it off immediately.
- **The daemon binds loopback or a tailnet interface** (`100.64.0.0/10`),
  never `0.0.0.0` or a LAN address; those are rejected at startup. Don't
  expose it to the open internet.

## Configuration

Config resolves in precedence order: **environment > config file > built-in
default**.

### The config file

`~/.marmalade/daemon/config.json` (override the path with `MARMALADE_CONFIG`).
It's **strict-validated**: a typo'd key or a bad value fails startup loudly
rather than being silently ignored. Every key is optional.

```jsonc
{
  "bind_port": 9130,
  "bind_host": "100.x.x.x",          // optional SECOND bind (tailnet only)
  "approvals_mode": "auto",          // "auto" | "prompt"
  "max_live_sessions": 8,
  "idle_reap_ms": 3900000,           // idle sessions are reaped (resumable)
  "default_model": "claude-opus-5",   // a harness model id
  "default_effort": "high",          // low|medium|high|xhigh|max
  "model_efforts": {                 // per-model floors/ceilings; clamps, never rejects
    "claude-opus-5": { "min": "high" }
  },
  "terminal_enabled": true,
  "context_reminder_percent": 75,
  "budget": { "metric": "usd", "daily_limit": 20 },
  "ntfy": { "topic": "your-topic" },
  "transcribe_command": ["whisper-ctranslate2", "{file}", "--output_dir", "{dir}"]
}
```

Clients can edit most of these live via `settings.get` / `settings.update`; keys
currently pinned by an environment variable are reported back as locked so the
UI can disable them.

### Environment variables

The ones that matter:

| Variable | Effect |
|---|---|
| `MARMALADE_CONFIG` | Path to the config file |
| `MARMALADE_BIND_PORT` | Gateway port (default `9130`) |
| `MARMALADE_BIND_HOST` | Extra non-loopback bind (loopback is always bound) |
| `MARMALADE_GATEWAY` | *(CLI)* full gateway WS URL to connect to |
| `MARMALADE_APPROVALS` | `auto` or `prompt` |
| `MARMALADE_DEFAULT_MODEL` | Default model for new sessions |
| `MARMALADE_DEFAULT_EFFORT` | Default reasoning effort |
| `MARMALADE_MAX_LIVE_SESSIONS` | Cap on concurrent live harness children |
| `MARMALADE_IDLE_REAP_MS` | Idle-session reap window |
| `MARMALADE_TERMINAL_ENABLED` | `0`/`false` disables PTY terminals |
| `MARMALADE_NTFY_TOPIC` / `_SERVER` / `_TOKEN` | Push-notification fallback |
| `MARMALADE_USER_BEHAVIOR` | Path to your own behavior addendum (markdown) |

### Optional integrations

Two config paths point at personal knowledge stores. Their defaults reflect one
particular setup; point them anywhere, or ignore them entirely:

- `MARMALADE_WIKI_ROOT` (default `~/.marmalade/wiki`): a markdown notes tree
  the main session preloads rollup state from.
- `MARMALADE_SKILLS_REGISTRY` (default `~/.marmalade/skills`): a registry of
  skill directories that get symlinked into the harness's skills dir.

**Both silently no-op if the directory doesn't exist.** You don't need them, and
nothing breaks without them.

## What it does today

Every one of these is implemented and covered by tests:

- **Sessions**: create, resume, list, archive, delete, fork, title, summarize,
  interrupt, steer mid-turn, compact, clear, and **undo** (rewind-resume: the
  harness rewinds its own context, non-destructively).
- **A singleton "main" session** that stays warm by design: the assistant home
  surface, exempt from idle reaping.
- **Model and reasoning-effort control**, mutable mid-session, with per-model
  bounds that clamp rather than reject.
- **Cross-session message search** (SQLite FTS5) plus an **archive corpus** for
  historical transcripts, with find-in-conversation and deep links.
- **Workspaces**: folder-scoped grouping of sessions and terminals, with
  context previews.
- **PTY terminals** over the gateway (`terminal.*`), host-conditional on
  `node-pty` loading.
- **Cron**: scheduled unattended turns with timezone handling, restart
  catch-up, and single-flight.
- **Attachments**: images, files, and PDF page rendering, staged and consumed
  at the next prompt.
- **Voice transcription** (`audio.transcribe`) via a configurable STT command.
- **Device pairing**: QR setup codes, hashed per-device bearer tokens,
  immediate revocation.
- **Skills / MCP servers / plugins**: list and toggle, plus registry sync.
- **Usage metering** with an optional daily budget that blocks *unattended*
  turns only (a prompt you typed is never refused).
- **ntfy** as a secondary push path when the client's WS isn't connected.
- **Dynamic UI v1**: the agent can emit structured UI node trees that both the
  webui and CLI render (`packages/ui-tree`).

Both harness adapters are proven live: Claude Code (driven through the official
Agent SDK) and OpenCode (ACP).

## Status

**Alpha, single-host, single-principal.**

Built and green: **874 tests** (daemon 559, webui 236, cli 34, protocol 28,
ui-tree 9, icons 8), full workspace build clean.

Honest caveats:

- **Not multi-user.** The policy layer has a `principal` concept, but guest
  execution is explicitly refused; it needs OS-level confinement that doesn't
  exist yet.
- **Not hardened for hostile networks.** It binds loopback or a tailnet
  interface. Do not expose it to the open internet.
- **Protocol v1 is still evolving.** It grows additively today, but expect
  changes while the project is alpha. Clients negotiate features at handshake.
- **Docs are thin** outside this README and `docs/`.
- Some subsystems (device-bridge MCP, CalDAV) are designed but not built.

## Layout

| Path | What |
|---|---|
| `packages/protocol` | JSON-RPC v1 gateway contracts (zod) + the negotiated `hello` handshake |
| `packages/daemon` | `marmaladed`: gateway, router, session index, harness adapters, normalization, policy, search, cron, terminals, pairing |
| `packages/webui` | React + Vite SPA client (shell-agnostic static bundle) |
| `packages/cli` | `marmalade`: interactive terminal client |
| `packages/ui-tree` | Marmalade UI v1 node-tree parser, shared by the webui and CLI renderers |
| `packages/icons` | The Marmalade icon map: wire icon names to vendored SVG path data |
| `apps/android` | Native Android client (Kotlin, Jetpack Compose) speaking the same protocol v1 |
| `behavior/` | The behavior spec rendered into the main session's system prompt |
| `docs/` | Architecture, protocol surface, verified SDK facts, design plans |
| `deploy/` | Example systemd units |

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how it fits together
- [docs/PROTOCOL-SURFACE.md](docs/PROTOCOL-SURFACE.md): method/event inventory
- [docs/SDK-FACTS.md](docs/SDK-FACTS.md): empirically verified harness SDK behavior
- [docs/dynamic-ui/marmalade-ui-v1.md](docs/dynamic-ui/marmalade-ui-v1.md): the dynamic-UI spec

## Conventions

- **One repo, host + client.** The daemon and the Android client live
  together so the wire contract can't drift silently: both sides' CI runs
  against the same `packages/protocol` fixtures, and a daemon change that
  breaks the client fails the PR, not the phone.
- **pnpm only**, never npm or yarn
- TypeScript throughout; ESM everywhere
- Tests are `node --test` (daemon, cli, protocol, ui-tree, icons) and vitest (webui)

## License

Apache-2.0; see [LICENSE](LICENSE).

Third-party code borrowed or adapted into this tree is credited in
[CREDITS.md](CREDITS.md), which records the source, its license, and the exact
borrow site; the verbatim license texts are in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
