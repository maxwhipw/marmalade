package app.marmalade.android.ui.sessions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isValidSessionTitle] — the pure trim/blank-check helper
 * extracted from [RenameSessionDialog].
 *
 * Compose UI tests are excluded because Robolectric is not wired up for
 * dialog composables in this project. The confirm-button guard logic is
 * fully covered here as a plain JVM test.
 */
class RenameSessionDialogTest {

    @Test
    fun `non-blank title is valid`() {
        assertTrue(isValidSessionTitle("My Session"))
    }

    @Test
    fun `title with only whitespace is invalid`() {
        assertFalse(isValidSessionTitle("   "))
    }

    @Test
    fun `empty title is invalid`() {
        assertFalse(isValidSessionTitle(""))
    }

    @Test
    fun `title with leading and trailing whitespace is valid`() {
        // isNotBlank() trims internally — the dialog's onClick does title.trim()
        // before calling onConfirm, so a padded string still passes the guard.
        assertTrue(isValidSessionTitle("  hello  "))
    }
}
