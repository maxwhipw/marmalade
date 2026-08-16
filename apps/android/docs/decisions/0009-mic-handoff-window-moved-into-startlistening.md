# 0009. Mic-handoff window lives inside startListening, not onShow

Status: Accepted
Date: 2026-05-23

Supersedes the "load-bearing delays" elaboration in
[0008-mic-ownership-manager.md](0008-mic-ownership-manager.md) (Consequences
section, lines describing `OpenClawSession.onShow`'s `delay(150)` as a
mic-handoff window). The decision in ADR 0008 — `MicOwnershipManager` as the
sole mic-ownership mechanism, with a short acquisition delay between
`requestMic` and `AudioRecord` construction — still stands. Only the
location of that delay in the voice-popup path has changed.

## Context

ADR 0008 documented two equivalent acquisition-delay sites:

- `delay(150)` in `OpenClawSession.onShow`, after activating the popup and
  before kicking off STT.
- `MIC_HANDOFF_DELAY_MS = 150` in `InlineSTTStateHolder.start`, after
  `requestMic(INLINE_STT)` and before `recognizer.startStreaming`.

Both were described as "load-bearing": the gap between `requestMic` and
`AudioRecord` construction lets `HotwordService`'s `currentOwner` collector
run `engine.stop()` so the two `AudioRecord`s never coexist.

Subsequent to ADR 0008, the voice-popup path was refactored. The
`requestMic(VOICE_SESSION)` call was moved out of `onShow` and into
`startListening()` — it now sits at the top of every listening turn
(including auto-listen restarts), not just the first one after popup show.
A short `delay(50)` was added inside `startListening()` immediately after
the `requestMic` call.

That refactor moved the mic-handoff window with it. `onShow`'s `delay(150)`
now runs **before** any `requestMic`, so the `currentOwner` collector has
nothing to observe during that 150 ms. The collector sees ownership flip
when `startListening()` calls `requestMic(VOICE_SESSION)`; the
`delay(50)` that follows is the actual settle window in which
`engine.stop()` runs before the recognizer builds its `AudioRecord`.

`onShow`'s `delay(150)` did not become dead code. It still serves a
secondary purpose: letting the popup-show animation, activation chirp, and
~50 ms haptic vibration land before the recognizer captures its first STT
frames. Removing it would risk the activation sound bleeding into the
first transcription window. But it is no longer a *mic-handoff* window.

`InlineSTTStateHolder.start` was not refactored — its `requestMic` →
`delay(MIC_HANDOFF_DELAY_MS)` → `recognizer.startStreaming` sequence still
matches ADR 0008's description.

## Decision

The mic-handoff window for the voice-popup path lives **inside**
`startListening()`, between `requestMic(VOICE_SESSION)` and recognizer
construction — currently `delay(50)`, named `MIC_HANDOFF_DELAY_MS` (defined
locally in `OpenClawSession`).

`OpenClawSession.onShow`'s `delay(150)` is reclassified as a
**popup-settle delay** (named `POPUP_SETTLE_DELAY_MS`) — it lets the popup
animation and activation cues land before the recognizer engages, but is
NOT load-bearing for mic handoff. The corresponding in-code comment is
updated to match.

`InlineSTTStateHolder.start`'s `MIC_HANDOFF_DELAY_MS = 150` keeps its
ADR-0008 role and naming, since that path was not refactored.

## Consequences

- The "load-bearing" claim in ADR 0008 (Consequences) about
  `OpenClawSession.onShow`'s `delay(150)` no longer applies. The
  load-bearing window in that path is the smaller `delay(50)` inside
  `startListening()`.
- The two mic-handoff delays now have different durations (50 ms in the
  voice popup, 150 ms in inline STT). The smaller value is acceptable
  because `requestMic` runs synchronously inside `startListening()` —
  the `currentOwner` `StateFlow` emission and `HotwordService`'s
  collector are both on Main, so the gap can be tighter than the
  inline path that crosses a separate scope.
- The popup-settle delay in `onShow` is now documented as a UX timing
  buffer, not a mic-handoff window. Future refactors are free to tune or
  remove it on UX grounds rather than treating it as a fragile audio
  invariant.
- `voice.md` and ADR 0008's body still reference `delay(150)` in
  `OpenClawSession.onShow` as a mic-handoff delay. This ADR supersedes
  that specific elaboration; the in-code comment is the authoritative
  description. ADR 0008's core decision (MicOwnershipManager as sole
  mic-ownership mechanism, 90 s safety net, keep-alive heartbeat) is
  unaffected.

## Rejected alternatives

- **Drop `delay(150)` from `onShow` entirely.** Rejected: it still
  provides the activation-chirp / haptic / animation-settle margin
  described in the new comment. Removing it is a separate UX call that
  should be made with on-device evidence, not as a side effect of this
  documentation correction.
- **Move the inline-STT `MIC_HANDOFF_DELAY_MS` to match the popup's
  50 ms.** Rejected: the inline path constructs its recognizer through a
  different scope and has not been observed to need the tighter value.
  Tuning it without device evidence would risk reintroducing the
  silent-AudioRecord-collision class of bug ADR 0008 was written to
  prevent.
