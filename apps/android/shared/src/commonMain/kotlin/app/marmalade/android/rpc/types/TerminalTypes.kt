package app.marmalade.android.rpc.types

import kotlinx.serialization.Serializable

/**
 * Wire types for the daemon's `terminal.*` PTY surface (protocol v1,
 * `marmalade/packages/protocol/src/methods.ts` terminal section +
 * `events.ts` TerminalDataPayload/TerminalExitPayload).
 *
 * A terminal is NOT a session: no identity stamping, no transcript, no replay
 * cache, no seq. Output is transient and attach-scoped. Scrollback recovery is
 * the ring-buffer [TerminalAttachResponse.snapshot_b64], atomic with joining
 * the live set. Base64 both directions so control bytes survive JSON.
 *
 * These mirror the frozen shapes EXACTLY — do not invent fields. Defaults are
 * defensive (a single omitted field must not reject the whole frame), matching
 * the SessionInfo convention.
 */
@Serializable
data class TerminalInfo(
    val terminal_id: String,
    /** The spawned shell binary, for roster display ("bash"). */
    val shell: String = "",
    val cwd: String = "",
    val cols: Int = 80,
    val rows: Int = 24,
    val pid: Int = 0,
    /** UTC ms. */
    val created_at: Long = 0L,
    /** Last input/output activity (UTC ms). */
    val last_active: Long = 0L,
    /** Workspace membership, cwd-derived server-side exactly like session.list
     *  rows (deepest prefix wins). null = quick terminal (no workspace). */
    val workspace_id: String? = null,
)

@Serializable
data class TerminalCreateResponse(val terminal: TerminalInfo)

/** terminal.attach — the terminal info + the scrollback snapshot (base64 raw
 *  bytes; may begin mid-escape-sequence after ring eviction, which emulators
 *  tolerate). The attach + snapshot are atomic server-side: write the snapshot,
 *  then apply subsequent terminal.data events with no gap and no overlap. */
@Serializable
data class TerminalAttachResponse(
    val terminal: TerminalInfo,
    val snapshot_b64: String = "",
)

@Serializable
data class TerminalCloseResponse(val closed: Boolean = false)

@Serializable
data class TerminalListResponse(val terminals: List<TerminalInfo> = emptyList())

/** `terminal.data` event payload — PTY output, base64 raw bytes. Sent ONLY to
 *  connections attached to the terminal; never stamped/cached/replayed. */
@Serializable
data class TerminalDataPayload(
    val terminal_id: String,
    val data_b64: String = "",
)

/** `terminal.exit` event payload — the shell process died. exit_code is null
 *  when the process died to a signal (or on terminal.close). */
@Serializable
data class TerminalExitPayload(
    val terminal_id: String,
    val exit_code: Int? = null,
)
