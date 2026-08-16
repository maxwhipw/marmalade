---
paths:
  - "app/src/main/java/app/marmalade/android/ui/chat/**/*.kt"
---

# Chat UI rules

Subsystem-specific gotchas when editing chat rendering, the streaming
bubble, or the message list. Background: ADR 0006 (streaming markdown
split).

## Markdown rendering

- **One renderer for both streaming and final text: `ChatMarkdownContent`**
  (compose-richtext on commonmark-java). `AssistantTextPart` splits text on
  ` ```marmalade ` fences (`splitAssistantText`) and routes each markdown
  segment through it. commonmark-java tolerates unclosed constructs
  mid-stream — an unmatched `**` renders as literal asterisks, not a flash —
  which is why the live path can re-parse the growing text on every 33ms
  flush without the bubble flickering. Don't swap in a stricter parser;
  that reintroduces the flash ADR 0006 was written about. (Historical: a
  hand-rolled tolerant parser `ChatMarkdown.kt` used to own the streaming
  path; it was superseded once commonmark-java handled partial input and
  deleted 2026-07-02 as dead code, along with the `ThinkingBlock` shim —
  reasoning now renders via `ReasoningPart`.)
- **Streaming reveal is presentation-only and LOCAL.**
  `StreamingTextReveal.kt` paces how much of the already-flushed text the
  bubble paints (a ~50Hz reveal cursor feeding `ChatMarkdownContent` a
  truncated prefix). It is local Compose state inside the bubble subtree and
  must NEVER be pushed back through `MessageStream`'s StateFlow/Room, or it
  reintroduces the per-token recompose + markdown re-parse the 33ms batching
  exists to eliminate. Keyed on `message.id` (= the LazyColumn item key);
  history/finalized messages snap to full instantly.
- **Body typography is propagated via `ProvideTextStyle`** in
  `ChatMarkdownContent`. compose-richtext reads M3's `LocalTextStyle`
  for body paragraphs; without the wrapper it falls through to a
  different default and the bubble reflows. Keep it matching
  `MaterialTheme.typography.bodyMedium`.

## LazyColumn keys & scroll anchoring

- **Namespace all keys**: `bubble:{messageId}:{segIdx}` (segment index
  WITHIN its own message, not a global row index — see B6),
  `tool:{toolCallId}`, `activity-indicator:{id}`, `tail-spacer`.
  Without namespacing, the streaming bubble's key can collide with the
  final message's key as it transitions, causing scroll jumps.
- The list is a **reverse-layout LazyColumn** (`reverseLayout = true`,
  item 0 = the tail spacer at the visual bottom, rows emitted
  newest-first). This is load-bearing: the scroll anchor is measured
  from the BOTTOM edge, so keyboard open/close (viewport resize under
  `imePadding`), tab-switch state restoration, and streaming-bubble
  growth all keep the list pinned to the bottom structurally. Do NOT
  convert back to a forward layout with corrective scroll effects —
  that was the 2026-07-02 lost-bottom-anchor bug. Regression tests:
  `ChatMessageListScrollTest`.
- Auto-stick fires only for newly INSERTED items and only when the
  user is near the bottom (`firstVisibleItemIndex <= 1`) — scrolling
  up to read must never be yanked mid-stream. Streaming TEXT growth
  needs no scroll handling at all in reverse layout.

## Marmalade interactive blocks

- Render blocks as **standalone `ChatItem` variants**, not nested inside
  message bubbles. The `ChatItem.MarmaladeBlock` sealed-class variant
  exists for this. Future multi-step flows depend on the standalone
  pattern.

## Slash command popup

- **Don't use Material `DropdownMenu`** for the slash command popup —
  it anchors to the composable and can't position above the input bar.
  Use `Popup` with calculated offset.

## Image memory

- Base64 images are decoded with `inSampleSize`; URL images go through
  Coil. Watch for OOM on devices with many images in chat — chats with
  20+ inline images can pressure memory on lower-end Pixels.
