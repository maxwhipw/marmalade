package app.marmalade.android.rpc

import app.marmalade.android.rpc.types.DaemonMcpListResponse
import app.marmalade.android.rpc.types.DaemonPluginsListResponse
import app.marmalade.android.rpc.types.DaemonToggleResponse
import app.marmalade.android.rpc.types.toMcpServerInfo
import app.marmalade.android.rpc.types.toPluginInfo
import app.marmalade.android.ui.settings.pluginEnabled
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fork-rest-triage Part E: the daemon's mcp.list / plugins.list /
 * *.toggle wire shapes (marmalade/packages/protocol/src/methods.ts) decode
 * and map onto the fork-era UI shapes the settings screens consume.
 */
class McpPluginsRpcMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `mcp_list daemon shape maps to McpServerInfo`() {
        val raw = """{"servers":[{"name":"qmd","transport":"stdio","enabled":true,"harness":"claude-code"},
            {"name":"old","transport":"http","enabled":false,"harness":"claude-code"}]}"""
        val resp = json.decodeFromString(DaemonMcpListResponse.serializer(), raw)
        val infos = resp.servers.map { it.toMcpServerInfo() }
        assertEquals("qmd", infos[0].name)
        assertTrue(infos[0].enabled)
        assertEquals("stdio", infos[0].transport)
        assertFalse(infos[1].enabled)
        assertEquals(null, infos[0].url)
    }

    @Test
    fun `plugins_list daemon shape maps to PluginInfo with a status the screen understands`() {
        val raw = """{"plugins":[{"name":"code-review@official","enabled":true,"harness":"claude-code"},
            {"name":"playwright@official","enabled":false,"harness":"claude-code"}]}"""
        val resp = json.decodeFromString(DaemonPluginsListResponse.serializer(), raw)
        val infos = resp.plugins.map { it.toPluginInfo() }
        assertTrue(pluginEnabled(infos[0].status))
        assertFalse(pluginEnabled(infos[1].status))
        assertEquals("claude-code", infos[0].source)
    }

    @Test
    fun `mcp_list carries endpoint detail (command_url) through to McpServerInfo`() {
        val raw = """{"servers":[
            {"name":"qmd","transport":"stdio","enabled":true,"harness":"claude-code","command":"qmd mcp"},
            {"name":"remote","transport":"http","enabled":true,"harness":"claude-code","url":"https://x/mcp"}]}"""
        val resp = json.decodeFromString(DaemonMcpListResponse.serializer(), raw)
        val infos = resp.servers.map { it.toMcpServerInfo() }
        assertEquals("qmd mcp", infos[0].command)
        assertEquals(null, infos[0].url)
        assertEquals("https://x/mcp", infos[1].url)
        assertEquals(null, infos[0].tools) // daemon never contacts the server
    }

    @Test
    fun `plugins_list enriched shape maps source, version, and description`() {
        val raw = """{"plugins":[{"name":"code-review@official","enabled":true,"harness":"claude-code",
            "source":"official","version":"1.2.0","description":"Review a pull request"}]}"""
        val resp = json.decodeFromString(DaemonPluginsListResponse.serializer(), raw)
        val info = resp.plugins.map { it.toPluginInfo() }.first()
        assertEquals("official", info.source)
        assertEquals("1.2.0", info.version)
        assertEquals("Review a pull request", info.description)
    }

    @Test
    fun `toggle result decodes the next_session semantics`() {
        val r = json.decodeFromString(DaemonToggleResponse.serializer(), """{"applied":true,"effective":"next_session"}""")
        assertTrue(r.applied)
        assertEquals("next_session", r.effective)
    }
}
