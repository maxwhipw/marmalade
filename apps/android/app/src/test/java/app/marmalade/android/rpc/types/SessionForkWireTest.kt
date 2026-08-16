package app.marmalade.android.rpc.types

import app.marmalade.android.rpc.JsonRpcClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-conformance twin for session.fork (T2 #3): decodes daemon-shaped JSON
 * mirroring marmalade/packages/protocol/src/methods.ts SessionForkResult and
 * the session.list row's `branched_from` lineage field.
 */
class SessionForkWireTest {

    private val json = JsonRpcClient.DefaultJson

    @Test fun decodesMidPointForkWithWarning() {
        val wire = """
            {"session_id":"s_fork","forked_from":{"session_id":"s_src","message_id":"m_7"},
             "full_context":true,"warning":"file-history not copied"}
        """.trimIndent()
        val res = json.decodeFromString(SessionForkResponse.serializer(), wire)
        assertEquals("s_fork", res.session_id)
        assertEquals("s_src", res.forked_from.session_id)
        assertEquals("m_7", res.forked_from.message_id)
        assertEquals(true, res.full_context)
        assertEquals("file-history not copied", res.warning)
    }

    @Test fun decodesEndForkWithNullCutAndNoWarning() {
        // An end-of-session fork carries message_id: null and omits warning.
        val wire = """{"session_id":"s_fork","forked_from":{"session_id":"s_src","message_id":null},
            "full_context":true}"""
        val res = json.decodeFromString(SessionForkResponse.serializer(), wire)
        assertNull(res.forked_from.message_id)
        assertNull(res.warning)
    }

    @Test fun sessionListRowCarriesBranchedFrom() {
        val wire = """{"session_id":"s_fork","last_seq":3,"seen_seq":3,
            "branched_from":{"session_id":"s_src","message_id":"m_7"}}"""
        val row = json.decodeFromString(SessionListRow.serializer(), wire)
        assertEquals("s_src", row.branched_from?.session_id)
        assertEquals("m_7", row.branched_from?.message_id)
    }

    @Test fun sessionListRowWithoutBranchedFromDecodesToNull() {
        val wire = """{"session_id":"s_plain","last_seq":0,"seen_seq":0}"""
        val row = json.decodeFromString(SessionListRow.serializer(), wire)
        assertNull(row.branched_from)
    }
}
