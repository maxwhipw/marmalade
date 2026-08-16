package app.marmalade.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-client unread derivation (marmaladed P4 seq cursors):
 *  unread = lastSeq > seenSeq. Pure arithmetic — no clocks, no epsilon. */
class UnreadUtilsTest {

    @Test
    fun `messages past the read cursor are unread`() {
        assertTrue(UnreadUtils.isUnread(lastSeq = 12, seenSeq = 8))
    }

    @Test
    fun `cursor at the head is read`() {
        assertFalse(UnreadUtils.isUnread(lastSeq = 12, seenSeq = 12))
    }

    @Test
    fun `cursor past the head is read - stale list row racing a fresh stamp`() {
        assertFalse(UnreadUtils.isUnread(lastSeq = 10, seenSeq = 12))
    }

    @Test
    fun `empty session is read`() {
        assertFalse(UnreadUtils.isUnread(lastSeq = 0, seenSeq = 0))
    }

    @Test
    fun `never-seen session with messages is unread`() {
        assertTrue(UnreadUtils.isUnread(lastSeq = 3, seenSeq = 0))
    }
}
