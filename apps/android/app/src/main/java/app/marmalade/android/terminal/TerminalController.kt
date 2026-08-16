package app.marmalade.android.terminal

import app.marmalade.android.rpc.GatewayEvent
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.types.TerminalAttachResponse
import app.marmalade.android.rpc.types.TerminalDataPayload
import app.marmalade.android.rpc.types.TerminalExitPayload
import app.marmalade.android.rpc.types.TerminalInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Owns the terminal roster + the seven `terminal.*` RPC calls, and routes the
 * transient `terminal.data` / `terminal.exit` events AROUND the chat/session
 * path (no Room, no session state, no watermark — a terminal is not a
 * session). Mirrors the webui's `gateway/client.ts` terminal
 * wrappers + `dispatchEvent` early-return.
 *
 * The screens own the per-open-terminal attach/detach lifecycle and the
 * reconnect re-attach (like `TerminalView.tsx`); this controller is the thin,
 * testable RPC + event-demux layer beneath them. Delivery is attach-scoped
 * SERVER-side, so [output] only carries data for terminals THIS connection has
 * attached — the controller does no client-side filtering of who gets what.
 *
 * @param events the gateway event stream (rpcClient.events). The demux
 *   coroutine runs on [scope]; inject a test scope to drive it deterministically.
 */
class TerminalController(
    private val rpc: MarmaladeRpc,
    private val scope: CoroutineScope,
    events: SharedFlow<GatewayEvent>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val _terminals = MutableStateFlow<List<TerminalInfo>>(emptyList())
    /** The live roster (shell · cwd · pid). Refreshed on [refresh] and whenever
     *  a terminal.exit arrives (a shell dying from any device prunes the row). */
    val terminals: StateFlow<List<TerminalInfo>> = _terminals.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Last roster-refresh error (transport/RPC), or null. Direct ops
     *  (create/close/attach) throw instead so callers can react inline. */
    val error: StateFlow<String?> = _error.asStateFlow()

    // Transient PTY output / exit — replay=0, buffered. Consumers (the open
    // TerminalScreen) collect while composed; a reconnect re-attaches and
    // repaints from the snapshot, so nothing needs to be buffered across gaps.
    private val _output = MutableSharedFlow<TerminalDataPayload>(extraBufferCapacity = 256)
    val output: SharedFlow<TerminalDataPayload> = _output.asSharedFlow()

    private val _exits = MutableSharedFlow<TerminalExitPayload>(extraBufferCapacity = 16)
    val exits: SharedFlow<TerminalExitPayload> = _exits.asSharedFlow()

    init {
        scope.launch {
            events.collect { ev ->
                when (ev.type) {
                    "terminal.data" -> ev.payload?.let {
                        runCatching { json.decodeFromJsonElement(TerminalDataPayload.serializer(), it) }
                            .getOrNull()?.let { p -> _output.emit(p) }
                    }
                    "terminal.exit" -> ev.payload?.let {
                        runCatching { json.decodeFromJsonElement(TerminalExitPayload.serializer(), it) }
                            .getOrNull()?.let { p ->
                                _exits.emit(p)
                                // A shell dying (here or from another device) prunes the
                                // roster — mirror the webui's roster-level exit watch.
                                refresh()
                            }
                    }
                }
            }
        }
    }

    /** Pull the roster. Swallows + records transport errors in [error] (it is
     *  also called from the exit watch, where a throw would kill the collector). */
    suspend fun refresh() {
        try {
            _terminals.value = rpc.terminalList().terminals
            _error.value = null
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
        }
    }

    /** Spawn a shell; the creating connection is auto-attached server-side. The
     *  roster is refreshed so the new row is present immediately. */
    suspend fun create(cols: Int = 80, rows: Int = 24, cwd: String? = null): TerminalInfo {
        val info = rpc.terminalCreate(cols, rows, cwd).terminal
        refresh()
        return info
    }

    /** Join a terminal's stream + get its scrollback snapshot.
     *
     *  A "gone" answer PRUNES the row: terminals don't survive a daemon
     *  restart, so a roster fetched beforehand lists shells that no longer
     *  exist, and leaving them in place means the drawer keeps offering
     *  terminals that can only ever fail to open. Transport failures are
     *  rethrown untouched — the shell may still be alive. */
    suspend fun attach(terminalId: String): TerminalAttachResponse =
        try {
            rpc.terminalAttach(terminalId)
        } catch (t: Throwable) {
            if (TerminalErrors.isGone(t.message)) {
                _terminals.update { list -> list.filterNot { it.terminal_id == terminalId } }
            }
            throw t
        }

    fun detach(terminalId: String) {
        scope.launch { runCatching { rpc.terminalDetach(terminalId) } }
    }

    suspend fun input(terminalId: String, dataB64: String) {
        rpc.terminalInput(terminalId, dataB64)
    }

    suspend fun resize(terminalId: String, cols: Int, rows: Int) {
        rpc.terminalResize(terminalId, cols, rows)
    }

    /** Kill the shell. terminal.exit → the roster prunes itself; this also
     *  refreshes to cover the race where the exit event beats the list call. */
    suspend fun close(terminalId: String): Boolean {
        val closed = rpc.terminalClose(terminalId).closed
        refresh()
        return closed
    }
}
