# 0006. Hand-rolled markdown parser for streaming text; library renderer for final

Status: Accepted
Date: 2026-04-24 (recording phase 02 decision)

## Context

Assistant replies arrive character-by-character over the WebSocket
stream. Standard markdown libraries (mikepenz, halilibo/compose-richtext,
etc.) expect well-formed markdown — they throw or render incorrectly on
incomplete constructs like:

- `**bold` (closing `**` not yet streamed)
- ` ``` ` (code fence opened, no close)
- `[link](http` (URL incomplete)
- `# Heading` immediately before the next line arrives

If the streaming bubble re-renders the partial text through a strict
markdown library on every token, the user sees flicker, broken
formatting, or crashes mid-stream.

## Decision

Split markdown rendering into two layers:

1. **Streaming layer**: a hand-rolled parser in `ChatMarkdown.kt` that
   is **resilient to unclosed constructs** — it renders what it has,
   tolerates partial markup, and never throws. Used for the live
   streaming bubble.
2. **Final layer**: the mikepenz markdown renderer, used **only after
   the stream is complete** and the bubble transitions from
   "streaming" to "final" state.

The handoff happens at stream finalization — the streaming text is
discarded and the complete text is re-rendered through the library.

## Consequences

- Two code paths to maintain instead of one
- The streaming parser is intentionally **lossy** for advanced markdown
  (tables, footnotes) — those render correctly only in the final pass
- No flicker or broken formatting during streaming
- LazyColumn keys must be **namespaced** (`msg-{id}`, `sep-{date}`,
  `block-{blockId}`) to avoid collisions when the streaming bubble
  transitions to its final form (key collision causes scroll jumps)
- Code-block syntax highlighting only works in the final renderer —
  acceptable since highlighting partial code is meaningless anyway
- Auto-scroll behavior must check whether the user is at the bottom
  before scrolling on new tokens, otherwise scrolling-up-to-read is
  destroyed mid-stream

## Rejected alternatives

- **Use the markdown library for streaming too.** Tested; produces
  visible errors and occasional crashes on partial markup. Rejected.
- **Plain text streaming, render markdown only at end.** Disorienting
  UX — user sees raw `**bold**` symbols flicker into formatted text at
  the end. The hand-rolled parser handles 90%+ of cases correctly during
  streaming.
- **Buffer the stream until complete, then render once.** Defeats the
  point of streaming — users want to see tokens as they arrive.
