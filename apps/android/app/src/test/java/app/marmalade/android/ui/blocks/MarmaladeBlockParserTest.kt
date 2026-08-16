package app.marmalade.android.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarmaladeBlockParserTest {

    // -- parseMarmaladeBlock tests --

    @Test
    fun `parseMarmaladeBlock with valid confirm JSON returns MarmaladeBlock with type confirm`() {
        val json = """
            {
                "type": "confirm",
                "blockId": "deploy-1",
                "title": "Deploy Confirmation",
                "data": {
                    "message": "Deploy to production?",
                    "confirmLabel": "Yes, deploy",
                    "cancelLabel": "Cancel"
                }
            }
        """.trimIndent()

        val result = MarmaladeBlockParser.parseMarmaladeBlock(json)
        assertNotNull("Should parse valid confirm JSON", result)
        assertEquals("confirm", result!!.type)
        assertEquals("deploy-1", result.blockId)
        assertEquals("Deploy Confirmation", result.title)

        val data = MarmaladeBlockParser.parseBlockData(result)
        assertNotNull("Should parse confirm data", data)
        assertTrue("Should be ConfirmData", data is ConfirmData)
        val confirmData = data as ConfirmData
        assertEquals("Deploy to production?", confirmData.message)
        assertEquals("Yes, deploy", confirmData.confirmLabel)
        assertEquals("Cancel", confirmData.cancelLabel)
    }

    @Test
    fun `parseMarmaladeBlock with valid select JSON returns MarmaladeBlock with type select`() {
        val json = """
            {
                "type": "select",
                "blockId": "model-pick",
                "data": {
                    "message": "Choose a model:",
                    "options": [
                        {"id": "gpt-4", "label": "GPT-4"},
                        {"id": "claude-3", "label": "Claude 3"}
                    ]
                }
            }
        """.trimIndent()

        val result = MarmaladeBlockParser.parseMarmaladeBlock(json)
        assertNotNull("Should parse valid select JSON", result)
        assertEquals("select", result!!.type)

        val data = MarmaladeBlockParser.parseBlockData(result)
        assertNotNull("Should parse select data", data)
        assertTrue("Should be SelectData", data is SelectData)
        val selectData = data as SelectData
        assertEquals("Choose a model:", selectData.message)
        assertEquals(2, selectData.options.size)
        assertEquals("gpt-4", selectData.options[0].id)
        assertEquals("GPT-4", selectData.options[0].label)
        assertEquals("claude-3", selectData.options[1].id)
    }

    @Test
    fun `parseMarmaladeBlock with valid multiselect JSON returns correct data`() {
        val json = """
            {
                "type": "multiselect",
                "data": {
                    "message": "Select features:",
                    "options": [
                        {"id": "dark-mode", "label": "Dark Mode"},
                        {"id": "notifications", "label": "Notifications"},
                        {"id": "offline", "label": "Offline Mode"}
                    ],
                    "submitLabel": "Apply"
                }
            }
        """.trimIndent()

        val result = MarmaladeBlockParser.parseMarmaladeBlock(json)
        assertNotNull(result)
        assertEquals("multiselect", result!!.type)

        val data = MarmaladeBlockParser.parseBlockData(result)
        assertTrue(data is MultiselectData)
        val msData = data as MultiselectData
        assertEquals("Select features:", msData.message)
        assertEquals(3, msData.options.size)
        assertEquals("Apply", msData.submitLabel)
    }

    @Test
    fun `parseMarmaladeBlock with valid action JSON returns correct data`() {
        val json = """
            {
                "type": "action",
                "data": {
                    "actions": [
                        {"id": "open-yt", "label": "Open YouTube", "icon": "play_arrow"},
                        {"id": "open-spotify", "label": "Open Spotify"}
                    ]
                }
            }
        """.trimIndent()

        val result = MarmaladeBlockParser.parseMarmaladeBlock(json)
        assertNotNull(result)
        assertEquals("action", result!!.type)

        val data = MarmaladeBlockParser.parseBlockData(result)
        assertTrue(data is ActionData)
        val actionData = data as ActionData
        assertEquals(2, actionData.actions.size)
        assertEquals("open-yt", actionData.actions[0].id)
        assertEquals("Open YouTube", actionData.actions[0].label)
        assertEquals("play_arrow", actionData.actions[0].icon)
        assertNull(actionData.actions[1].icon)
    }

    @Test
    fun `parseMarmaladeBlock with valid status JSON returns correct data`() {
        val json = """
            {
                "type": "status",
                "blockId": "deploy-status",
                "data": {
                    "message": "Deploying...",
                    "progress": 0.45,
                    "state": "running"
                }
            }
        """.trimIndent()

        val result = MarmaladeBlockParser.parseMarmaladeBlock(json)
        assertNotNull(result)
        assertEquals("status", result!!.type)
        assertEquals("deploy-status", result.blockId)

        val data = MarmaladeBlockParser.parseBlockData(result)
        assertTrue(data is StatusData)
        val statusData = data as StatusData
        assertEquals("Deploying...", statusData.message)
        assertEquals(0.45f, statusData.progress!!, 0.01f)
        assertEquals("running", statusData.state)
    }

    @Test
    fun `parseMarmaladeBlock with invalid JSON returns null`() {
        val result = MarmaladeBlockParser.parseMarmaladeBlock("this is not json at all")
        assertNull("Invalid JSON should return null", result)
    }

    @Test
    fun `parseMarmaladeBlock with empty string returns null`() {
        val result = MarmaladeBlockParser.parseMarmaladeBlock("")
        assertNull("Empty string should return null", result)
    }

    @Test
    fun `parseMarmaladeBlock with unknown type returns MarmaladeBlock with raw data`() {
        val json = """
            {
                "type": "custom_widget",
                "data": {
                    "foo": "bar"
                }
            }
        """.trimIndent()

        val result = MarmaladeBlockParser.parseMarmaladeBlock(json)
        assertNotNull("Unknown type should still parse", result)
        assertEquals("custom_widget", result!!.type)

        val data = MarmaladeBlockParser.parseBlockData(result)
        assertNull("Unknown type should return null from parseBlockData", data)
    }

    // -- formatBlockResponse tests --

    @Test
    fun `formatBlockResponse for confirm produces correct marmalade-response block`() {
        val response = MarmaladeBlockParser.formatBlockResponse(
            blockId = "deploy-1",
            type = "confirm",
            response = "confirmed",
        )

        assertTrue("Should contain marmalade-response fence", response.contains("```marmalade-response"))
        assertTrue("Should contain closing fence", response.trimEnd().endsWith("```"))
        assertTrue("Should contain blockId", response.contains("\"blockId\""))
        assertTrue("Should contain deploy-1", response.contains("deploy-1"))
        assertTrue("Should contain type confirm", response.contains("\"type\""))
        assertTrue("Should contain confirmed response", response.contains("confirmed"))
    }

    @Test
    fun `formatBlockResponse for select with option id produces correct response`() {
        val response = MarmaladeBlockParser.formatBlockResponse(
            blockId = "model-pick",
            type = "select",
            response = "gpt-4",
        )

        assertTrue("Should contain marmalade-response fence", response.contains("```marmalade-response"))
        assertTrue("Should contain model-pick", response.contains("model-pick"))
        assertTrue("Should contain gpt-4 response", response.contains("gpt-4"))
    }

    @Test
    fun `formatBlockResponse for multiselect with list of ids produces correct response`() {
        val response = MarmaladeBlockParser.formatBlockResponse(
            blockId = "features",
            type = "multiselect",
            response = listOf("dark-mode", "offline"),
        )

        assertTrue("Should contain marmalade-response fence", response.contains("```marmalade-response"))
        assertTrue("Should contain features blockId", response.contains("features"))
        assertTrue("Should contain dark-mode", response.contains("dark-mode"))
        assertTrue("Should contain offline", response.contains("offline"))
    }

    @Test
    fun `formatBlockResponse with null blockId still produces valid response`() {
        val response = MarmaladeBlockParser.formatBlockResponse(
            blockId = null,
            type = "confirm",
            response = "cancelled",
        )

        assertTrue("Should contain marmalade-response fence", response.contains("```marmalade-response"))
        assertTrue("Should contain cancelled response", response.contains("cancelled"))
    }

    @Test
    fun `parseMarmaladeBlock with status null progress parses correctly`() {
        val json = """
            {
                "type": "status",
                "blockId": "task-1",
                "data": {
                    "message": "Complete!",
                    "state": "complete"
                }
            }
        """.trimIndent()

        val result = MarmaladeBlockParser.parseMarmaladeBlock(json)
        assertNotNull(result)

        val data = MarmaladeBlockParser.parseBlockData(result!!)
        assertTrue(data is StatusData)
        val statusData = data as StatusData
        assertEquals("Complete!", statusData.message)
        assertNull(statusData.progress)
        assertEquals("complete", statusData.state)
    }

    // -- ERRV13-03 regression tests: graceful fallback for malformed marmalade JSON --

    @Test
    fun `parseMarmaladeBlock with malformed top-level JSON returns null (regression for ERRV13-03)`() {
        // Realistic agent-emitted broken payload: unterminated data object.
        // Two renderer paths (streaming + final) rely on this null to fall back
        // to a styled code block rather than crashing or rendering an empty card.
        val malformed = """
            {"type":"confirm","blockId":"deploy-1","data":{
        """.trimIndent()

        val result = MarmaladeBlockParser.parseMarmaladeBlock(malformed)
        assertNull(
            "Malformed top-level JSON must return null so callers can fall back to ChatCodeBlock",
            result,
        )

        // Additional realistic broken shapes — trailing garbage and unescaped quote.
        val trailingGarbage = """{"type":"confirm","data":{}}garbage"""
        assertNull(
            "Trailing garbage after valid object must still return null",
            MarmaladeBlockParser.parseMarmaladeBlock(trailingGarbage),
        )

        val unescapedQuote = """{"type":"confirm","data":{"message":"He said "hi""}}"""
        assertNull(
            "Unescaped inner quote must return null",
            MarmaladeBlockParser.parseMarmaladeBlock(unescapedQuote),
        )
    }

    @Test
    fun `parseMarmaladeBlock with missing required data field returns a block but parseBlockData returns null`() {
        // Lenient shell parse succeeds — the top-level {type, data:{}} shape is valid —
        // but the inner confirm data is missing required fields (message, confirmLabel,
        // cancelLabel). parseBlockData must swallow the throw and return null so the
        // renderer can route to MarmaladeBlockRenderer.RawJsonFallback.
        val json = """
            {
                "type": "confirm",
                "blockId": "deploy-1",
                "data": {}
            }
        """.trimIndent()

        val result = MarmaladeBlockParser.parseMarmaladeBlock(json)
        assertNotNull("Lenient shell parse should succeed on valid top-level shape", result)
        assertEquals("confirm", result!!.type)

        val data = MarmaladeBlockParser.parseBlockData(result)
        assertNull(
            "parseBlockData must return null when required inner fields are missing",
            data,
        )
    }
}
