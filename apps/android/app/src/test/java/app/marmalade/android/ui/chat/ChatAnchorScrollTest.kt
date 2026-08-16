package app.marmalade.android.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.marmalade.android.chat.messages.ChatMessage
import app.marmalade.android.chat.messages.ChatMessagePart
import app.marmalade.android.chat.messages.ChatRole
import app.marmalade.android.ui.theme.MarmaladeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The index math against the REAL list, not a model of it: scroll a live
 * [ChatMessageList] to `anchorListIndex(...)` and assert the anchored message
 * is what's on screen.
 *
 * [ChatAnchorIndexTest] pins the arithmetic; this pins the arithmetic's
 * *premise* — that ChatMessageList emits one base item (plus the activity
 * indicator when shown) before the reversed rows. If that emission ever
 * changes, this test fails where a unit test of the helper alone would happily
 * keep agreeing with itself.
 *
 * Harness mirrors ChatMessageListScrollTest (bare Application, sdk 34).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    qualifiers = "w360dp-h640dp",
    sdk = [34],
    application = Application::class,
)
class ChatAnchorScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private fun message(i: Int, text: String, pending: Boolean = false) = ChatMessage(
        id = "m-$i",
        role = if (i % 2 == 0) ChatRole.Assistant else ChatRole.User,
        parts = listOf(ChatMessagePart.Text(text)),
        seq = i.toLong(),
        timestamp = i * 1_000L,
        pending = pending,
    )

    /** Tall enough that the anchor target is far off screen from the bottom. */
    private fun body(i: Int) =
        "Message $i body line one with enough words to wrap over several " +
            "lines on a 360dp wide viewport so the list is much taller than the screen."

    private fun messages(count: Int = 40): List<ChatMessage> =
        (1..count).map { message(it, if (it == 6) "anchor-target" else body(it)) }

    @Composable
    private fun Host(
        msgs: List<ChatMessage>,
        listState: LazyListState,
        isStreaming: Boolean = false,
        highlightedMessageId: String? = null,
    ) {
        MarmaladeTheme {
            Box(Modifier.width(360.dp).height(640.dp)) {
                ChatMessageList(
                    messages = msgs,
                    onBlockResponse = {},
                    onImageTap = { _, _ -> },
                    onBubbleAction = { _, _ -> },
                    listState = listState,
                    isStreaming = isStreaming,
                    highlightedMessageId = highlightedMessageId,
                )
            }
        }
    }

    private fun jumpTo(
        msgs: List<ChatMessage>,
        targetId: String,
        isStreaming: Boolean = false,
        highlightedMessageId: String? = null,
    ) {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            Host(msgs, state, isStreaming = isStreaming, highlightedMessageId = highlightedMessageId)
        }
        rule.waitForIdle()
        val index = anchorListIndex(
            messages = msgs,
            showActivityIndicator = showChatActivityIndicator(msgs, true, isStreaming),
            targetMessageId = targetId,
        )!!
        rule.runOnIdle { runBlocking { state.scrollToItem(index) } }
        rule.waitForIdle()
    }

    @Test
    fun `the computed index puts the anchored message on screen`() {
        jumpTo(messages(), targetId = "m-6")
        rule.onNodeWithText("anchor-target").assertIsDisplayed()
    }

    @Test
    fun `the index still lands with the activity indicator taking an item`() {
        // A jump into a session whose turn is still running: the indicator
        // occupies one extra list item and every row shifts by one.
        val msgs = messages().dropLast(1) + message(40, body(40), pending = true)
        jumpTo(msgs, targetId = "m-6", isStreaming = true)
        rule.onNodeWithText("anchor-target").assertIsDisplayed()
    }

    @Test
    fun `highlighting the anchored bubble does not disturb the landing`() {
        // The focus ring is paint-only (border + shadow) precisely so it can't
        // reflow the transcript the instant after the jump.
        jumpTo(messages(), targetId = "m-6", highlightedMessageId = "m-6")
        rule.onNodeWithText("anchor-target").assertIsDisplayed()
    }

    @Test
    fun `a multi-row message anchors on its prose, not its tool card`() {
        val toolCall = ChatMessagePart.ToolCall(
            toolCallId = "t-1",
            toolName = "read_file",
            args = kotlinx.serialization.json.JsonObject(emptyMap()),
            argsText = "{}",
            result = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        val msgs = messages().toMutableList()
        msgs[5] = msgs[5].copy(
            parts = listOf(
                ChatMessagePart.Text("anchor-target"),
                toolCall,
                ChatMessagePart.Text("trailing prose after the tool call"),
            ),
        )
        jumpTo(msgs, targetId = "m-6")
        rule.onNodeWithText("anchor-target").assertIsDisplayed()
    }
}
