---
paths:
  - "app/src/main/java/app/marmalade/android/chat/protocol/**/*.kt"
  - "app/src/main/java/app/marmalade/android/chat/ChatController.kt"
---

# Gateway event handling rules

There is no typed sealed-class protocol layer. `chat/protocol/` does
not exist in this repo — it was deleted in the 2026-06-25 gut and
never rebuilt. This file's `paths:` frontmatter still lists that
directory so the rule reactivates automatically if/when a typed layer
is (re)built; until then it only fires for `ChatController.kt` (and the
collaborators extracted from it live beside it in `chat/`).

## Actual current architecture

- **`rpc/GatewayEvent.kt`** is the wire shape for every server-pushed
  event: `{ type: String, payload: JsonElement?, sessionId: String? }`.
  `payload` is deliberately kept as a raw `JsonElement` — there is no
  central typed schema or discriminated sealed hierarchy to decode
  into.
- **Decoding happens ad hoc, per handler, at the point of use.** Each
  call site casts `event.payload as? JsonObject` and pulls fields with
  helpers like `stringOrNull(...)`, tolerating missing/wrong-shaped
  values instead of failing a strict schema.
  - `chat/ChatEventRouter.kt::handle()` — a `when (event.type)`
    dispatch for controller-level concerns (extracted from
    ChatController in the 2026-07-17 decomposition; ChatController
    wires it in init and owns the state flows it mutates)
    (`gateway.ready`, `message.start`/`complete`, `status.update`,
    `error`, `clarify.request`/`approval.request`/etc.,
    `terminal.read.request`, `session.info`, `background.complete`).
    Its `else` branch explicitly defers everything else to
    `MessageStream`.
  - `chat/messages/MessageStream.kt::handle()` (~line 333) — a second
    `when (event.type)` dispatch for the message/tool/reasoning stream
    itself (`message.delta`, `reasoning.delta`,
    `tool.start`/`progress`/`complete`, etc.).
- **Warn-and-drop convention.** Neither dispatcher throws on an
  unrecognized event type or a malformed payload. Unknown `event.type`
  values fall through to a no-op `else` (`ChatController`) or a
  `Log.d(TAG, "unhandled event: ...")` line (`MessageStream`).
  Malformed or missing fields inside a recognized event are handled
  with `Log.w(TAG, ...)` plus an early `return` — see
  `MessageStream.resolveSessionId()` (drops unscoped `subagent.*`
  events and unscoped events when there's no active session) and
  `ChatController`'s `background.complete` handler (missing
  `task_id` or an unresolvable session both log-and-skip). Nothing
  here crashes the WS reader on an unexpected shape.

## Roadmap

A typed ingestion layer (sealed classes / discriminated schema
decoding, replacing the raw-`JsonElement` + per-handler-cast pattern
above) is a known roadmap item — it does not exist today. If it lands,
this file's `paths:` scope already covers `chat/protocol/`.
