package app.marmalade.android.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * The daemon's M2 pairing setup code — base64url(JSON {url, token,
 * expires_at_ms}), minted by `pairing.start` (`marmalade pair` renders it as
 * a terminal QR / paste string). Wire truth: marmalade repo
 * `packages/daemon/src/pairing.ts` `encodeSetupCode` (OpenClaw setup-code
 * payload shape, MIT — see daemon CREDITS.md).
 *
 * The [token] is a single-use 10-minute bootstrap token: the phone redeems
 * it via `pairing.claim` on an unauthenticated socket to [url] and receives
 * the durable per-device bearer token.
 */
@Serializable
data class SetupCode(
    val url: String,
    val token: String,
    val expires_at_ms: Long,
) {
    /** True when the bootstrap token's TTL has already passed. */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expires_at_ms

    /**
     * The dashboard base URL to persist in prefs — `ws://host:port/api/ws` →
     * `http://host:port` (the runtime's buildWsUrl re-derives the WS form).
     */
    fun dashboardHttpUrl(): String {
        val scheme = if (url.startsWith("wss://")) "https://" else "http://"
        val authority = url
            .removePrefix("wss://")
            .removePrefix("ws://")
            .substringBefore('/')
        return "$scheme$authority"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse a scanned/pasted setup code; failure carries a human-readable
         *  message. Tolerates surrounding whitespace. */
        fun parse(raw: String): Result<SetupCode> = runCatching {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "Setup code is empty" }
            val decoded = try {
                // java.util.Base64 (minSdk 31 ≥ API 26) keeps this JVM-testable.
                String(Base64.getUrlDecoder().decode(trimmed), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Not a Marmalade setup code")
            }
            val code = try {
                json.decodeFromString(serializer(), decoded)
            } catch (_: Exception) {
                throw IllegalArgumentException("Not a Marmalade setup code")
            }
            require(code.url.startsWith("ws://") || code.url.startsWith("wss://")) {
                "Setup code has no gateway URL"
            }
            require(code.token.isNotBlank()) { "Setup code has no pairing token" }
            code
        }
    }
}
