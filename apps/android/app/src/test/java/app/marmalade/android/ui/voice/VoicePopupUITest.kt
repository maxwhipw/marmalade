package app.marmalade.android.ui.voice

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.marmalade.android.service.AssistantState
import app.marmalade.android.service.VoiceMessage
import app.marmalade.android.ui.theme.MarmaladeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [VoicePopupUI].
 *
 * The popup is now (V1) the only voice-experience surface in
 * `marmalade-android`. These tests pin the user-visible contract:
 *
 *  - Working state surfaces exactly ONE "Thinking…" indicator (the mini
 *    chat bubble); the old bottom state chip is gone (redundant with the
 *    bubble + mascot, maintainer 2026-07-04).
 *  - Empty-state hint shows when there's no conversation yet, and
 *    disappears once messages start arriving.
 *  - Mic and Auto-listen callbacks fire on tap.
 *  - All three dismiss affordances (scrim, X button, system back)
 *    end up at the same `onDismiss` callback.
 *
 * Mirrors the harness from `ActivityBubbleTest`:
 *  - bare framework [Application] skips MarmaladeApplication's
 *    AndroidKeyStore touch that Robolectric can't shadow.
 *  - [ComponentActivity] host so BackHandler has an OnBackPressedDispatcher.
 *
 * Animation timing (mascot pulse, mic halo, chip crossfade) is
 * intentionally NOT asserted — Compose UI tests are unreliable with
 * animation timing and the visible-label assertions are sufficient
 * to pin the contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    qualifiers = "w360dp-h640dp",
    sdk = [34],
    application = Application::class,
)
class VoicePopupUITest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // ── Activity indication ─────────────────────────────────────────────────

    @Test
    fun thinkingStateShowsExactlyOneThinkingIndicator() {
        // The mini chat bubble (the "..." partial assistant message) is the
        // ONLY textual thinking indicator. The old bottom state chip doubled
        // it up and was removed — regression-pin the single-indicator
        // contract.
        renderPopup(
            state = AssistantState.THINKING,
            messages = listOf(
                VoiceMessage(text = "what's the weather", isUser = true),
                VoiceMessage(text = "...", isUser = false, isPartial = true),
            ),
        )
        rule.onAllNodesWithText("Thinking…").assertCountEquals(1)
    }

    @Test
    fun noTextualStateChipRendered() {
        // The mascot + mic glow carry the state now; none of the old chip
        // labels should render for any state.
        renderPopup(state = AssistantState.LISTENING)
        rule.onAllNodesWithText("Listening…").assertCountEquals(0)
        renderPopup(state = AssistantState.SPEAKING)
        rule.onAllNodesWithText("Speaking…").assertCountEquals(0)
    }

    @Test
    fun errorStateShowsBannerWhenProvided() {
        renderPopup(
            state = AssistantState.ERROR,
            errorMessage = "Gateway not connected",
        )
        rule.onNodeWithText("Gateway not connected").assertIsDisplayed()
    }

    // ── Listening hint (patient-mode termination word) ──────────────────────

    @Test
    fun listeningHintShowsOnlyWhileListening() {
        renderPopup(
            state = AssistantState.LISTENING,
            listeningHint = "Say “over” to send",
        )
        rule.onNodeWithTag(VoicePopupTags.LISTENING_HINT).assertIsDisplayed()
        rule.onNodeWithText("Say “over” to send").assertIsDisplayed()

        // Not listening any more — the hint leaves with the state.
        renderPopup(
            state = AssistantState.THINKING,
            listeningHint = "Say “over” to send",
        )
        rule.onAllNodesWithTag(VoicePopupTags.LISTENING_HINT).assertCountEquals(0)
    }

    @Test
    fun noHintProvidedRendersNothing() {
        // The non-patient default: no termination word, no hint row.
        renderPopup(state = AssistantState.LISTENING)
        rule.onAllNodesWithTag(VoicePopupTags.LISTENING_HINT).assertCountEquals(0)
    }

    // ── Empty state ─────────────────────────────────────────────────────────

    @Test
    fun emptyStateShowsTapTheMicPrompt() {
        renderPopup(state = AssistantState.IDLE)
        rule.onNodeWithTag(VoicePopupTags.EMPTY_STATE).assertIsDisplayed()
        rule.onNodeWithText("Tap the mic to start").assertIsDisplayed()
    }

    @Test
    fun emptyStateIsHiddenOnceMessagesArrive() {
        renderPopup(
            state = AssistantState.LISTENING,
            messages = listOf(VoiceMessage(text = "hello", isUser = true)),
        )
        // Empty-state tag is gone when there's at least one message.
        rule.onAllNodesWithTag(VoicePopupTags.EMPTY_STATE).assertCountEquals(0)
        // assertExists rather than assertIsDisplayed — the bubble is
        // inside a LazyColumn whose viewport sizing under Robolectric
        // doesn't always satisfy "displayed in visible bounds", but
        // the node is in the semantic tree (which is what we're
        // asserting: messages render, empty state hides).
        rule.onNodeWithText("hello").assertExists()
    }

    // ── Mic + Auto-listen callbacks ─────────────────────────────────────────

    @Test
    fun micButtonClickInvokesCallback() {
        var clicks = 0
        renderPopup(
            state = AssistantState.IDLE,
            onMicClick = { clicks++ },
        )
        // The MIC_BUTTON testTag sits on the FAB's outer Box, but the click
        // semantics live on FAB's inner Surface (Material 3). performClick on
        // the outer node doesn't route. Descend to the clickable child.
        clickClickableUnder(VoicePopupTags.MIC_BUTTON)
        assertEquals(1, clicks)
    }

    @Test
    fun stopSquareAppearsWhileSpeakingAndRoutesToOnMicClick() {
        // The morph pill (2026-07-04) replaced the mic↔stop FAB swap: the
        // pill root keeps the MIC_BUTTON tag in every state, and a stop
        // square with its own tag+click appears at the right while speaking.
        var clicks = 0
        renderPopup(
            state = AssistantState.SPEAKING,
            onMicClick = { clicks++ },
        )
        rule.onAllNodesWithTag(VoicePopupTags.MIC_BUTTON, useUnmergedTree = true)
            .assertCountEquals(1)
        clickClickableUnder(VoicePopupTags.STOP_BUTTON)
        assertEquals(1, clicks)
    }

    @Test
    fun stopSquareAbsentWhileIdle() {
        renderPopup(state = AssistantState.IDLE)
        rule.onAllNodesWithTag(VoicePopupTags.STOP_BUTTON, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun autoListenToggleInvokesCallback() {
        val received = mutableListOf<Boolean>()
        renderPopup(
            state = AssistantState.IDLE,
            autoListenEnabled = false,
            onAutoListenToggle = { received += it },
        )
        // Switch wraps its toggleable semantics on the inner thumb; testTag
        // on the modifier surfaces on the merged outer node. Use the
        // unmerged tree to reach the toggleable child + drive it directly.
        rule.onNodeWithTag(VoicePopupTags.AUTO_LISTEN, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(true), received)
    }

    /** Drive a click via the OnClick semantics action on the tagged node.
     *  performClick() routes through coordinate-based touch injection which
     *  is brittle under Robolectric for nested-semantics composables like
     *  FloatingActionButton + Switch. The semantics-action path is what the
     *  click handler is actually wired to, so this exercises the contract
     *  the user sees without depending on Robolectric's layout math. */
    private fun clickClickableUnder(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    // ── Dismiss surface ─────────────────────────────────────────────────────

    @Test
    fun closeButtonInvokesOnDismiss() {
        var dismissed = 0
        renderPopup(
            state = AssistantState.IDLE,
            onDismiss = { dismissed++ },
        )
        rule.onNodeWithTag(VoicePopupTags.CLOSE_BUTTON).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun scrimTapInvokesOnDismiss() {
        var dismissed = 0
        renderPopup(
            state = AssistantState.IDLE,
            onDismiss = { dismissed++ },
        )
        rule.onNodeWithTag(VoicePopupTags.SCRIM).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun systemBackInvokesOnDismiss() {
        var dismissed = 0
        renderPopup(
            state = AssistantState.IDLE,
            onDismiss = { dismissed++ },
        )
        rule.activityRule.scenario.onActivity { activity ->
            @Suppress("DEPRECATION")
            activity.onBackPressed()
        }
        rule.waitForIdle()
        assertTrue(
            "Expected onDismiss to be invoked at least once via system back, got $dismissed",
            dismissed >= 1,
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun renderPopup(
        state: AssistantState,
        messages: List<VoiceMessage> = emptyList(),
        autoListenEnabled: Boolean = false,
        isVisible: Boolean = true,
        errorMessage: String? = null,
        listeningHint: String? = null,
        onMicClick: () -> Unit = {},
        onAutoListenToggle: (Boolean) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        rule.activity.setContent {
            MarmaladeTheme {
                VoicePopupUI(
                    state = state,
                    messages = messages,
                    autoListenEnabled = autoListenEnabled,
                    isVisible = isVisible,
                    errorMessage = errorMessage,
                    listeningHint = listeningHint,
                    onMicClick = onMicClick,
                    onAutoListenToggle = onAutoListenToggle,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}
