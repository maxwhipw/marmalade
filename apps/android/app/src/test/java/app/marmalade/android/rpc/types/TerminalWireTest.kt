package app.marmalade.android.rpc.types

import app.marmalade.android.rpc.JsonRpcClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-conformance twin for the terminal.* surface: decodes daemon-shaped JSON
 * mirroring marmalade/packages/protocol/src/methods.ts (TerminalInfoWire /
 * create / attach / close / list) + events.ts (TerminalDataPayload /
 * TerminalExitPayload). Guards the field names/shapes this client depends on.
 */
class TerminalWireTest {

    private val json = JsonRpcClient.DefaultJson

    @Test fun decodesCreateResult() {
        val wire = """
            {"terminal":{"terminal_id":"t_1","shell":"bash","cwd":"/home/user",
             "cols":80,"rows":24,"pid":4242,"created_at":1721000000000,
             "last_active":1721000000500}}
        """.trimIndent()
        val res = json.decodeFromString(TerminalCreateResponse.serializer(), wire)
        assertEquals("t_1", res.terminal.terminal_id)
        assertEquals("bash", res.terminal.shell)
        assertEquals("/home/user", res.terminal.cwd)
        assertEquals(4242, res.terminal.pid)
        assertEquals(1721000000500L, res.terminal.last_active)
    }

    @Test fun decodesAttachResultWithSnapshot() {
        val wire = """
            {"terminal":{"terminal_id":"t_1","shell":"bash","cwd":"/home/user",
             "cols":100,"rows":30,"pid":7,"created_at":1,"last_active":2},
             "snapshot_b64":"aGVsbG8="}
        """.trimIndent()
        val res = json.decodeFromString(TerminalAttachResponse.serializer(), wire)
        assertEquals("aGVsbG8=", res.snapshot_b64)
        assertEquals(100, res.terminal.cols)
    }

    @Test fun decodesListResult() {
        val wire = """{"terminals":[
            {"terminal_id":"t_1","shell":"bash","cwd":"/a","cols":80,"rows":24,"pid":1,"created_at":1,"last_active":1},
            {"terminal_id":"t_2","shell":"zsh","cwd":"/b","cols":80,"rows":24,"pid":2,"created_at":2,"last_active":2}]}"""
        val res = json.decodeFromString(TerminalListResponse.serializer(), wire)
        assertEquals(2, res.terminals.size)
        assertEquals("zsh", res.terminals[1].shell)
    }

    @Test fun decodesWorkspaceStamp() {
        // workspace_id: server-derived from cwd (deepest workspace prefix
        // wins), null for quick terminals, ABSENT from an older daemon —
        // all three must decode, absent as null.
        val wire = """{"terminals":[
            {"terminal_id":"t_1","shell":"bash","cwd":"/a","cols":80,"rows":24,"pid":1,"created_at":1,"last_active":1,"workspace_id":"w_1"},
            {"terminal_id":"t_2","shell":"bash","cwd":"/b","cols":80,"rows":24,"pid":2,"created_at":2,"last_active":2,"workspace_id":null},
            {"terminal_id":"t_3","shell":"bash","cwd":"/c","cols":80,"rows":24,"pid":3,"created_at":3,"last_active":3}]}"""
        val res = json.decodeFromString(TerminalListResponse.serializer(), wire)
        assertEquals("w_1", res.terminals[0].workspace_id)
        assertNull(res.terminals[1].workspace_id)
        assertNull(res.terminals[2].workspace_id)
    }

    @Test fun decodesEmptyListRoster() {
        val res = json.decodeFromString(TerminalListResponse.serializer(), """{"terminals":[]}""")
        assertEquals(0, res.terminals.size)
    }

    @Test fun decodesCloseResult() {
        val res = json.decodeFromString(TerminalCloseResponse.serializer(), """{"closed":true}""")
        assertEquals(true, res.closed)
    }

    @Test fun decodesDataPayload() {
        val p = json.decodeFromString(
            TerminalDataPayload.serializer(),
            """{"terminal_id":"t_1","data_b64":"JCA="}""",
        )
        assertEquals("t_1", p.terminal_id)
        assertEquals("JCA=", p.data_b64)
    }

    @Test fun decodesExitPayloadWithCodeAndWithNull() {
        val exited = json.decodeFromString(
            TerminalExitPayload.serializer(),
            """{"terminal_id":"t_1","exit_code":0}""",
        )
        assertEquals(0, exited.exit_code)
        // Died to a signal (or terminal.close) → exit_code is null.
        val signalled = json.decodeFromString(
            TerminalExitPayload.serializer(),
            """{"terminal_id":"t_1","exit_code":null}""",
        )
        assertNull(signalled.exit_code)
    }
}
