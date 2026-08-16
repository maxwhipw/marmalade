---
paths:
  - "app/src/main/java/app/marmalade/android/chat/**"
  - "shared/src/commonMain/kotlin/app/marmalade/android/rpc/**"
  - "shared/src/jvmSharedMain/kotlin/app/marmalade/android/rpc/**"
---

# Identity invariants (marmaladed stable ids)

This client speaks to **marmaladed** (the marmalade orchestrator daemon,
protocol v1). The daemon's identity plan (P1–P4) is the contract; the old
fork gateway's LIVE/STORED id split is DEAD — do not reintroduce it.

## The rules (locked)

1. **IDs are names, not state.** The daemon mints `session_id` (at
   session.create) and `message_id` (per message) ONCE; they never change.
   Never synthesize an id when the server minted one. Local synth ids
   ("outbox-…", "assistant-…" fallback) exist only until the server ack /
   event binds the real id.
2. **seq orders; timestamps are metadata.** Every stamped event carries a
   per-session monotonic `seq`. Ordering, dedup (watermark), the replay
   cursor (`session.subscribe(since_seq = MAX(messages.serverSeq))`), and
   unread (`last_seq > seen_seq`) are all seq arithmetic. Never order or
   badge by wall clock.
3. **Finalized rows store the COMPLETE event's seq** (not the start's) so
   the replay cursor lands after the whole turn. A mid-stream partial keeps
   its start seq and is rebuilt by the replayed `message.start`
   (MessageStream deletes the stale row for the same id).
4. **Session key promotion (K1).** A client-coined "chat-…" local key is
   renamed to the daemon's `session_id` on first session.create. After
   that, `sessions.key == gatewaySessionId == the wire session_id` —
   one id, everywhere. `session.resume` returns the SAME id.
5. **History = replay, not a snapshot.** There is no session.history, no
   inflight snapshot, no content-based reconcile. Attach = resume +
   subscribe; replayed events flow through the SAME MessageStream path as
   live ones.
6. **Origin is connection-bound.** The hello handshake declares
   deviceId/platform/tzOffset; the daemon stamps message origins from the
   authenticated connection, never the message body. Don't put identity
   claims in payloads.

Regression tests: `MessageStreamStableIdsTest`, `SubscribeAndSeenTest`,
`UnreadUtilsTest`.
