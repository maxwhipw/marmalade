# Sessions and Chat

## Session Model
- Sessions are managed by the gateway (not locally created)
- Fetch session list via `sessions.list` RPC
- Each session has a key, display name, agent config, and message history
- The gateway provides a `mainSessionKey` on connect — this is the default session

### Session Data Class
```kotlin
data class Session(
    val key: String,            // gateway-assigned session key
    val displayName: String,
    val agentName: String?,
    val avatarUri: String?,     // agent avatar for this session
    val lastMessagePreview: String?,
    val lastMessageTimestamp: Instant?,
    val unreadCount: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
)
```

## Chat UI
- **Session list screen:** Telegram/Signal-like list with agent avatar, session name, last message preview, timestamp, unread count
- **Message view:** scrollable message list, agent avatar beside assistant messages
- User messages right-aligned, assistant messages left-aligned with avatar
- Markdown rendering in message bubbles (port `ChatMarkdown.kt` from official app)
- Streaming: assistant tokens appear in real-time as they arrive via gateway events

### Message Rendering
- Text messages: standard styled text with markdown support
- Code blocks: syntax-highlighted, horizontally scrollable
- Long messages: render fully (no "show more" truncation during streaming)
- Token usage: small label below each assistant message showing input/output token count

### Streaming Implementation
- On `chat.send`, the gateway streams back events with text chunks
- Collect chunks in ViewModel, emit as growing `StateFlow<String>`
- Compose recomposes the message bubble as text grows
- On stream complete: persist final message to Room

## Sending Messages
- Text input: `TextField` with send button, supports multiline
- Voice input: transcribed text from conversation mode feeds the same `chat.send` path
- Optimistic UI: user message appears immediately in the list, assistant response streams in below it

## Context Strategy
- Enable token usage tracking per session: `sessions.patch` with `responseUsage: "tokens"` immediately after connecting
- Each streamed response includes `responseUsage: { inputTokens, outputTokens }` in its completion event
- Accumulate per-session totals in Room
- Display per-response counts in message view, per-session totals in session list or detail

## Local Persistence

### Room Schema
```
GatewayEntity(id, name, url, deviceToken, isEmbedded, lastConnected)
SessionEntity(key, gatewayId, displayName, agentName, avatarUri, lastUpdated)
MessageEntity(id, sessionKey, role, content, timestamp, inputTokens, outputTokens)
```

- Cache sessions and messages for offline viewing
- On reconnect: sync with gateway, update local cache
- Use `REPLACE` conflict strategy for session updates
- Messages are append-only (gateway is source of truth for edits/deletes)
