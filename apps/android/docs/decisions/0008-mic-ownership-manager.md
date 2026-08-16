# 0008. Microphone handoff via MicOwnershipManager, not broadcasts

Status: Accepted
Date: 2026-05-20

## Context

Three subsystems compete for the single physical microphone:

1. **KWS** — `HotwordService` runs the openWakeWord `WakeWordEngine`,
   which owns an `AudioRecord` continuously while voice wake is enabled.
2. **Voice popup** — `OpenClawSession` drives STT through the active
   recognizer when the wake word fires or the user long-presses the mic.
3. **Inline chat STT** — `InlineSTTStateHolder` drives STT through the
   same recognizer when the user taps the chat input mic button.

Android's `AudioRecord` is single-owner: two live instances on one device
typically means one pipeline fails to initialize or gets silent/garbage
audio. So KWS must release its `AudioRecord` before any STT consumer
creates one, and at most one STT consumer may run at a time.

The fork inherited a broadcast-based handoff: STT consumers (and even the
recognizers themselves) sent `ACTION_PAUSE_HOTWORD` /
`ACTION_RESUME_HOTWORD`, and `HotwordService` stopped/started its engine
on receipt. This was fragile:

- Fire-and-forget. A dropped or never-sent `RESUME` (a crash between
  pause and resume, an exception path that skipped the resume, a
  recognizer that paused but whose caller never resumed) left the wake
  word **silently dead** until the next process restart. No timeout, no
  recovery.
- Ownership was diffuse. The recognizers, the two STT callers, and a
  notification-only receiver all broadcast or listened, with no single
  place that knew who held the mic.
- A genuine STT-vs-STT collision (voice popup opened while inline STT
  ran) had no defined behaviour.

`MicOwnershipManager` already existed in the tree, fully written but never
wired in.

## Decision

**Wire `MicOwnershipManager` as the sole mic-ownership mechanism and
delete the hotword pause/resume broadcasts entirely.**

`MicOwnershipManager` is a process-wide singleton coordination *token* —
not an audio pipe. Each consumer still constructs and owns its own
`AudioRecord`; the manager only guarantees at most one non-KWS owner at a
time and that KWS yields before an STT consumer acquires.

- `MicOwner { NONE, KWS, VOICE_SESSION, INLINE_STT }`. `requestMic` grants
  if the mic is free or held by KWS (KWS is always preemptable) or already
  held by the requester (idempotent); it denies a different non-KWS owner.
- The **caller** holds the token, not the recognizer. `WhisperRecognizer`
  and `NemotronRecognizer` are shared by both STT entry points and cannot
  tell which they serve, so they are ownership-agnostic. Their existing
  `onFlowClosed` callback — invoked in the flow's `finally` block, after
  `AudioRecord.release()` — is the precise release signal the caller wires
  to `releaseMic`.
- `HotwordService` collects `currentOwner`: the instant a non-KWS owner
  appears it calls `engine.stop()` (the *stop* edge). It registers
  `setOnMicReleasedToKws` to restart KWS when the mic returns to `NONE`
  (the *restart* edge). The two edges are split deliberately: the restart
  callback carries a 50ms settle delay; restarting from the collector too
  would double-start the engine.
- A **90-second safety net** force-releases whatever non-KWS owner holds
  the mic and restarts KWS. This is the durable guarantee the broadcast
  scheme lacked — a stuck or leaked STT session can no longer kill the
  wake word permanently.
- Patient-mode voice sessions can legitimately run minutes. A 60-second
  keep-alive heartbeat, scoped strictly inside the STT job, re-requests
  the mic (the idempotent branch refreshes the net). A healthy long
  session survives; a stuck session's heartbeat dies with its job, so the
  net still fires ~90s after the stall.

## Consequences

- Single-owner is enforced in-process by one component; no IPC, no
  fire-and-forget broadcasts.
- A dropped release can no longer permanently kill the wake word — the
  90s net always reclaims the mic.
- Recognizers are simpler and reusable: pure audio pipelines with no
  knowledge of mic ownership.
- The voice popup surfaces a hard error (`error_mic_busy`) and inline STT
  surfaces a Toast when they collide — no silent failure.
- The foreground-service notification's "Voice Wake: Listening / Paused"
  suffix is now correct. It is driven by `NodeRuntime._voiceWakeIsListening`,
  which was previously dead (never set true); it is now sourced from
  `currentOwner == KWS`. The in-app status label (`chatMicActive` →
  `_voiceWakeStatusText`) is likewise re-sourced from `currentOwner`.
- A short acquisition delay (`delay(150)` in `OpenClawSession.onShow`,
  `MIC_HANDOFF_DELAY_MS` in `InlineSTTStateHolder.start`) gives the
  `currentOwner` collector time to run `engine.stop()` before the
  recognizer builds its `AudioRecord`. These delays are load-bearing.
- `.claude/rules/voice.md`'s wake-word section still describes the
  broadcast handoff and must be updated to point at this ADR.

## Rejected alternatives

- **Keep the broadcasts.** The dropped-RESUME failure mode is exactly the
  bug being fixed; there is no timeout or recovery to bolt on without
  reinventing the manager.
- **Caller-configurable / disabled safety-net timeout for patient mode.**
  Rejected: it widens the manager's API surface and weakens the net for
  the longest-running, most leak-prone sessions. The keep-alive heartbeat
  achieves long-session survival without disarming the net.
- **A recognizer-owned token.** The recognizers are singletons shared
  across entry points and cannot tell `VOICE_SESSION` from `INLINE_STT`;
  ownership must live with the caller.
