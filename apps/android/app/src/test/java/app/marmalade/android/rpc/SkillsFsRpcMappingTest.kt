package app.marmalade.android.rpc

import app.marmalade.android.rpc.types.DaemonFsListResponse
import app.marmalade.android.rpc.types.SkillsListResponse
import app.marmalade.android.rpc.types.toForkShape
import app.marmalade.android.rpc.types.toSkillInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fork-rest-triage Part C: the daemon's skills.list / fs.list wire shapes
 * (marmalade/packages/protocol/src/methods.ts) decode into the client types
 * and map onto the fork-era UI shapes the screens still consume. The JSON
 * fixtures below are the daemon's EXACT result shapes — if the daemon
 * changes shape, this fails before the settings screen crashes on-device.
 */
class SkillsFsRpcMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `skills_list daemon shape decodes and maps to SkillInfo`() {
        // Exact daemon shape: description optional, harnesses present.
        val raw = """
            {"skills":[
              {"name":"agent-wiki","description":"Read and write the wiki","enabled":true,"harnesses":["claude-code"]},
              {"name":"undescribed","enabled":false,"harnesses":[]}
            ]}
        """.trimIndent()
        val resp = json.decodeFromString(SkillsListResponse.serializer(), raw)
        val infos = resp.skills.map { it.toSkillInfo() }
        assertEquals(2, infos.size)
        assertEquals("agent-wiki", infos[0].name)
        assertTrue(infos[0].enabled)
        assertEquals("Read and write the wiki", infos[0].description)
        // Missing description maps to empty string (SkillInfo.description is non-null).
        assertEquals("", infos[1].description)
        assertEquals(null, infos[0].category)
    }

    @Test
    fun `fs_list daemon shape maps entry paths from the resolved base`() {
        val raw = """{"path":"/home/user/coding","entries":[{"name":"project","dir":true},{"name":"readme.txt","dir":false}]}"""
        val resp = json.decodeFromString(DaemonFsListResponse.serializer(), raw)
        val fork = resp.toForkShape()
        assertEquals("/home/user/coding/project", fork.entries[0].path)
        assertTrue(fork.entries[0].isDirectory)
        assertEquals("/home/user/coding/readme.txt", fork.entries[1].path)
        assertTrue(!fork.entries[1].isDirectory)
        assertEquals(null, fork.error)
    }

    @Test
    fun `fs_list at filesystem root does not double the slash`() {
        val raw = """{"path":"/","entries":[{"name":"home","dir":true}]}"""
        val fork = json.decodeFromString(DaemonFsListResponse.serializer(), raw).toForkShape()
        assertEquals("/home", fork.entries[0].path)
    }
}
