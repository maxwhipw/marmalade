package app.marmalade.android.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Client-paced text reveal for the streaming assistant bubble (design proposal
 * TOPIC 1 option (a) + (c) — UI design proposals, kept internally).
 *
 * The gateway batches `message.delta` into sentence-ish chunks and
 * [app.marmalade.android.chat.messages.MessageStream] flushes the whole
 * accumulated string into Room / its `StateFlow` on a 33 ms debounce, so text
 * POPs in sentence-sized lumps — jarring next to ChatGPT/Claude.ai-style paced
 * reveal. This adds a *presentation-only* delay on top of already-arrived,
 * already-persisted text: a coroutine ticks every [REVEAL_TICK_MS] and walks a
 * reveal cursor forward toward the full length, closing any backlog
 * geometrically (see [nextRevealCount]) so it never lags the server by more
 * than ~[CATCH_UP_TICKS] ticks regardless of chunk size.
 *
 * CRITICAL (see the design doc's recomposition warning): this state is LOCAL to
 * the streaming bubble's composable — it must NOT be pushed back through
 * `MessageStream`'s `StateFlow` or Room, or it reintroduces the per-token
 * recomposition + markdown re-parse cost the 33 ms batching exists to avoid.
 * `MessageStream` keeps flushing the full text at 33 ms unchanged; the reveal
 * only chooses how much of that already-flushed text this one composable paints
 * per frame.
 */

/** Reveal ticker period. ~50 Hz — smooth without thrashing recomposition. */
internal const val REVEAL_TICK_MS = 20L

/**
 * Backlog divisor. A backlog of N chars advances by ~N/8 per tick, so any lump
 * (even a large paste) closes to zero in ~8 ticks (~160 ms) — fast enough to
 * never feel like it lags the server, slow enough to read as motion.
 */
// Public, not internal: reached from `:app`'s StreamingTextRevealTest, which
// is a separate module from :shared.
const val CATCH_UP_TICKS = 8

/**
 * The reveal state a streaming bubble reads: how many chars of the full text to
 * paint, and whether text is still being revealed (drives the caret liveness).
 */
data class StreamingReveal(val revealedChars: Int, val revealing: Boolean)

/**
 * Pure catch-up step. Given the current [revealed] cursor and the [target] full
 * length, return the next cursor value: advance by `max(1, backlog /
 * catchUpTicks)`, clamped to [target]. Deliberately side-effect-free so the
 * pacing math is unit-testable in isolation (see StreamingTextRevealTest).
 */
// Public, not internal: reached from `:app`'s StreamingTextRevealTest.
fun nextRevealCount(revealed: Int, target: Int, catchUpTicks: Int = CATCH_UP_TICKS): Int {
    if (revealed >= target) return target
    val backlog = target - revealed
    val step = maxOf(1, backlog / catchUpTicks)
    return (revealed + step).coerceAtMost(target)
}

/**
 * Clamp a reveal cursor so a frame never ends between a Unicode surrogate pair,
 * which would render a broken glyph. If [count] would end on a high surrogate,
 * back off by one. Also clamps into `0..text.length`.
 */
// Public, not internal: reached from `:app`'s StreamingTextRevealTest.
fun safeRevealLength(text: String, count: Int): Int {
    val n = count.coerceIn(0, text.length)
    if (n in 1 until text.length && text[n - 1].isHighSurrogate()) return n - 1
    return n
}

/**
 * Drive a reveal cursor over [fullText] for one streaming bubble.
 *
 * - History / finalized messages ([isPending] == false at first composition):
 *   the cursor starts at the full length and never animates — zero behaviour
 *   change for restored transcript rows.
 * - Pending messages: the cursor starts at 0 and the ticker walks it forward,
 *   surviving text growth because the effect is keyed on [revealKey] (the
 *   bubble's stable `message.id`), not on [fullText]; it reads the latest text
 *   via [rememberUpdatedState] so growth doesn't restart the animation.
 * - On finalize ([isPending] flips to false) the effect re-launches, snaps the
 *   cursor to full immediately, and stops ticking (no idle battery drain).
 *
 * @param revealKey stable identity for this bubble (its `message.id`). Reveal
 *   progress is remembered against it — matching the LazyColumn item key — so it
 *   persists for the streaming lifetime and resets only for a genuinely new
 *   message, never touching `rows`/keys (see `.claude/rules/chat-ui.md`).
 */
@Composable
fun rememberStreamingReveal(
    fullText: String,
    isPending: Boolean,
    revealKey: Any,
): StreamingReveal {
    val latestText by rememberUpdatedState(fullText)
    var revealed by remember(revealKey) {
        mutableIntStateOf(if (isPending) 0 else fullText.length)
    }

    LaunchedEffect(revealKey, isPending) {
        if (!isPending) {
            // History or just-finalized: paint everything, don't tick.
            revealed = latestText.length
            return@LaunchedEffect
        }
        while (isActive) {
            val target = latestText.length
            if (revealed < target) {
                revealed = nextRevealCount(revealed, target)
            }
            delay(REVEAL_TICK_MS)
        }
    }

    val shown = safeRevealLength(fullText, revealed)
    return StreamingReveal(
        revealedChars = shown,
        revealing = isPending && shown < fullText.length,
    )
}
