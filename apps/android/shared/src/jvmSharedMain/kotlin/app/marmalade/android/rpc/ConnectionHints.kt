package app.marmalade.android.rpc

/**
 * Connection UX guidance (hardening plan #3): a phone pointed at
 * `127.0.0.1`/`localhost` is pointed at ITSELF — the classic first-run
 * mistake after copying the daemon's default loopback URL. Detect it and
 * say what actually works instead of letting the generic connect-failed
 * toast do the explaining.
 */
object ConnectionHints {

    // 10.0.2.2 (emulator host alias) is deliberately NOT here — it's the
    // correct answer on an emulator.
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    /** The URL's host, with scheme/port/path stripped. */
    fun host(url: String): String {
        val afterScheme = url.trim().substringAfter("://", url.trim())
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        // Bracketed IPv6 keeps its brackets; otherwise strip the port.
        return if (authority.startsWith("[")) authority.substringBefore("]") + "]"
        else authority.substringBefore(':')
    }

    /**
     * Non-null guidance when [url] targets loopback (which on a phone is the
     * phone). Null for anything else — including blank URLs.
     */
    fun localhostGuidance(url: String): String? {
        if (url.isBlank()) return null
        return if (host(url).lowercase() in LOOPBACK_HOSTS) {
            "This URL points at the phone itself, not your computer. Use the " +
                "daemon's tailnet address (e.g. http://192.0.2.10:9130), or for " +
                "USB debugging keep 127.0.0.1 and run: adb reverse tcp:9130 tcp:9130"
        } else {
            null
        }
    }
}
