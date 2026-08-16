package app.marmalade.android.utils

/**
 * Utility for classifying gateway session keys into display groups.
 *
 * Key structure: colon-separated segments. Classification rules:
 * 1. ":cron:" anywhere → Scheduled Tasks
 * 2. ":channel:" with preceding platform → platform group (Mattermost, Discord, etc.)
 * 3. Direct top-level prefix (telegram:, discord:, etc.) → platform group
 * 4. agent:<name>:<id> → Gateway (user-created sessions)
 * 5. Everything else → Other
 */
object SessionKeyUtils {

    const val GROUP_GATEWAY = "Gateway"
    const val GROUP_CRON = "Scheduled Tasks"
    const val GROUP_SUBAGENT = "Subagents"
    const val GROUP_DISCORD = "Discord"
    const val GROUP_SLACK = "Slack"
    const val GROUP_TELEGRAM = "Telegram"
    const val GROUP_MATTERMOST = "Mattermost"
    const val GROUP_OTHER = "Other"

    val CHANNEL_TAB_ORDER = listOf("Gateway", "Subagents", "Cron", "Discord", "Slack", "Telegram", "Mattermost", "Other")

    private val CHANNEL_PLATFORMS = mapOf(
        "mattermost" to GROUP_MATTERMOST,
        "discord" to GROUP_DISCORD,
        "slack" to GROUP_SLACK,
        "telegram" to GROUP_TELEGRAM,
    )

    fun classifySessionKey(key: String): String {
        val segments = key.split(":")

        // Rule 1: cron anywhere → Scheduled Tasks
        if (segments.contains("cron")) return GROUP_CRON

        // Rule 2: known platform name anywhere in segments → platform group
        // Catches both "agent:main:mattermost:channel:id" and
        // "agent:main:mattermost:direct:id" and "mattermost:something"
        for (segment in segments) {
            val group = CHANNEL_PLATFORMS[segment]
            if (group != null) return group
        }

        // Rule 3: direct top-level prefixes (redundant safety net)
        if (key.startsWith("telegram:")) return GROUP_TELEGRAM
        if (key.startsWith("discord:")) return GROUP_DISCORD
        if (key.startsWith("mattermost:")) return GROUP_MATTERMOST
        if (key.startsWith("slack:")) return GROUP_SLACK

        // Rule 4: subagent sessions → Subagents
        if (segments.contains("subagent")) return GROUP_SUBAGENT

        // Rule 5: agent sessions → Gateway (exclude channel/bridge sessions)
        if (key.startsWith("agent:") && !segments.contains("channel")) return GROUP_GATEWAY

        return GROUP_OTHER
    }

    fun extractAgentId(key: String): String? {
        if (!key.startsWith("agent:")) return null
        val parts = key.removePrefix("agent:").split(":")
        return parts.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    fun extractParentAgent(key: String): String? {
        val segments = key.split(":")
        val subIdx = segments.indexOf("subagent")
        if (subIdx < 1) return null
        return segments.subList(0, subIdx).joinToString(":")
    }

    /**
     * True if [eventKey] refers to the same logical session as [subscriptionKey].
     *
     * Exact match always wins. Otherwise we check each colon-segment of
     * [eventKey]: a segment-equals match accepts the gateway's canonical
     * form (`agent:<id>:<name>`) when we subscribed to the bare name,
     * without the false positives of a substring / endsWith check
     * (`mainland` is not `main`, `agent:x:mainframe` is not `main`).
     *
     * Matches OpenCami's `eventSegments.includes(subKey)` pattern (see their
     * `gateway.ts` `sessionListeners` routing).
     */
    fun matchesSubscription(eventKey: String, subscriptionKey: String): Boolean {
        if (subscriptionKey.isEmpty() || eventKey.isEmpty()) return false
        if (eventKey == subscriptionKey) return true
        return eventKey.split(":").any { it == subscriptionKey }
    }

    /**
     * True when the user is allowed to delete [key].
     *
     * Everything is deletable EXCEPT the literal system sentinels
     * (`main`/`global`) and bridge-owned sessions the app doesn't manage
     * (channels, cron, subagents). Post-K1 the gateway keys user sessions
     * by bare `stored_session_id` (e.g. `20260629_180856_779e02`) with no
     * `agent:` prefix — those classify as [GROUP_OTHER] and stay deletable.
     *
     * NOTE: the gateway's *most-recent* session id is deliberately NOT
     * excluded. Upstream (desktop command-center) lets you delete any
     * session including the active/most-recent one; ChatController.deleteSession
     * moves focus to `main` when the bound session is deleted. An earlier
     * `key == mainKey` guard here suppressed Delete on the newest session —
     * which sorts to the top of the list and is the first row a user
     * long-presses — so Delete appeared to be missing entirely.
     */
    fun isDeletable(key: String): Boolean {
        if (key == "global" || key == "main") return false
        return when (classifySessionKey(key)) {
            GROUP_CRON, GROUP_SUBAGENT, GROUP_DISCORD,
            GROUP_SLACK, GROUP_TELEGRAM, GROUP_MATTERMOST -> false
            else -> true // GROUP_GATEWAY + GROUP_OTHER (post-K1 stored ids)
        }
    }
}
