package app.marmalade.android.terminal

/**
 * Telling "this shell is gone" apart from "the call failed".
 *
 * Terminals are the one daemon object with NO persistence — no transcript, no
 * replay, no identity stamping (see TerminalTypes.kt). Every PTY dies with the
 * daemon, so a client roster fetched before a daemon restart lists shells that
 * no longer exist, and attaching to one answers `-32602 unknown terminal`.
 *
 * Observed on device 2026-07-25 after a daemon restart: the terminal screen
 * showed "attach failed: terminal.attach failed (rpc -32602): unknown terminal
 * t_3ef58cae-…" and rendered nothing, which read as "the terminal is still
 * broken" when it was really a stale row.
 */
object TerminalErrors {

    /** JSON-RPC invalid-params, which is what the daemon answers for an id it
     *  doesn't know. Matched alongside the message so a reworded daemon error
     *  (or a differently-coded one) still classifies correctly. */
    const val INVALID_PARAMS = -32602

    /**
     * True when [message] says the terminal itself doesn't exist — as opposed
     * to a transport failure, where the shell may well still be alive and
     * pruning the row would be wrong.
     */
    fun isGone(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val m = message.lowercase()
        return m.contains("unknown terminal") ||
            m.contains("no such terminal") ||
            (m.contains(INVALID_PARAMS.toString()) && m.contains("terminal"))
    }

    /** What to show the user. The cause is almost always a daemon restart, and
     *  saying so beats a raw RPC code — the shell is not coming back, so the
     *  only useful next step is starting a new one. */
    fun goneMessage(): String =
        "That shell is gone — the daemon restarted since it was listed. Start a new terminal."
}
