package app.marmalade.desktop.notify

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A desktop notification sink — the desktop analogue of Android's
 * `ChatNotificationHelper`.
 *
 * One method on purpose: everything the client wants to say is a title plus a
 * snippet, and every richer affordance (actions, replace-by-id, urgency) is a
 * backend detail we don't need until something asks for it.
 *
 * Implementations must be safe to call from any thread and must never throw —
 * a notification failing is never worth losing a chat event over.
 */
interface Notifier {
    fun notify(title: String, body: String)
}

/**
 * [Notifier] that shells out to `notify-send`, falling back to `gdbus`.
 *
 * DECIDED (Phase 2): a CLI rather than a D-Bus client of our own. Both of
 * these commands end up calling the same `org.freedesktop.Notifications`
 * method a dbus-java implementation would — this is the same notification
 * with none of the dependency. A direct D-Bus implementation can replace the
 * whole class behind [Notifier] later, if we ever want replace-by-id or
 * notification actions.
 *
 * The fallback exists because `notify-send` ships in `libnotify-bin`, which is
 * NOT installed by default on this KDE box (only the library is) — while
 * `gdbus` comes with glib and is therefore always there. Trying the purpose-
 * built tool first keeps the nice behavior (app name, urgency, desktop entry
 * hints) where it is available.
 *
 * Failure posture: **fail soft, once**. A missing binary advances to the next
 * backend; anything else (nonzero exit, hang) disables notifications for the
 * rest of the process after a single log line. Notifications are ambient; a
 * desktop that can't show them should cost the user nothing, and a log line
 * per turn would be worse than silence.
 *
 * Threading: [notify] hands the launch to a single daemon thread and returns
 * immediately, so a caller on a coroutine dispatcher never blocks on process
 * start or on `waitFor`.
 */
class NotifySendNotifier(
    private val appName: String = DEFAULT_APP_NAME,
    /** libnotify expiry hint, milliseconds. The daemon may clamp it. */
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val log: (String) -> Unit = { println("[marmalade-desktop] $it") },
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "marmalade-notify").apply { isDaemon = true }
    },
) : Notifier, AutoCloseable {

    private enum class Backend { NotifySend, GDBus }

    /** Set on the first hard failure — see the class kdoc's failure posture. */
    private val disabled = AtomicBoolean(false)

    /** Which command to try next. Only ever moves forward, on a missing binary. */
    @Volatile
    private var backend: Backend = Backend.NotifySend

    override fun notify(title: String, body: String) {
        if (disabled.get()) return
        val safeTitle = NotificationText.forNotifySend(title, NotificationText.TITLE_MAX)
        val safeBody = NotificationText.forNotifySend(body, NotificationText.BODY_MAX)
        try {
            executor.execute { send(safeTitle, safeBody) }
        } catch (_: RejectedExecutionException) {
            // Post-[close] call — the app is shutting down, nothing to say.
        }
    }

    private fun send(title: String, body: String) {
        while (!disabled.get()) {
            val current = backend
            if (run(current, command(current, title, body))) return
        }
    }

    private fun command(backend: Backend, title: String, body: String): List<String> = when (backend) {
        Backend.NotifySend -> listOf(
            "notify-send",
            "-a", appName,
            "-t", timeoutMs.toString(),
            // Everything after `--` is positional, so a title or body that
            // happens to start with `-` can't be read as a flag.
            "--",
            title,
            body,
        )
        // org.freedesktop.Notifications.Notify(app_name, replaces_id, app_icon,
        // summary, body, actions, hints, expire_timeout).
        Backend.GDBus -> listOf(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.Notifications",
            "--object-path", "/org/freedesktop/Notifications",
            "--method", "org.freedesktop.Notifications.Notify",
            appName, "0", "", title, body, "[]", "{}", timeoutMs.toString(),
        )
    }

    /** Runs one attempt. Returns true when the notification is settled — shown,
     *  or failed in a way there is no point retrying with another backend. */
    private fun run(attempted: Backend, command: List<String>): Boolean {
        val name = command.first()
        return try {
            val process = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start()
            // Nothing to write; leaving stdin open would hold a pipe fd.
            process.outputStream.close()
            if (!process.waitFor(PROCESS_WAIT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                disable("$name did not exit within ${PROCESS_WAIT_SECONDS}s")
                return true
            }
            val exit = process.exitValue()
            if (exit != 0) disable("$name exited $exit")
            true
        } catch (e: java.io.IOException) {
            // Missing binary — try the next backend, and only give up when
            // there isn't one.
            val next = Backend.entries.getOrNull(attempted.ordinal + 1)
            if (next == null) {
                disable("$name unavailable (${e.message})")
                true
            } else {
                log("$name unavailable, falling back to ${next.name.lowercase()}")
                backend = next
                false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            disable("interrupted while running $name")
            true
        }
    }

    private fun disable(reason: String) {
        if (disabled.compareAndSet(false, true)) {
            log("WARN notifications disabled: $reason")
        }
    }

    override fun close() {
        executor.shutdown()
    }

    companion object {
        private const val DEFAULT_APP_NAME = "Marmalade"
        private const val DEFAULT_TIMEOUT_MS = 8_000
        private const val PROCESS_WAIT_SECONDS = 5L
    }
}

/**
 * Text shaping for the notification surface — the only part of this file with
 * behavior worth testing.
 *
 * Two jobs, in this order:
 *  - **Flatten and truncate.** A chat snippet is arbitrary markdown; a
 *    notification body is one short paragraph. Newlines and runs of whitespace
 *    collapse to single spaces so the popup doesn't grow a code block, and the
 *    result is cut to a budget with an ellipsis.
 *  - **Escape.** libnotify parses a small HTML-ish markup in bodies, so raw
 *    `&`/`<`/`>` from a transcript would either vanish or break the parse.
 *    Escaping AFTER truncation keeps the budget about visible characters
 *    rather than about entity length.
 */
internal object NotificationText {
    const val TITLE_MAX = 80
    const val BODY_MAX = 240

    private val WHITESPACE = Regex("\\s+")

    fun forNotifySend(raw: String, max: Int): String = escape(snippet(raw, max))

    /** Flatten to one line and cut to [max] visible characters. */
    fun snippet(raw: String, max: Int): String {
        val flat = raw.replace(WHITESPACE, " ").trim()
        if (flat.length <= max) return flat
        // Trim the trailing partial word so the ellipsis reads as elision
        // rather than as a typo.
        val cut = flat.take(max).trimEnd()
        val lastSpace = cut.lastIndexOf(' ')
        val body = if (lastSpace >= max / 2) cut.take(lastSpace) else cut
        return body.trimEnd() + "…"
    }

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
