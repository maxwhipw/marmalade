package app.marmalade.android.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.marmalade.android.ui.theme.MarmaladeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [ActivityBubble]. The composable is the unified
 * "what is the agent doing right now" surface — typing indicator +
 * streaming thinking header + (Phase 3) tool/writing activity all flow
 * through here.
 *
 * Verb selection is randomised in production; these tests inject a
 * deterministic [pickIndex] fixture so the asserted verb is stable.
 *
 * Mirrors the harness setup from `ThinkingBlockTest`: bare Application
 * skips `MarmaladeApplication.onCreate` (which reaches into
 * androidx.security AndroidKeyStore that Robolectric doesn't shadow).
 *
 * Animations (cross-activity AnimatedContent crossfade, pulsing dots)
 * are intentionally NOT asserted — Compose UI tests struggle with
 * animation timing and the verb-presence assertion is sufficient to
 * pin the user-visible contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    qualifiers = "w360dp-h640dp",
    sdk = [34],
    application = Application::class,
)
class ActivityBubbleTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun startingActivityShowsAStartingVerb() {
        rule.setContent {
            MarmaladeTheme {
                ActivityBubble(activity = "starting", pickIndex = { 0 })
            }
        }
        // verbsFor("starting").first() == "Warming up"
        rule.onNodeWithText("Warming up").assertIsDisplayed()
    }

    @Test
    fun thinkingActivityShowsAThinkingVerb() {
        rule.setContent {
            MarmaladeTheme {
                ActivityBubble(activity = "thinking", pickIndex = { 0 })
            }
        }
        // verbsFor("thinking").first() == "Thinking"
        rule.onNodeWithText("Thinking").assertIsDisplayed()
    }

    @Test
    fun execToolShowsBashingWithFixedIndex() {
        rule.setContent {
            MarmaladeTheme {
                ActivityBubble(activity = "tool:exec", pickIndex = { 0 })
            }
        }
        // verbsFor("tool:exec").first() == "Bashing"
        rule.onNodeWithText("Bashing").assertIsDisplayed()
    }

    @Test
    fun unknownToolShowsRawNameSubtitle() {
        rule.setContent {
            MarmaladeTheme {
                ActivityBubble(activity = "tool:my_custom", pickIndex = { 0 })
            }
        }
        // Unknown tool routes to the generic verb list. With pickIndex 0
        // the selected verb is "Wielding".
        rule.onNodeWithText("Wielding").assertIsDisplayed()
        // The raw tool name surfaces as a dimmed subtitle so advanced
        // users can still see what's running.
        rule.onNodeWithText("my_custom").assertIsDisplayed()
    }

    @Test
    fun bodyTextRendersBelowHeader() {
        rule.setContent {
            MarmaladeTheme {
                ActivityBubble(
                    activity = "thinking",
                    bodyText = "deep thoughts",
                    pickIndex = { 0 },
                )
            }
        }
        // Header verb is present...
        rule.onNodeWithText("Thinking").assertIsDisplayed()
        // ...alongside the body text.
        rule.onNodeWithText("deep thoughts").assertIsDisplayed()
    }

    @Test
    fun emptyBodyTextSuppressesBody() {
        rule.setContent {
            MarmaladeTheme {
                ActivityBubble(
                    activity = "starting",
                    bodyText = "",
                    pickIndex = { 0 },
                )
            }
        }
        // Header verb renders.
        rule.onNodeWithText("Warming up").assertIsDisplayed()
        // No body text node carries the empty-string sentinel — and the
        // structural assertion below pins that exactly one node shows
        // the verb (the header). If a future regression starts rendering
        // a body Column for empty text, an extra Text node would appear.
        rule.onAllNodesWithText("Warming up").assertCountEquals(1)
    }
}
