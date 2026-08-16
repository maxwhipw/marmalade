package app.marmalade.android.utils

/**
 * Cross-client unread derivation (marmaladed identity plan P4).
 *
 * The daemon stamps a monotonic per-(device, session) read cursor
 * (`session.seen`, auto-stamped on prompt.submit — submitting IS seeing) and
 * reports both the session's highest message seq (`last_seq`) and this
 * device's cursor (`seen_seq`) on every `session.list` row. Unread is pure
 * arithmetic: there are messages this device hasn't rendered iff
 * `lastSeq > seenSeq`.
 *
 * No wall clocks and no epsilons — the old `last_active > seen_at + 10s`
 * heuristic existed only because the fork gateway had no message identity.
 * seq orders; timestamps are metadata.
 */
object UnreadUtils {

    fun isUnread(lastSeq: Long, seenSeq: Long): Boolean = lastSeq > seenSeq
}
