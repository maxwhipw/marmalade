package app.marmalade.android.chat

/**
 * Single entry-point for all client-side slash command handling.
 *
 * Each known slash command has a defined fate:
 *
 * - **Action** — pure client-side op (no RPC). E.g. /new, /clear.
 * - **ExecWithArgs** — calls an RPC on [ChatController] (which surfaces a
 *   snackbar via [ChatController.toastMessage]); requires text after the slash. If args
 *   are missing the dispatcher returns a [Result.Unavailable] explaining
 *   the usage. /title is special — empty args opens the rename dialog.
 * - **OpenRenameDialog** / **OpenSessionPicker** — return a UI-action
 *   [Result] so the host (Composer / ChatScreen) opens the right surface.
 * - **Unavailable** — known but the Android surface isn't built yet.
 *   User sees a snackbar; the command is NOT sent to the server (so it
 *   never reaches the LLM as plain text).
 * - **Pass-through** — unknown slash (extension commands, skill commands)
 *   falls through and is forwarded to [onSend] by the caller.
 *
 * Mirrors the spec-table pattern in `desktop-slash-commands.ts` +
 * `use-prompt-actions.ts`.
 */
object SlashCommandDispatcher {

    // ── Result type ──────────────────────────────────────────────────────────

    sealed class Result {
        /** Command was handled. Caller should clear the composer. */
        object Handled : Result()

        /** Command is known but has no Android surface yet. Show [message]
         *  as a snackbar and clear the composer so it doesn't reach the
         *  server. */
        data class Unavailable(val message: String) : Result()

        /** Not a slash command, or an unknown slash (extension / skill).
         *  Caller should forward the text to [onSend]. */
        object NotASlashCommand : Result()

        /** Open the rename-session dialog. Composer hosts it; the
         *  dialog's onConfirm calls [ChatController.renameCurrentSession]. */
        object ShowRenameDialog : Result()

        /** Open the session picker (sidebar / Sessions tab). Composer's
         *  parent passes the navigation callback in. */
        object OpenSessionPicker : Result()

        /** Known command, wrong shape (e.g. missing required argument).
         *  Caller should snackbar [message] AND PRESERVE the composer
         *  text so the user can append the missing arg without retyping
         *  the command. Distinct from [Unavailable] (which clears text
         *  because the command can't be made to work no matter what
         *  the user adds). */
        data class UsageError(val message: String) : Result()
    }

    // ── Command table ────────────────────────────────────────────────────────

    private enum class HandlerKind {
        Action,             // controller method, no args, no RPC
        ExecWithArgs,       // controller method that calls RPC with args (text after slash)
        OpenRenameDialog,
        OpenSessionPicker,
        Unavailable,
    }

    private data class CommandSpec(
        val kind: HandlerKind,
        val displayName: String,
        /** Invoked for Action / Exec / ExecWithArgs. Second arg is the
         *  trimmed text after the slash (null for Action/Exec). */
        val handler: (ChatController, String?) -> Unit = { _, _ -> },
    )

    private val COMMAND_TABLE: Map<String, CommandSpec> = buildMap {
        // ── Action: pure client-side, no RPC ─────────────────────────────────
        val freshSession = CommandSpec(HandlerKind.Action, "/new") { c, _ -> c.startFreshSession() }
        put("/new", freshSession)
        put("/reset", freshSession.copy(displayName = "/reset"))
        put("/clear", freshSession.copy(displayName = "/clear"))

        // ── ExecWithArgs: RPC with required text after slash ─────────────────
        // Missing-args path is hoisted into dispatch() so it can return
        // UsageError (which the Composer preserves text on) instead of
        // emitting via emitToast and returning Handled (which clears text).
        fun execArgs(name: String, vararg aliases: String, run: (ChatController, String) -> Unit) {
            val spec = CommandSpec(HandlerKind.ExecWithArgs, name) { c, args ->
                // Pre-validated in dispatch(); args is non-null + non-blank here.
                run(c, args!!)
            }
            put(name, spec)
            for (alias in aliases) put(alias, spec.copy(displayName = alias))
        }
        // /title is dual-mode: with args = rename; no args = open dialog.
        // Implemented as ExecWithArgs whose null-args branch is overridden in
        // dispatch() below to return ShowRenameDialog.
        execArgs("/title") { c, args -> c.renameCurrentSession(args) }

        // ── UI navigation ────────────────────────────────────────────────────
        val sessionPicker = CommandSpec(HandlerKind.OpenSessionPicker, "/sessions")
        put("/sessions", sessionPicker)
        put("/switch", sessionPicker.copy(displayName = "/switch"))
        put("/resume", sessionPicker.copy(displayName = "/resume"))

        // ── Unavailable: known commands, Android surface not built yet ───────
        // Kept as Unavailable (not pass-through) so they snackbar instead of
        // silently reaching the LLM as plain text.
        fun unavail(name: String, vararg aliases: String) {
            val spec = CommandSpec(HandlerKind.Unavailable, name)
            put(name, spec)
            for (alias in aliases) put(alias, spec.copy(displayName = alias))
        }
        unavail("/branch", "/fork")   // needs branch-name dialog + sidebar refresh
        unavail("/rollback")          // needs FS-checkpoint picker UI
        // Fork-gateway machinery with no marmaladed method (gap triage,
        // 2026-07-11): the daemon has stop/interrupt; save/undo/compress/
        // steer/status/background/queue were fork-gateway RPC rituals.
        // Kept as Unavailable (not deleted) so a manually-typed command
        // snackbars instead of leaking to the LLM as plain prompt text.
        unavail("/save")
        unavail("/stop")              // Composer's Stop button interrupts the turn
        unavail("/undo")
        unavail("/compress")
        unavail("/retry")
        unavail("/status")
        unavail("/agents", "/tasks")
        unavail("/background", "/bg", "/btw")
        unavail("/queue", "/q")
        unavail("/steer")
        unavail("/goal")
        unavail("/subgoal")

        // /approve and /deny are hidden from the catalog popup
        // (ANDROID_HIDDEN_COMMANDS) because the ApprovalBanner inline UI
        // handles them. BUT if a user types one manually it would pass
        // through to prompt.submit and reach the LLM as plain text — the
        // gateway only treats /approve as a slash via slash.exec, not
        // prompt.submit. Mark them Unavailable here as defense-in-depth so
        // the snackbar redirects users to the banner instead of leaking
        // their intent to the LLM.
        val approvalSpec = CommandSpec(
            HandlerKind.Unavailable,
            "/approve — use the approval banner above the composer",
        )
        put("/approve", approvalSpec)
        put("/deny", approvalSpec.copy(
            displayName = "/deny — use the approval banner above the composer",
        ))
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Attempt to dispatch [text] as a slash command.
     *
     * @param text Composer text, already trimmed.
     * @param controller The bound [ChatController] for Action/Exec commands.
     * @return [Result.Handled], [Result.Unavailable], [Result.ShowRenameDialog],
     *         [Result.OpenSessionPicker], or [Result.NotASlashCommand].
     */
    fun dispatch(text: String, controller: ChatController): Result {
        if (!text.startsWith("/")) return Result.NotASlashCommand

        // Split into command token + args. Don't lowercase the args (case may
        // matter for free-form text like /title or /steer). Split on ANY
        // whitespace (tab, NBSP, …) rather than just ASCII space — paste +
        // external-keyboard inputs sometimes wedge a tab between the
        // command and its argument.
        val firstWhitespace = text.indexOfFirst { it.isWhitespace() }
        val token = (if (firstWhitespace < 0) text else text.substring(0, firstWhitespace)).lowercase()
        val args = if (firstWhitespace < 0) null else text.substring(firstWhitespace + 1).trim().ifEmpty { null }

        val spec = COMMAND_TABLE[token] ?: return Result.NotASlashCommand

        return when (spec.kind) {
            HandlerKind.Action -> {
                spec.handler(controller, null)
                Result.Handled
            }
            HandlerKind.ExecWithArgs -> when {
                // /title (and only /title) opens the rename dialog when no args.
                args == null && token == "/title" -> Result.ShowRenameDialog
                args == null -> Result.UsageError(
                    "$token needs an argument: $token <text>"
                )
                else -> {
                    spec.handler(controller, args)
                    Result.Handled
                }
            }
            HandlerKind.OpenRenameDialog -> Result.ShowRenameDialog
            HandlerKind.OpenSessionPicker -> Result.OpenSessionPicker
            HandlerKind.Unavailable -> Result.Unavailable(
                "Command not yet supported on Android: ${spec.displayName}"
            )
        }
    }
}
