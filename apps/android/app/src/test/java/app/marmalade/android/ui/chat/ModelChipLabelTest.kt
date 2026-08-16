package app.marmalade.android.ui.chat

import app.marmalade.android.chat.ModelCatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for the composer model chip's label fallback chain
 * ([composerModelChipLabel]) and the daemon-default resolver
 * ([resolveDefaultModelLabel]).
 *
 * The contract under test (daemon-owned new-session defaults, 2026-07-23):
 *  - a NEW session with no pick and no advertised default renders the bare
 *    "Default" — today's exact behavior on an older daemon;
 *  - when the daemon advertised a default_model, that slot instead shows the
 *    model's human label (raw id when the catalog doesn't list it);
 *  - an adopted / picked model (currentModel non-null) ALWAYS wins, so a live
 *    session's real model can never be masked by the daemon default.
 */
class ModelChipLabelTest {

    private val catalog = listOf(
        ModelCatalogEntry(id = "claude-opus-4-8", name = "Opus 4.8", provider = ""),
        ModelCatalogEntry(id = "claude-haiku-4-5", name = "Haiku 4.5", provider = ""),
    )

    // ── resolveDefaultModelLabel ──────────────────────────────────────────────

    @Test
    fun `null default resolves to null`() {
        assertNull(resolveDefaultModelLabel(null, catalog))
    }

    @Test
    fun `default id in catalog resolves to its human label`() {
        assertEquals("Opus 4.8", resolveDefaultModelLabel("claude-opus-4-8", catalog))
    }

    @Test
    fun `default id absent from catalog falls back to the raw id`() {
        assertEquals("some-unlisted-model", resolveDefaultModelLabel("some-unlisted-model", catalog))
    }

    // ── composerModelChipLabel ────────────────────────────────────────────────

    @Test
    fun `no pick and no default renders bare Default`() {
        assertEquals("Default", composerModelChipLabel(null, catalog, null))
    }

    @Test
    fun `no pick with an advertised default renders the default label`() {
        val defaultLabel = resolveDefaultModelLabel("claude-opus-4-8", catalog)
        assertEquals("Opus 4.8", composerModelChipLabel(null, catalog, defaultLabel))
    }

    @Test
    fun `an adopted model wins over the daemon default`() {
        // session.info adopted Haiku; the daemon default is Opus — the adopted
        // value must render, never the default.
        val defaultLabel = resolveDefaultModelLabel("claude-opus-4-8", catalog)
        assertEquals("Haiku 4.5", composerModelChipLabel("claude-haiku-4-5", catalog, defaultLabel))
    }

    @Test
    fun `a picked model not in the catalog falls back to its raw id`() {
        assertEquals("gpt-5", composerModelChipLabel("gpt-5", catalog, "Opus 4.8"))
    }

    @Test
    fun `a blank currentModel is treated as no pick`() {
        assertEquals("Opus 4.8", composerModelChipLabel("", catalog, "Opus 4.8"))
    }
}
