package app.marmalade.android.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [computeAttachmentsSupported], the predicate behind
 * [MarmaladeRuntime.attachmentsSupported]. The daemon defines no
 * "attachments" feature yet, so this is false today by design — the
 * composer's attach UI auto-lights once the daemon starts advertising it.
 */
class AttachmentsSupportedTest {

  @Test
  fun `no features reported — attachments not supported`() {
    assertFalse(computeAttachmentsSupported(emptyList()))
  }

  @Test
  fun `features present but no attachments entry — not supported`() {
    assertFalse(computeAttachmentsSupported(listOf("stable-ids", "subscribe")))
  }

  @Test
  fun `attachments feature advertised — supported`() {
    assertTrue(computeAttachmentsSupported(listOf("stable-ids", "attachments")))
  }

  @Test
  fun `transcription gate follows the hello feature`() {
    assertFalse(computeTranscriptionSupported(emptyList()))
    assertFalse(computeTranscriptionSupported(listOf("stable-ids", "attachments")))
    assertTrue(computeTranscriptionSupported(listOf("stable-ids", "transcription")))
  }

  @Test
  fun `terminal gate follows the hello feature`() {
    assertFalse(computeTerminalSupported(emptyList()))
    assertFalse(computeTerminalSupported(listOf("stable-ids", "attachments")))
    assertTrue(computeTerminalSupported(listOf("stable-ids", "terminal")))
  }

  @Test
  fun `search gate follows the hello feature`() {
    // search.messages 404s without the daemon's FTS5 sidecar, so both the
    // drawer's Search entry and find-in-conversation ride this gate. There is
    // deliberately no client-local fallback index.
    assertFalse(computeSearchSupported(emptyList()))
    assertFalse(computeSearchSupported(listOf("stable-ids", "workspaces")))
    assertTrue(computeSearchSupported(listOf("stable-ids", "search")))
  }

  @Test
  fun `the archive gate is its own feature, not implied by search`() {
    // A daemon can run the FTS sidecar over its own sessions without having
    // indexed ~/.claude/projects. Ungated, it would silently ignore the unknown
    // scope.corpus and answer with LIVE results under an "Archive" chip —
    // worse than not offering the chip at all.
    assertFalse(computeSearchArchiveSupported(emptyList()))
    assertFalse(computeSearchArchiveSupported(listOf("stable-ids", "search")))
    assertTrue(computeSearchArchiveSupported(listOf("stable-ids", "search", "search_archive")))
  }

  @Test
  fun `the gates do not alias each other`() {
    // One feature string must light exactly one gate — a substring or
    // prefix-matching slip here would silently enable a 404ing UI.
    val only = listOf("search")
    assertTrue(computeSearchSupported(only))
    assertFalse(computeSearchArchiveSupported(only))
    assertFalse(computeTerminalSupported(only))
    assertFalse(computeWorkspacesSupported(only))
    assertFalse(computeSettingsSupported(only))
    assertFalse(computeUndoSupported(only))
  }
}
