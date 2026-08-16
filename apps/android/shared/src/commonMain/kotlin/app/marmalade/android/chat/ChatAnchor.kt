package app.marmalade.android.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the transcript AT this message" — the intent a search result (or any
 * future jump affordance) hands to the chat screen.
 *
 * Why an intent and not a scroll call: the target row usually does not exist
 * yet when the jump is requested. A session the maintainer has never opened on this
 * device has an empty Room table; hydration (`session.resume` →
 * `session.subscribe(since_seq)`) replays the missing events over the socket
 * and the rows land asynchronously, one batch at a time. So the anchor is a
 * RETAINED request that resolves whenever the row shows up — not a one-shot
 * scroll that fires into an empty list and is lost.
 *
 * @param sessionKey the session this anchor belongs to. The chat screen acts
 *   only when it matches the session it is currently bound to, so an anchor
 *   minted for another session can never yank this transcript.
 * @param seq the daemon-minted `seq` of the target message. Authoritative
 *   ordering key: unlike a wall-clock timestamp it is comparable across
 *   devices, so "the first message at or after seq" is well defined even when
 *   the exact row was compacted away.
 * @param messageId the daemon `message_id` when the caller knows it (search
 *   results do). Preferred over [seq] because it is the Room primary key —
 *   an exact hit, no nearest-neighbour guess.
 * @param query the search term that produced this jump. Unused by the
 *   anchoring machinery; carried so the match navigator (slice 2) can restore
 *   its query without a second round trip.
 */
data class ChatAnchor(
    val sessionKey: String,
    val seq: Long,
    val messageId: String? = null,
    val query: String? = null,
)

/**
 * One-shot anchor request slot.
 *
 * Semantics deliberately kept tiny and testable (no Room, no Android):
 *  - [request] replaces whatever was pending — the newest jump wins.
 *  - [consume] clears ONLY the anchor that was actually applied, so a jump
 *    requested while an older one was being resolved is not silently dropped.
 *  - Re-requesting the same anchor after it was consumed re-fires: the flow
 *    went through null in between, so the collector sees a fresh value and
 *    jumps again (tapping the same search result twice must work).
 */
class ChatAnchorRequests {
    private val _anchor = MutableStateFlow<ChatAnchor?>(null)
    val anchor: StateFlow<ChatAnchor?> = _anchor.asStateFlow()

    fun request(anchor: ChatAnchor) {
        _anchor.value = anchor
    }

    fun consume(anchor: ChatAnchor) {
        _anchor.compareAndSet(anchor, null)
    }

    /** Drop a pending anchor without applying it (e.g. the user left). */
    fun clear() {
        _anchor.value = null
    }
}
