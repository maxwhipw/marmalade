package app.marmalade.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for [ActivityVocabulary]. No Robolectric — the
 * vocabulary is plain data + a tiny lookup function.
 */
class ActivityVocabularyTest {

    @Test
    fun `verbsFor thinking returns Thinking-led list`() {
        val verbs = ActivityVocabulary.verbsFor("thinking")
        assertTrue("thinking list must be non-empty", verbs.isNotEmpty())
        assertEquals("Thinking", verbs.first())
    }

    @Test
    fun `verbsFor writing returns Writing-led list`() {
        val verbs = ActivityVocabulary.verbsFor("writing")
        assertTrue(verbs.isNotEmpty())
        assertEquals("Writing", verbs.first())
    }

    @Test
    fun `verbsFor starting returns five entries with Warming up first`() {
        val verbs = ActivityVocabulary.verbsFor("starting")
        assertEquals(5, verbs.size)
        assertEquals("Warming up", verbs.first())
    }

    @Test
    fun `verbsFor null returns the default fallback list`() {
        val expectedDefault = ActivityVocabulary.verbsFor("__sentinel_unknown_string__")
        val nullList = ActivityVocabulary.verbsFor(null)
        assertEquals(expectedDefault, nullList)
        // Sanity: default list is the Working/Tinkering/Brewing/Cooking set
        assertEquals(listOf("Working", "Tinkering", "Brewing", "Cooking"), nullList)
    }

    @Test
    fun `verbsFor unknown activity string returns the default fallback list`() {
        val verbs = ActivityVocabulary.verbsFor("garbage_unknown")
        assertEquals(listOf("Working", "Tinkering", "Brewing", "Cooking"), verbs)
    }

    @Test
    fun `verbsFor tool exec contains both Bashing and Moseying`() {
        val verbs = ActivityVocabulary.verbsFor("tool:exec")
        assertTrue("exec list must contain Bashing", "Bashing" in verbs)
        assertTrue("exec list must contain Moseying", "Moseying" in verbs)
    }

    @Test
    fun `verbsFor tool bash returns the same list as tool exec`() {
        val execVerbs = ActivityVocabulary.verbsFor("tool:exec")
        val bashVerbs = ActivityVocabulary.verbsFor("tool:bash")
        assertEquals(execVerbs, bashVerbs)
    }

    @Test
    fun `verbsFor unknown tool returns the generic non-OpenClaw list`() {
        val verbs = ActivityVocabulary.verbsFor("tool:my_custom_thing")
        assertEquals(listOf("Wielding", "Operating", "Tinkering", "Conjuring"), verbs)
        // Sanity: confirm this name really isn't in the built-in set.
        assertTrue("my_custom_thing must not be a built-in",
            "my_custom_thing" !in ActivityVocabulary.BUILT_IN_OPENCLAW_TOOLS)
    }

    @Test
    fun `pickVerb thinking has null subtitle`() {
        val result = ActivityVocabulary.pickVerb("thinking", 0)
        assertNull(result.subtitle)
    }

    @Test
    fun `pickVerb tool exec has null subtitle (built-in)`() {
        val result = ActivityVocabulary.pickVerb("tool:exec", 0)
        assertNull(result.subtitle)
    }

    @Test
    fun `pickVerb unknown tool exposes raw tool name as subtitle`() {
        val result = ActivityVocabulary.pickVerb("tool:my_custom_thing", 0)
        assertEquals("my_custom_thing", result.subtitle)
    }

    @Test
    fun `pickVerb wraps index modulo verb list size`() {
        val verbs = ActivityVocabulary.verbsFor("thinking")
        val expected = verbs[100 % verbs.size]
        val result = ActivityVocabulary.pickVerb("thinking", 100)
        assertEquals(expected, result.verb)
    }

    @Test
    fun `pickVerb tool exec at index zero returns first exec verb`() {
        val first = ActivityVocabulary.verbsFor("tool:exec").first()
        val result = ActivityVocabulary.pickVerb("tool:exec", 0)
        assertEquals(first, result.verb)
    }

    @Test
    fun `pickVerb handles negative index without throwing`() {
        // floorMod(-1, n) = n-1 — valid index; assert the verb is the
        // last entry in the default fallback list.
        val verbs = ActivityVocabulary.verbsFor(null)
        val result = ActivityVocabulary.pickVerb(null, -1)
        assertEquals(verbs.last(), result.verb)
        assertNull(result.subtitle)
    }

    @Test
    fun `BUILT_IN_OPENCLAW_TOOLS contains expected canonical names`() {
        // Spot-check a handful from each source category — guards
        // against accidental deletion of the registry list.
        val expected = listOf(
            "exec", "bash", "shell",
            "read_file", "edit", "apply_patch",
            "web_fetch", "web_search",
            "grep", "find", "ls", "glob",
            "canvas", "nodes", "gateway", "update_plan",
        )
        for (name in expected) {
            assertTrue("$name should be a built-in OpenClaw tool",
                name in ActivityVocabulary.BUILT_IN_OPENCLAW_TOOLS)
        }
    }

    @Test
    fun `tool exec curated list differs from generic non-OpenClaw list`() {
        // Pin: a built-in with curated verbs must NOT silently fall
        // through to the generic list.
        assertNotEquals(
            ActivityVocabulary.verbsFor("tool:my_custom_thing"),
            ActivityVocabulary.verbsFor("tool:exec"),
        )
    }
}
