package app.marmalade.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandTest {

    @Test
    fun forwardSlashAloneReturnsAllCommands() {
        val result = filterSlashCommands("/")
        assertEquals(SLASH_COMMANDS.size, result.size)
    }

    @Test
    fun prefixMatchIsCaseInsensitive() {
        val lower = filterSlashCommands("/ti")
        val upper = filterSlashCommands("/TI")
        assertEquals(lower, upper)
    }

    @Test
    fun prefixMatchFiltersCorrectly() {
        val result = filterSlashCommands("/ti")
        // "/title" starts with "/ti"; "/new" and "/sessions" do not.
        assertTrue(result.any { it.command == "/title" })
        assertTrue(result.none { it.command == "/new" })
        assertTrue(result.none { it.command == "/sessions" })
    }

    @Test
    fun exactMatchReturnsOnlyThatCommand() {
        val result = filterSlashCommands("/new")
        assertEquals(1, result.size)
        assertEquals("/new", result.first().command)
    }

    @Test
    fun noMatchReturnsEmptyList() {
        val result = filterSlashCommands("/zzznomatch")
        assertTrue(result.isEmpty())
    }

    @Test
    fun fullCommandWithTrailingSpaceMatchesExact() {
        // "/new " — with trailing space trim, query becomes "/new", matches exactly
        val result = filterSlashCommands("/new ")
        assertEquals(1, result.size)
        assertEquals("/new", result.first().command)
    }

    @Test
    fun staticListOnlyOffersDispatcherHandledCommands() {
        // The popup must never advertise a command the dispatcher can't run
        // client-side (the fork catalog went with the marmaladed flip).
        val offered = SLASH_COMMANDS.map { it.command }.toSet()
        assertEquals(setOf("/new", "/clear", "/title", "/sessions"), offered)
    }
}
