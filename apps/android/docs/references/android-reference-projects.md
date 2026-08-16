# Android Reference Projects (Non-Copyleft)

These are open-source Android projects with permissive licenses (MIT or Apache 2.0) that we can study for patterns and, where useful, borrow code from with attribution. They cover real-time communication, WebSocket handling, and chat UI — the same problem space Marmalade operates in.

**Do not add projects with GPL/LGPL/AGPL licenses.** Read-only pattern study of copyleft projects is fine (see CLAUDE.md) but no code can be borrowed.

---

## WebSocket / Real-Time Connection

### Amethyst (Nostr Client)
- **Repo**: `vitorpamplona/amethyst`
- **License**: MIT
- **Relevant for**: WebSocket relay management, reconnection with exponential backoff (1s base, 5min cap), ConnectivityManager integration, OkHttp ping interval (120s), per-network-type timeouts (WiFi 10s vs mobile 30s), proxy-aware timeout tripling
- **Key files**:
  - `quartz/.../BasicRelayClient.kt` — relay connection lifecycle
  - `commons/.../EventDeduplicator.kt` — in-memory dedup by cryptographic event ID
  - `service/okhttp/OkHttpClientFactoryForRelays.kt` — network-aware OkHttp configuration
  - `service/relayClient/RelayProxyClientConnector.kt` — reconnection with backoff
- **Pattern quality**: Mature Kotlin, coroutine-based, lifecycle-aware via `SharingStarted.WhileSubscribed(30s)`. Their `EventDeduplicator` using a thread-safe set of event IDs is cleaner than text-based matching.

### Mattermost Mobile
- **Repo**: `mattermost/mattermost-mobile`
- **License**: Apache 2.0 (mobile app)
- **Relevant for**: Application-level ping/pong (30s interval), sequence-number-based message dedup, exponential backoff with staggered multi-server reconnection, background socket management (15s grace period then disconnect)
- **Key files**:
  - `app/client/websocket/index.ts` — WebSocket client with ping/pong heartbeat
  - `app/managers/websocket_manager.ts` — reconnection strategy and lifecycle
- **Note**: React Native / TypeScript, not Kotlin. Patterns transfer but code doesn't port directly.

### Element Android (Matrix SDK)
- **Repo**: `element-hq/element-android`
- **License**: Apache 2.0
- **Relevant for**: Android lifecycle management (Doze, process death, backgrounding), WakeLock handling, `START_REDELIVER_INTENT` for surviving process death, AlarmManager-based background sync rescheduling, `NetworkConnectivityChecker` for reactive reconnection
- **Key files**:
  - `internal/session/sync/job/SyncThread.kt` — sync loop with retry
  - `api/session/sync/job/SyncAndroidService.kt` — foreground service with process death recovery
  - `internal/session/sync/SyncTask.kt` — sync token management
- **Note**: Uses long-polling HTTP sync, not persistent WebSocket. The Android lifecycle patterns are the valuable part, not the transport.

---

## Chat UI

### Mattermost Mobile
- **Relevant for**: Message threading, reply-to UI, unread markers, channel/session switching, typing indicators, message status (sending/sent/failed)

### Amethyst
- **Relevant for**: Rich content rendering (markdown, media, links), message dedup in UI layer, relay status indicators

---

## AI Assistant Features

### Kai 9000
- **Repo**: `SimonSchubert/Kai`
- **License**: Apache 2.0
- **Relevant for**: The assistant-feature layer planned for the marmalade-agent branch (port plan Phase 3, kept internally). Kotlin Multiplatform + Compose, so code ports with modest effort.
- **Worth studying**:
  - **Interactive UI generation** — the AI emits full interactive screens (quizzes, dashboards, recipes) instead of text-only replies; closest existing implementation of the "interactive chat" goal
  - **Memory fact promotion** — frequently-used remembered facts get promoted into the system prompt automatically
  - **Multi-provider failover** — 24+ LLM providers with automatic fallback (less relevant while the gateway owns the model, but useful if a direct-API mode ever lands)
  - **Autonomous heartbeat** — 30-minute background self-check surfacing pending tasks (overlaps with marmalade-agent cron; study the *client-side rendering* of proactive output)
  - **MCP client support** on-device
- **Note**: Verified Apache-2.0 on 2026-06-11. Borrowing requires per-file attribution + CREDITS.md entry per CLAUDE.md.

---

## Patterns We've Already Adopted

| Pattern | Source | Status |
|---|---|---|
| ConnectivityManager reactive reconnect | Amethyst, Element | Adopted (NodeRuntime `NetworkCallback`) |
| Interruptible exponential backoff (2min cap) | Amethyst (5min cap), Mattermost (5min cap) | Adopted (GatewaySession `retrySignal`) |
| App-level heartbeat watchdog | Mattermost (ping/pong), custom | Adopted (HeartbeatWatchdog, all-frame liveness) |
| Foreground Service for always-on connection | Element (SyncAndroidService) | Adopted (NodeForegroundService) |

## Patterns Worth Adopting Next

| Pattern | Source | Notes |
|---|---|---|
| ID-based message dedup | Amethyst (event hash), Mattermost (sequence numbers) | Currently using text-based dedup; switch to idempotencyKey |
| `START_REDELIVER_INTENT` | Element | Survives process death; add to NodeForegroundService |
| Per-network-type timeouts | Amethyst | WiFi vs mobile connection quality differs significantly |
| Background grace period | Mattermost (15s), Amethyst (30s `WhileSubscribed`) | Consider for battery optimization if needed |
