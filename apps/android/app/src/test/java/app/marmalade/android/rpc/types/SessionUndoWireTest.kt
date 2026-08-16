package app.marmalade.android.rpc.types

import app.marmalade.android.rpc.JsonRpcClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-conformance twin for session.undo (T2 #6): decodes daemon-shaped JSON
 * mirroring marmalade/packages/protocol/src/methods.ts SessionUndoResult.
 */
class SessionUndoWireTest {

    private val json = JsonRpcClient.DefaultJson

    @Test fun decodesUndoResultWithPoppedIds() {
        val wire = """
            {"last_message_id":"m_a1","popped_message_ids":["m_u2","m_a2"],"files_rewound":false}
        """.trimIndent()
        val res = json.decodeFromString(SessionUndoResponse.serializer(), wire)
        assertEquals("m_a1", res.last_message_id)
        assertEquals(listOf("m_u2", "m_a2"), res.popped_message_ids)
        // v1 is conversation-only — always false (file edits are not reverted).
        assertFalse(res.files_rewound)
    }

    @Test fun decodesFirstTurnUndoWithNullTip() {
        // First-turn undo empties the session: the new tip is null.
        val wire = """{"last_message_id":null,"popped_message_ids":["m_u1","m_a1"],"files_rewound":false}"""
        val res = json.decodeFromString(SessionUndoResponse.serializer(), wire)
        assertNull(res.last_message_id)
        assertEquals(2, res.popped_message_ids.size)
    }

    @Test fun minimalResultDecodesToLenientDefaults() {
        val res = json.decodeFromString(SessionUndoResponse.serializer(), "{}")
        assertNull(res.last_message_id)
        assertEquals(emptyList<String>(), res.popped_message_ids)
        assertFalse(res.files_rewound)
    }
}
