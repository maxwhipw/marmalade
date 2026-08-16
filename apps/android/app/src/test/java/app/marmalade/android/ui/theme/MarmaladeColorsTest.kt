package app.marmalade.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for MarmaladeColors dark and light instances completeness.
 * Ensures no fields are left as default Color.Unspecified.
 */
class MarmaladeColorsTest {

    @Test
    fun darkMarmaladeColors_hasNonDefaultCodeBackground() {
        assertNotEquals(Color.Unspecified, DarkMarmaladeColors.codeBackground)
    }

    @Test
    fun darkMarmaladeColors_hasNonDefaultCodeBorder() {
        assertNotEquals(Color.Unspecified, DarkMarmaladeColors.codeBorder)
    }

    @Test
    fun darkMarmaladeColors_hasNonDefaultCodeText() {
        assertNotEquals(Color.Unspecified, DarkMarmaladeColors.codeText)
    }

    @Test
    fun darkMarmaladeColors_hasNonDefaultToolSuccess() {
        assertNotEquals(Color.Unspecified, DarkMarmaladeColors.toolSuccess)
    }

    @Test
    fun darkMarmaladeColors_hasNonDefaultUserBubble() {
        assertNotEquals(Color.Unspecified, DarkMarmaladeColors.userBubble)
    }

    @Test
    fun darkMarmaladeColors_hasNonDefaultAssistantBubble() {
        assertNotEquals(Color.Unspecified, DarkMarmaladeColors.assistantBubble)
    }

    @Test
    fun darkMarmaladeColors_hasNonDefaultChatTextPrimary() {
        assertNotEquals(Color.Unspecified, DarkMarmaladeColors.chatTextPrimary)
    }

    @Test
    fun darkMarmaladeColors_hasNonDefaultStatusConnected() {
        assertNotEquals(Color.Unspecified, DarkMarmaladeColors.statusConnected)
    }

    @Test
    fun lightMarmaladeColors_hasNonDefaultCodeBackground() {
        assertNotEquals(Color.Unspecified, LightMarmaladeColors.codeBackground)
    }

    @Test
    fun lightMarmaladeColors_hasNonDefaultUserBubble() {
        assertNotEquals(Color.Unspecified, LightMarmaladeColors.userBubble)
    }

    @Test
    fun lightMarmaladeColors_hasNonDefaultAssistantBubble() {
        assertNotEquals(Color.Unspecified, LightMarmaladeColors.assistantBubble)
    }

    @Test
    fun lightMarmaladeColors_hasNonDefaultStatusConnected() {
        assertNotEquals(Color.Unspecified, LightMarmaladeColors.statusConnected)
    }

    @Test
    fun darkAndLight_differOnCodeBackground() {
        assertNotEquals(
            "Dark and light codeBackground should differ",
            DarkMarmaladeColors.codeBackground,
            LightMarmaladeColors.codeBackground,
        )
    }

    @Test
    fun userBubble_isPeachInBothModes() {
        // The locked maintainer-approved combination: the user bubble is Peach (#FDBA74) in
        // both light and dark — only the assistant bubble swaps.
        assertEquals(Color(0xFFFDBA74), LightMarmaladeColors.userBubble)
        assertEquals(Color(0xFFFDBA74), DarkMarmaladeColors.userBubble)
    }

    @Test
    fun darkAndLight_differOnAssistantBubble() {
        assertNotEquals(
            "Dark and light assistantBubble should differ",
            DarkMarmaladeColors.assistantBubble,
            LightMarmaladeColors.assistantBubble,
        )
    }
}
