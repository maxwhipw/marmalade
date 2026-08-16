package app.marmalade.android.chat.messages

import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.OutboxEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ChatMessage ↔ MessageEntity mappers. Top-level so MessageStream,
 * ChatController, and hydrateFromServer can all use the same canonical
 * encoding without duplicating the parts-list JSON shape across modules.
 */

private val PARTS_SERIALIZER = ListSerializer(JsonElement.serializer())

fun MessageEntity.toChatMessage(json: Json): ChatMessage {
    val parts: List<ChatMessagePart> = runCatching {
        json.decodeFromString(PARTS_SERIALIZER, contentJson).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = obj.stringOrNullLocal("type") ?: obj.stringOrNullLocal("kind")
            when (type) {
                "text" -> ChatMessagePart.Text(obj.stringOrNullLocal("text").orEmpty())
                "reasoning", "thinking" -> ChatMessagePart.Reasoning(obj.stringOrNullLocal("text").orEmpty())
                "image" -> ChatMessagePart.Image(
                    image = obj.stringOrNullLocal("url") ?: obj.stringOrNullLocal("image").orEmpty(),
                    alt = obj.stringOrNullLocal("alt"),
                )
                // File parts serialize below but had no decode branch, so a
                // user bubble's attachment chip silently vanished on cold
                // load. Round-trip them like every other part.
                "file" -> ChatMessagePart.File(
                    name = obj.stringOrNullLocal("name").orEmpty(),
                    source = obj.stringOrNullLocal("source").orEmpty(),
                    mimeType = obj.stringOrNullLocal("mimeType"),
                )
                "tool_call" -> ChatMessagePart.ToolCall(
                    toolCallId = obj.stringOrNullLocal("toolCallId").orEmpty(),
                    toolName = obj.stringOrNullLocal("toolName").orEmpty(),
                    // args is the structured form; cold-load only has the
                    // serialized argsText so we reconstruct empty. Live
                    // streaming repopulates args from the wire event; the
                    // UI reparses argsText via displayArgs() when needed.
                    args = JsonObject(emptyMap()),
                    argsText = obj.stringOrNullLocal("argsText").orEmpty(),
                    // result == null means "still running" to the UI, so a
                    // completed tool MUST hydrate with its result or every
                    // cold load reverts it to a perpetual running pill with
                    // no duration (maintainer, on-device 2026-07-02). Legacy rows
                    // (pre-fix) have no "result" key and hydrate as null.
                    result = obj["result"]?.takeIf { it !is JsonNull },
                    isError = (obj["isError"] as? JsonPrimitive)?.booleanOrNull() == true,
                    // Absent on legacy rows (pre-2026-07-26) and on every
                    // main-agent call — both hydrate null, which is exactly
                    // "not a subagent's work".
                    parentToolUseId = obj.stringOrNullLocal("parentToolUseId"),
                    // Absent on legacy rows and on every call nobody was asked
                    // about — both hydrate null, which is exactly "no human
                    // decision on record".
                    approvalChoice = obj.stringOrNullLocal("approvalChoice"),
                )
                else -> null
            }
        }
    }.getOrDefault(emptyList())
    return ChatMessage(
        id = id,
        role = when (role) {
            "assistant" -> ChatRole.Assistant
            "user" -> ChatRole.User
            "tool" -> ChatRole.Tool
            else -> ChatRole.System
        },
        parts = parts,
        timestamp = timestampMs,
        pending = isStreaming,
        // Messages-table rows are confirmed; outbox-flow rows carry the
        // pending/sending/failed indicator (see OutboxEntity.toChatMessage).
        sendStatus = "sent",
        error = error,
        replyToId = replyToId,
        voiceOrigin = voiceOrigin,
        rawPayloadJson = rawPayloadJson,
        seq = serverSeq,
        hasCutPoint = hasCutPoint,
        steered = steered,
        originSource = originSource,
        originDeviceId = originDeviceId,
    )
}

fun ChatMessage.toMessageEntity(sessionKey: String, json: Json, clientOrdinal: Long = 0L): MessageEntity {
    val partsJson = json.encodeToString(
        PARTS_SERIALIZER,
        parts.map { part ->
            when (part) {
                is ChatMessagePart.Text -> buildJsonObject {
                    put("type", "text")
                    put("text", part.text)
                }
                is ChatMessagePart.Reasoning -> buildJsonObject {
                    put("type", "reasoning")
                    put("text", part.text)
                }
                is ChatMessagePart.Image -> buildJsonObject {
                    put("type", "image")
                    put("image", part.image)
                    part.alt?.let { put("alt", it) }
                }
                is ChatMessagePart.File -> buildJsonObject {
                    put("type", "file")
                    put("name", part.name)
                    put("source", part.source)
                    part.mimeType?.let { put("mimeType", it) }
                }
                is ChatMessagePart.ToolCall -> buildJsonObject {
                    put("type", "tool_call")
                    put("toolCallId", part.toolCallId)
                    put("toolName", part.toolName)
                    put("argsText", part.argsText)
                    part.result?.let { put("result", it) }
                    if (part.isError) put("isError", true)
                    part.parentToolUseId?.let { put("parentToolUseId", it) }
                    part.approvalChoice?.let { put("approvalChoice", it) }
                }
            }
        },
    )
    return MessageEntity(
        id = id,
        sessionKey = sessionKey,
        role = when (role) {
            ChatRole.Assistant -> "assistant"
            ChatRole.User -> "user"
            ChatRole.Tool -> "tool"
            ChatRole.System -> "system"
        },
        contentJson = partsJson,
        timestampMs = timestamp ?: System.currentTimeMillis(),
        isStreaming = pending,
        clientOrdinal = clientOrdinal,
        serverSeq = seq,
        error = error,
        replyToId = replyToId,
        voiceOrigin = voiceOrigin,
        rawPayloadJson = rawPayloadJson,
        hasCutPoint = hasCutPoint,
        steered = steered,
        originSource = originSource,
        originDeviceId = originDeviceId,
    )
}

/**
 * Render an outbox row as a user ChatMessage. sendStatus reflects the
 * outbox row's status verbatim — the chat UI uses this to render the
 * pending/sending/failed indicator next to the bubble.
 */
fun OutboxEntity.toChatMessage(json: Json): ChatMessage {
    val parts: List<ChatMessagePart> = runCatching {
        json.decodeFromString(PARTS_SERIALIZER, contentJson).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = obj.stringOrNullLocal("type") ?: obj.stringOrNullLocal("kind")
            when (type) {
                "text" -> ChatMessagePart.Text(obj.stringOrNullLocal("text").orEmpty())
                else -> null
            }
        }
    }.getOrDefault(emptyList())
    // Translate the outbox lifecycle vocabulary (pending/sending/failed) into
    // the ChatMessage.sendStatus vocabulary the UI's SendStatusLabel knows
    // about (queued/sending/sent/failed). "pending" maps to "queued" — the
    // user-visible "Waiting…" state, covering both the offline-no-serverSid
    // case and the backoff-between-attempts case. Without this translation
    // the most common outbox state renders empty in the UI (Reviewer
    // Checkpoint 2 finding UX-#1).
    val uiStatus = when (status) {
        "pending" -> "queued"
        else -> status
    }
    return ChatMessage(
        id = id,
        role = ChatRole.User,
        parts = parts,
        timestamp = createdAtMs,
        pending = false,
        sendStatus = uiStatus,
        voiceOrigin = voiceOrigin,
    )
}

private fun JsonObject.stringOrNullLocal(key: String): String? {
    val value = this[key] ?: return null
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content
}

private fun JsonPrimitive.booleanOrNull(): Boolean? =
    runCatching { content.toBooleanStrictOrNull() }.getOrNull()
