package app.marmalade.android.node

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Severity of a [TransportLogEntry]. Maps onto Android [android.util.Log]
 * levels via the buffer's logcat mirror; the in-app Debugging tab uses
 * the same set to color-code rows and filter "show only warn/error".
 */
enum class TransportLogLevel { INFO, DEBUG, WARN, ERROR }

/**
 * One row in the transport log buffer.
 *
 * - [timestamp] — millis since epoch; rendered as HH:mm:ss in the UI.
 * - [level] — see [TransportLogLevel].
 * - [message] — pre-formatted human-readable string. The buffer holds it
 *   verbatim; producers are responsible for any truncation
 *   (e.g. `RAW_FRAME_LOG_CAP_BYTES` for raw WS frames).
 * - [isVerbose] — true for high-volume noise (tick/health/delta frames)
 *   that the Debugging tab shows only when the user opts in.
 * - [runId] — agent-run correlation key when known; lets the Debugging
 *   tab group entries from the same turn without re-parsing every row.
 * - [source] — short tag for the origin (`"rpc"`, `"event"`, `"chat"`,
 *   etc.). Optional; rendered as a sidebar chip in the Debugging tab.
 */
data class TransportLogEntry(
  val timestamp: Long,
  val level: TransportLogLevel,
  val message: String,
  val isVerbose: Boolean = false,
  val runId: String? = null,
  val source: String? = null,
)

/**
 * Thread-safe ring buffer of [TransportLogEntry] backed by a [StateFlow]
 * so the Compose UI re-renders on every write. The transport layer
 * (JsonRpcClient + chat dispatch + connect attempts) flows everything
 * through [add]; the Debugging tab observes [entries].
 *
 * The buffer mirrors entries to logcat under tags `MarmaladeLog` /
 * `MarmaladeLogV` so `adb logcat -s MarmaladeLog:* MarmaladeLogV:*`
 * captures the same stream the in-app viewer shows. Verbose noise
 * (tick / health / delta frames) is suppressed from the logcat mirror
 * — it would flood `adb logcat` to the point of unusability — but stays
 * in the in-memory buffer so the Debugging tab can opt in via filters.
 *
 * Extracted from MarmaladeRuntime in Section C of Task #10. The capacity
 * was bumped from 500 in OpenClaw to 5000 here because high-frequency
 * deltas / ticks now flow through the buffer too (UI consumers apply
 * their own filter). At 150ms delta cadence during streaming, 500
 * filled inside 90 seconds; 5000 gives ~12 minutes of headroom under
 * heavy streaming, more under idle.
 */
class TransportLogBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

  private val _entries = MutableStateFlow<List<TransportLogEntry>>(emptyList())
  val entries: StateFlow<List<TransportLogEntry>> = _entries.asStateFlow()

  private val ring = ArrayDeque<TransportLogEntry>(capacity)

  /**
   * Append an entry. Drops the oldest row when at capacity. Thread-safe;
   * safe to call from any coroutine context.
   *
   * The logcat mirror filters pure-plumbing frames (tick / health /
   * chat-state-delta) — they fill the in-memory buffer (so the Debugging
   * tab can opt in via the verbose toggle) but stay out of `adb logcat`.
   */
  fun add(
    level: TransportLogLevel,
    message: String,
    verbose: Boolean = false,
    runId: String? = null,
    source: String? = null,
  ) {
    val entry = TransportLogEntry(
      timestamp = System.currentTimeMillis(),
      level = level,
      message = message,
      isVerbose = verbose,
      runId = runId,
      source = source,
    )
    synchronized(ring) {
      if (ring.size >= capacity) ring.removeFirst()
      ring.addLast(entry)
      _entries.value = ring.toList()
    }
    mirrorToLogcat(level, message, verbose)
  }

  /** Drop every entry. Used by the Debugging tab's "clear" action. */
  fun clear() {
    synchronized(ring) {
      ring.clear()
      _entries.value = emptyList()
    }
  }

  private fun mirrorToLogcat(level: TransportLogLevel, message: String, verbose: Boolean) {
    val isNoiseForLogcat = verbose && (
      message.contains("\"event\":\"tick\"") ||
        message.contains("\"event\":\"health\"") ||
        (message.contains("\"event\":\"chat\"") && message.contains("\"state\":\"delta\""))
    )
    if (isNoiseForLogcat) return
    val tag = if (verbose) "MarmaladeLogV" else "MarmaladeLog"
    when (level) {
      TransportLogLevel.INFO -> android.util.Log.i(tag, message)
      TransportLogLevel.DEBUG -> android.util.Log.d(tag, message)
      TransportLogLevel.WARN -> android.util.Log.w(tag, message)
      TransportLogLevel.ERROR -> android.util.Log.e(tag, message)
    }
  }

  companion object {
    /** See class-level comment for the 5000 sizing rationale. */
    const val DEFAULT_CAPACITY = 5000
  }
}
