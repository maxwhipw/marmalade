package app.marmalade.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActionDispatcherTest {

    @Test
    fun parseReturnsNullForPlainText() {
        assertNull(parseMarmaladeAction("Hello, how can I help you today?"))
    }

    @Test
    fun parseReturnsNullForEmptyString() {
        assertNull(parseMarmaladeAction(""))
    }

    @Test
    fun parseReturnsNullWhenKeyAbsent() {
        assertNull(parseMarmaladeAction("""{"other_key": {"action": "app.launch"}}"""))
    }

    @Test
    fun parseExtractsAppLaunchAction() {
        val json = """
            {
              "marmalade_action": {
                "action": "app.launch",
                "package": "com.google.android.youtube",
                "params": {},
                "displayText": "Opening YouTube"
              }
            }
        """.trimIndent()
        val result = parseMarmaladeAction(json)
        assertNotNull(result)
        assertEquals("app.launch", result!!.action)
        assertEquals("com.google.android.youtube", result.packageName)
        assertEquals("Opening YouTube", result.displayText)
    }

    @Test
    fun parseExtractsTimerAction() {
        val json = """
            {
              "marmalade_action": {
                "action": "device.timer",
                "params": {"duration_seconds": "300"},
                "displayText": "Setting a 5-minute timer"
              }
            }
        """.trimIndent()
        val result = parseMarmaladeAction(json)
        assertNotNull(result)
        assertEquals("device.timer", result!!.action)
        assertEquals("300", result.params["duration_seconds"])
    }

    @Test
    fun parseExtractsAlarmAction() {
        val json = """
            {
              "marmalade_action": {
                "action": "device.alarm",
                "params": {"hour": "7", "minute": "30"},
                "displayText": "Setting alarm for 7:30"
              }
            }
        """.trimIndent()
        val result = parseMarmaladeAction(json)
        assertNotNull(result)
        assertEquals("device.alarm", result!!.action)
        assertEquals("7", result.params["hour"])
        assertEquals("30", result.params["minute"])
    }

    @Test
    fun parseExtractsWebSearchAction() {
        val json = """
            {
              "marmalade_action": {
                "action": "web.search",
                "params": {"query": "Kotlin coroutines tutorial"},
                "displayText": "Searching the web"
              }
            }
        """.trimIndent()
        val result = parseMarmaladeAction(json)
        assertNotNull(result)
        assertEquals("web.search", result!!.action)
        assertEquals("Kotlin coroutines tutorial", result.params["query"])
    }

    @Test
    fun parseExtractsActionEmbeddedInProseText() {
        val text = """
            Sure! I'll open that for you now.
            {
              "marmalade_action": {
                "action": "app.launch",
                "package": "com.spotify.music",
                "params": {},
                "displayText": "Opening Spotify"
              }
            }
            Let me know if you need anything else.
        """.trimIndent()
        val result = parseMarmaladeAction(text)
        assertNotNull(result)
        assertEquals("app.launch", result!!.action)
        assertEquals("com.spotify.music", result.packageName)
    }

    @Test
    fun parseHandlesMalformedJsonGracefully() {
        val text = """{ "marmalade_action": { "action": "app.launch" BROKEN JSON }"""
        // Should not throw — returns null
        assertNull(parseMarmaladeAction(text))
    }

    @Test
    fun parseExtractsTextAnswerAction() {
        val json = """
            {
              "marmalade_action": {
                "action": "text.answer",
                "displayText": "The capital of France is Paris."
              }
            }
        """.trimIndent()
        val result = parseMarmaladeAction(json)
        assertNotNull(result)
        assertEquals("text.answer", result!!.action)
        assertNull(result.packageName)
    }

    @Test
    fun parseExtractsActionFromJsonCodeFence() {
        // Canonical MCP wrapper the gateway emits: a prose preamble, a ```json fenced
        // block containing the marmalade_action envelope, and a trailing line.
        val text = """
            Sure, I'll set that timer for you.

            ```json
            {
              "marmalade_action": {
                "action": "device.timer",
                "params": {"duration_seconds": "300"},
                "displayText": "Setting a 5-minute timer"
              }
            }
            ```

            Let me know if you need anything else.
        """.trimIndent()
        val result = parseMarmaladeAction(text)
        assertNotNull(result)
        assertEquals("device.timer", result!!.action)
        assertEquals("300", result.params["duration_seconds"])
    }

    @Test
    fun parseExtractsFirstActionIgnoresSubsequent() {
        // Two embedded marmalade_action blocks. Parser must return the FIRST only.
        // No list extraction is introduced in Phase 9.
        val text = """
            First I'll set a timer:
            {
              "marmalade_action": {
                "action": "device.timer",
                "params": {"duration_seconds": "300"},
                "displayText": "Setting a 5-minute timer"
              }
            }
            And then I'll open YouTube:
            {
              "marmalade_action": {
                "action": "app.launch",
                "package": "com.google.android.youtube",
                "params": {},
                "displayText": "Opening YouTube"
              }
            }
        """.trimIndent()
        val result = parseMarmaladeAction(text)
        assertNotNull(result)
        // The FIRST block in the text is the timer — confirm we don't pick app.launch.
        assertEquals("device.timer", result!!.action)
        assertEquals("300", result.params["duration_seconds"])
    }

    @Test
    fun parseHandlesMessageParam() {
        // Locks the contract that the parser surfaces params["message"] downstream.
        // Task 2 (ActionDispatcher) consumes this via AlarmClock.EXTRA_MESSAGE.
        val json = """
            {
              "marmalade_action": {
                "action": "device.timer",
                "params": {"duration_seconds": "300", "message": "Coffee"},
                "displayText": "Setting a 5-minute Coffee timer"
              }
            }
        """.trimIndent()
        val result = parseMarmaladeAction(json)
        assertNotNull(result)
        assertEquals("device.timer", result!!.action)
        assertEquals("300", result.params["duration_seconds"])
        assertEquals("Coffee", result.params["message"])
    }
}
