# Gateway Events — Handling Audit

> **Before changing anything here or in the code referenced below, confirm this document is still accurate.** The upstream openclaw protocol moves quickly. Verify event names and payload shapes against `src/gateway/server-methods-list.ts` and `src/gateway/protocol/schema/` in the upstream OpenClaw repo before acting. Last verified against upstream commit `9a93ea9d7` on 2026-04-18.

Authoritative list of server → client events is `GATEWAY_EVENTS` in `src/gateway/server-methods-list.ts:142`. The same array is the source of truth for TypeBox schemas generated under `src/gateway/protocol/schema/`.

Status legend:
- ✅ **Implemented** — event is received, parsed, and produces the right behavior on device.
- ⚠️ **Needs debugging** — event is wired but behavior is partial, unverified on device, or known to have gaps.
- ❌ **Not implemented** — event is dropped by `handleGatewayEvent` / `onEvent` and has no code path.
- ➖ **N/A** — intentionally unhandled because the event doesn't apply to a mobile node/operator (document the reason).

| Event | Status | Schema / docs | Current implementation | Notes |
|---|---|---|---|---|
| `connect.challenge` | ✅ | [`schema/frames.ts`](../../../openclaw-latest/src/gateway/protocol/schema/frames.ts), [protocol.md](../../../openclaw-latest/docs/gateway/protocol.md) | `GatewaySession.kt:555` — captured inside the connection listener, used to drive the signed `connect` request. Never escapes to `onEvent`. | Part of the handshake, not a runtime event. |
| `tick` | ⚠️ | `TickEventSchema` in `schema/frames.ts:5`; `TICK_INTERVAL_MS = 30_000` in `server-constants.ts:24` | `ChatController.kt:641` triggers `pollHealthIfNeeded(force = false)`. Also bumps the `HeartbeatWatchdog` via `onActivity`. | Watchdog is currently mis-tuned (20s timeout vs 30s tick). Being fixed alongside this audit. |
| `health` | ✅ | `gateway/health.md`; `HealthSummary` in `gateway/server-maintenance.ts` | `ChatController.kt:644` sets `_healthOk=true`, writes widget connected flag, drains the outbound message queue on the unhealthy→healthy transition. | Queue drain is the highest-value side-effect; don't regress it. |
| `chat` | ✅ | `src/gateway/events/*` (chat events); `concepts/messages.md` | `ChatController.kt:677` → `handleChatEvent`. Routes chat-delta / final / error frames into the message list. | Main streaming path. Tested, but historically fragile — check `handleChatEvent` before touching. |
| `agent` | ✅ | `concepts/agent-loop.md`; `concepts/streaming.md` | `ChatController.kt:681` → `handleAgentEvent`. Surfaces tool starts/results and thinking blocks during a run. | GW-7 fixed a runId-vs-sessionId mismatch here; regression-sensitive. |
| `node.invoke.request` | ✅ | `nodes/index.md`; `docs/references/openclaw-ws-protocol.md` §5 | `GatewaySession.kt:562` → `handleInvokeEvent` → dispatches to registered `on_invoke` handler in `NodeRuntime`. Responds via `node.invoke.result` RPC. | 15+ command handlers live under `node/*Handler.kt`. |
| `voicewake.changed` | ✅ | `platforms/mac/voicewake.md` | `NodeRuntime.kt:1058` → `gatewayEventHandler.handleVoiceWakeChangedEvent`. | Wake-word enable/disable propagation from the gateway. |
| `shutdown` | ❌ | `ShutdownEventSchema` in `schema/frames.ts:12`; emitted from `server-close.ts:152` with `{ reason, restartExpectedMs? }` | No handler. Event is dropped in `ChatController.handleGatewayEvent`. | **High priority.** Should: disarm the heartbeat watchdog (a clean close is coming, don't false-alarm), surface a top-of-screen banner with `reason`, show a spinner if `restartExpectedMs` is present, and use `restartExpectedMs` as a floor for reconnect backoff. macOS client sets `state = .degraded("gateway shutdown")` (see `ControlChannel.swift:366`). |
| `sessions.changed` | ❌ | `concepts/session.md`; `cli/sessions` | No handler. Session list only refreshes via cold `sessions.list` RPC on screen entry. | Should refresh the `SessionListActivity` model when the gateway reports new/renamed/deleted sessions. Currently the UI drifts until you re-enter the screen. |
| `session.message` | ❌ | See `gateway/protocol.md` framing; shape in `src/gateway/events/session-events.ts` | No handler. | Needs a trace-level audit first — may be redundant with `chat` for the currently-selected session, but probably carries inserts for background sessions that `chat` doesn't. |
| `session.tool` | ❌ | Tool-use surfacing; `concepts/streaming.md` | No handler. | Likely redundant with tool events already carried inside the `agent` stream. Verify by logging before wiring. |
| `presence` | ❌ | `gateway/protocol.md` §Presence | No handler. | Low priority for mobile node. Would let us surface "other devices online" in the gateway status UI. macOS uses it heavily; iOS only in tests. |
| `talk.mode` | ❌ | `platforms/mac/voice-overlay.md` | No handler. | Voice flow relevance. iOS handles it at `apps/ios/.../NodeAppModel.swift:724`. Wire when voice polish lands. |
| `heartbeat` | ❌ | `gateway/heartbeat.md` | No handler. | This is the *agent* heartbeat (scheduled agent turn), not transport. macOS posts it to `NotificationCenter` (`ControlChannel.swift:359`). May already be visible in Marmalade via `chat` events that get fired by the heartbeat agent run — verify before wiring. |
| `cron` | ❌ | `automation/cron-jobs.md`, `automation/cron-vs-heartbeat.md` | No handler. | Cron run events. Same deferrable call as `heartbeat`. |
| `node.pair.requested` | ➖ | `gateway/protocol.md` §Pairing | No handler on operator socket. | Pairing is driven by the node socket's setup path (`NodeRuntime` pairing flow). Operator socket does not need it. |
| `node.pair.resolved` | ➖ | As above | No handler. | Same reason — N/A for operator socket. |
| `device.pair.requested` | ➖ | `gateway/protocol.md` §Pairing | No handler. | N/A for mobile node — this targets operator UIs that approve/reject device pairings. |
| `device.pair.resolved` | ➖ | As above | No handler. | Same reason. |
| `exec.approval.requested` | ➖ | `gateway/protocol.md` §`Exec approvals`; `tools/exec-approvals.md` | No handler. | Shell exec approvals — an operator-console feature. Not expected from a mobile node. |
| `exec.approval.resolved` | ➖ | As above | No handler. | Same reason. |
| `plugin.approval.requested` | ➖ | Plugin install approval (new in upstream as of 2026-04) | No handler. | Operator-console feature. |
| `plugin.approval.resolved` | ➖ | As above | No handler. | Same reason. |
| `update.available` | ❌ | `GATEWAY_EVENT_UPDATE_AVAILABLE` in `src/gateway/events.ts:3` | No handler. | Low priority. Could surface as a one-line banner ("Gateway update available") in settings. |
| `seqGap` | ⚠️ | Synthetic client-side event — not in `GATEWAY_EVENTS` | `ChatController.kt:673` sets an error text + clears pending runs. | Emitted by the framing layer on detected `seq` discontinuity. Double-check the framing code actually produces this — if not, this branch is dead code. |

## Cross-reference — how the other official clients handle these

| Event | iOS | macOS |
|---|---|---|
| `talk.mode` | `apps/ios/.../NodeAppModel.swift:724` | `apps/macos/.../GatewayConnection.swift:72` (enum case) |
| `presence` | — | `apps/macos/.../InstancesStore.swift:86` |
| `heartbeat` | — | `apps/macos/.../ControlChannel.swift:359` (NotificationCenter post) |
| `shutdown` | — | `apps/macos/.../ControlChannel.swift:366` (`state = .degraded("gateway shutdown")`) |

Use these as reference implementations when wiring Marmalade's handlers.

## How to use this doc

1. Before wiring a new event handler, **re-verify the payload schema against upstream** (file paths above). Event names are stable but payload shapes evolve.
2. Log a raw payload from a real gateway before hand-coding the parser — run the Marmalade app with a DEBUG log filter on `OpenClawGateway` and provoke the event, then write the parser against what actually arrives.
3. Update this table when you land a change: bump the status column, point the "current implementation" cell at the new file:line, and re-date the "last verified" note at the top.
