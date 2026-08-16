package app.marmalade.android.ui.chat

data class SlashCommand(
    val command: String,
    val parameters: String?,
    val description: String,
)

/**
 * Commands that are meaningless or replaced by native Android UI — never shown
 * in the slash-command popup regardless of whether they come from
 * `commands.catalog`, `complete.slash`, or the offline static fallback.
 *
 * - `/topic`      Telegram threading; gateway_only
 * - `/redraw`     TUI screen repaint; meaningless on touch UI
 * - `/snapshot`, `/snap`  CLI screen-capture; cli_only
 * - `/sethome`, `/set-home`  Telegram home-channel config
 * - `/handoff`    Telegram/Discord handoff; mobile-on-mobile makes no sense
 * - `/history`    Auto-loaded on session resume; no UX value as a slash
 * - `/approve`    Handled by the ApprovalBanner inline UI; redundant as slash
 * - `/deny`       Same; inline ApprovalBanner handles it
 */
val ANDROID_HIDDEN_COMMANDS: Set<String> = setOf(
    "/topic",
    "/redraw",
    "/snapshot",
    "/snap",
    "/sethome",
    "/set-home",
    "/handoff",
    "/history",
    "/approve",
    "/deny",
)

/**
 * The commands the popup offers = the commands [SlashCommandDispatcher]
 * actually handles client-side. The fork gateway's ~40-command catalog
 * (and its commands.catalog / complete.slash live completion) went with
 * the marmaladed flip — the daemon exposes no slash surface (gap triage,
 * 2026-07-11). Grow this list only alongside a real dispatcher handler.
 */
val SLASH_COMMANDS: List<SlashCommand> = listOf(
    SlashCommand("/new", null, "Start a fresh session"),
    SlashCommand("/clear", null, "Start a fresh session"),
    SlashCommand("/title", "[name]", "Rename the current session"),
    SlashCommand("/sessions", null, "Open the session picker"),
).filter { it.command !in ANDROID_HIDDEN_COMMANDS }

/**
 * Filter commands by prefix match against user input (e.g., "/th" matches "/think").
 * Case-insensitive. Returns all commands if just "/" is typed.
 */
fun filterSlashCommands(input: String): List<SlashCommand> {
    val query = input.trim().lowercase()
    if (query == "/") return SLASH_COMMANDS
    return SLASH_COMMANDS.filter { it.command.lowercase().startsWith(query) }
}
