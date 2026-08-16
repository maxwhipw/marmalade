package app.marmalade.android.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.espresso.AppNotIdleException
import androidx.test.espresso.IdlingPolicies
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.ui.theme.MarmaladeTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Digital-twin repro for the lost-bottom-anchor scroll bugs (maintainer, on-device
 * 2026-07-02): the chat list dropped its bottom anchor and jumped up on
 * (a) tab-switch away-and-back and (b) closing the keyboard while staying
 * in the chat.
 *
 * Twin mapping:
 *  - Keyboard open/close ⇒ the list's viewport height shrinking/growing
 *    (that is all `Modifier.imePadding()` does to the list).
 *  - Tab switch ⇒ saveable-state save/restore of the whole screen
 *    ([StateRestorationTester] — NavHost tabs restore the destination the
 *    same way).
 *
 * Harness setup mirrors ActivityBubbleTest (bare Application, sdk 34).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    qualifiers = "w360dp-h640dp",
    sdk = [34],
    application = Application::class,
)
class ChatMessageListScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private fun message(id: Int, text: String) = ChatMessage(
        id = "m-$id",
        role = if (id % 2 == 0) ChatRole.Assistant else ChatRole.User,
        parts = listOf(ChatMessagePart.Text(text)),
        timestamp = id * 1_000L,
    )

    /** Long enough that ~30 of them far exceed any viewport used here. */
    private fun longBody(i: Int) =
        "Message $i body line one with enough words to wrap over several " +
            "lines on a 360dp wide viewport so the list is much taller than the screen."

    private fun messages(count: Int = 30): List<ChatMessage> =
        (1 until count).map { message(it, longBody(it)) } + message(count, "end-marker")

    @Composable
    private fun Host(
        msgs: List<ChatMessage>,
        height: Dp,
        listState: LazyListState = rememberLazyListState(),
    ) {
        MarmaladeTheme {
            Box(Modifier.width(360.dp).height(height)) {
                ChatMessageList(
                    messages = msgs,
                    onBlockResponse = {},
                    onImageTap = { _, _ -> },
                    onBubbleAction = { _, _ -> },
                    listState = listState,
                )
            }
        }
    }

    @Test
    fun `opens at the bottom`() {
        rule.setContent { Host(messages(), height = 640.dp) }
        rule.waitForIdle()
        rule.onNodeWithText("end-marker").assertIsDisplayed()
    }

    @Test
    fun `keyboard open then close keeps the bottom anchored`() {
        var height by mutableStateOf(640.dp)
        rule.setContent { Host(messages(), height = height) }
        rule.waitForIdle()
        rule.onNodeWithText("end-marker").assertIsDisplayed()

        // IME opens: the list viewport shrinks.
        height = 300.dp
        rule.waitForIdle()
        rule.onNodeWithText("end-marker").assertIsDisplayed()

        // IME closes: the viewport grows back. Pre-fix the anchor was kept
        // relative to the TOP item, so the view ended up scrolled away from
        // the bottom — the on-device "jumps up on keyboard close".
        height = 640.dp
        rule.waitForIdle()
        rule.onNodeWithText("end-marker").assertIsDisplayed()
    }

    @Test
    fun `tab switch (state restoration) keeps the bottom anchored`() {
        val restorationTester = StateRestorationTester(rule)
        restorationTester.setContent { Host(messages(), height = 640.dp) }
        rule.waitForIdle()
        rule.onNodeWithText("end-marker").assertIsDisplayed()

        // Tab away + back: the destination composition is disposed and
        // recreated from saveable state (NavHost saveState/restoreState).
        restorationTester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithText("end-marker").assertIsDisplayed()
    }

    // ── the docked-card resize loop (maintainer, on-device 2026-08-01) ────────────
    // "The agent has a question" card was freaking out and glitching, like a
    // resizing loop. It was one: the bottom bar swapped between a tall clarify
    // card and a short pointer based on whether the inline question row was in
    // `listState.layoutInfo.visibleItemsInfo`. Tall bar → smaller viewport →
    // the row leaves visibleItemsInfo → short bar → the row is back → tall bar,
    // with `imePadding()` re-driving it every frame the keyboard moved.
    //
    // Twin mapping: the chat list plus a bottom bar in a Column, exactly the
    // ChatScreen Scaffold arrangement. Frames are pumped by hand (autoAdvance
    // off) so a genuinely non-terminating loop fails as a flip count rather
    // than hanging the suite.

    private val BAR_TALL = 480.dp
    private val BAR_SHORT = 40.dp

    /** Chat list + bottom bar, mirroring ChatScreen's Scaffold. [barHeight] is
     *  invoked in composition with the list's state so a test can decide the
     *  bar's height the correct way (from its own state) or the wrong way
     *  (from the list's layout). Every composed height is recorded. */
    @Composable
    private fun DockedBarHost(
        msgs: List<ChatMessage>,
        listState: LazyListState,
        recorded: MutableList<Dp>,
        barHeight: @Composable (LazyListState) -> Dp,
    ) {
        MarmaladeTheme {
            Box(Modifier.width(360.dp).height(640.dp)) {
                Column(Modifier.fillMaxSize()) {
                    ChatMessageList(
                        messages = msgs,
                        onBlockResponse = {},
                        onImageTap = { _, _ -> },
                        onBubbleAction = { _, _ -> },
                        listState = listState,
                        modifier = Modifier.weight(1f),
                    )
                    val h = barHeight(listState)
                    recorded += h
                    Box(Modifier.fillMaxWidth().height(h))
                }
            }
        }
    }

    /** How many times consecutive composed bar heights differ. A settled
     *  layout changes at most once (the initial measure → the new height). */
    private fun flips(heights: List<Dp>): Int =
        heights.zipWithNext().count { (a, b) -> a != b }

    @Test
    fun `a bottom bar sized from its own state reaches a fixed point`() {
        // The shipped shape: the docked prompt card is present iff a prompt is
        // pending — a fact about the wire, never about what the list can see.
        val heights = mutableListOf<Dp>()
        var promptPending by mutableStateOf(false)
        rule.setContent {
            DockedBarHost(messages(), rememberLazyListState(), heights) {
                if (promptPending) BAR_TALL else BAR_SHORT
            }
        }
        // Safe to wait for idle here precisely because this shape terminates.
        rule.waitForIdle()

        // A question parks. The bar grows once and stays grown.
        rule.runOnUiThread { promptPending = true }
        rule.waitForIdle()
        assertEquals(BAR_TALL, heights.last())
        assertEquals("the bar must settle at its new height", 1, flips(heights))
    }

    @Test
    fun `a bottom bar sized from the list's layout never settles — the loop`() {
        // The removed shape, kept as the twin's teeth: it asserts the loop is
        // real and reproducible, so the test above is measuring something. If
        // this ever stops looping the geometry drifted and both tests need
        // re-deriving, not deleting.
        //
        // The failure mode is literally "composition never goes idle", so that
        // is what gets asserted — with Espresso's master timeout dialled down
        // for the duration, since the default is a 60-second wait.
        val previous = IdlingPolicies.getMasterIdlingPolicy().idleTimeout
        val previousUnit = IdlingPolicies.getMasterIdlingPolicy().idleTimeoutUnit
        IdlingPolicies.setMasterPolicyTimeout(3, TimeUnit.SECONDS)
        try {
            val heights = mutableListOf<Dp>()
            // setContent itself waits for idle, so the loop trips right here.
            val failure = runCatching {
                rule.setContent {
                    DockedBarHost(messages(), rememberLazyListState(), heights) { state ->
                        // "Is the question's row on screen?" — the exact
                        // derivation the pointer/card swap used. The target is
                        // a row a few up from the bottom: reverse layout pins
                        // the NEWEST row to the bottom edge, so only a row
                        // further up can be pushed out of the viewport by a
                        // growing bottom bar (on device that is the transcript
                        // scrolled up even slightly — the normal state while
                        // reading a long answer).
                        val visible by remember(state) {
                            derivedStateOf {
                                state.layoutInfo.visibleItemsInfo
                                    .any { it.key == "bubble:m-27:0" }
                            }
                        }
                        if (visible) BAR_TALL else BAR_SHORT
                    }
                }
            }.exceptionOrNull()
            assertTrue(
                "expected the visibility-driven bar to loop forever, saw ${flips(heights)} " +
                    "flips and ${failure ?: "a settled layout"}",
                failure is AppNotIdleException,
            )
        } finally {
            IdlingPolicies.setMasterPolicyTimeout(previous, previousUnit)
        }
    }

    @Test
    fun `reader scrolled up is not yanked to the bottom by new content`() {
        var msgs by mutableStateOf(messages())
        var state: LazyListState? = null
        rule.setContent {
            val st = rememberLazyListState()
            state = st
            Host(msgs, height = 640.dp, listState = st)
        }
        rule.waitForIdle()

        // Scroll away from the bottom to read older messages (reverse
        // layout: higher index = older).
        rule.runOnIdle { runBlocking { state!!.scrollToItem(15) } }
        rule.waitForIdle()
        val anchorIndex = state!!.firstVisibleItemIndex
        assertTrue("precondition: reader is away from the bottom", anchorIndex > 1)

        // New content arrives while reading. It is inserted at index 0
        // (the bottom); the reader's anchor item must stay put — its index
        // shifts by exactly the one inserted row, and no auto-stick fires.
        msgs = msgs + message(99, "brand new reply")
        rule.waitForIdle()
        assertEquals(
            "reading position must be preserved (anchor item shifted by the inserted row)",
            anchorIndex + 1,
            state!!.firstVisibleItemIndex,
        )
    }
}
