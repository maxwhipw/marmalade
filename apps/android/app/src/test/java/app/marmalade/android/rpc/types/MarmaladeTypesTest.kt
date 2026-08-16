package app.marmalade.android.rpc.types

import app.marmalade.android.rpc.JsonRpcClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spot tests confirming the most-load-bearing wire types decode cleanly via
 * [JsonRpcClient.DefaultJson]. The data classes themselves are mostly
 * mechanical hand-ports of TS interfaces; the tests target the cases where
 * shape ambiguity, optional fields, or discriminated unions could break a
 * round-trip in production.
 */
class MarmaladeTypesTest {

    private val json: Json = JsonRpcClient.DefaultJson

    @Test
    fun `SessionCreateResponse decodes with optional fields omitted`() {
        val parsed = json.decodeFromString<SessionCreateResponse>("""{"session_id":"abc"}""")
        assertEquals("abc", parsed.session_id)
        assertEquals(null, parsed.info)
        assertEquals(null, parsed.messages)
    }

    @Test
    fun `SessionCreateResponse decodes with full payload`() {
        val src = """
            {"session_id":"s1","message_count":2,
             "info":{"model":"opus-4-7","provider":"anthropic","running":true},
             "messages":[
               {"role":"user","content":"hi","timestamp":1},
               {"role":"assistant","content":"hello","timestamp":2}
             ]}
        """.trimIndent()
        val parsed = json.decodeFromString<SessionCreateResponse>(src)
        assertEquals("s1", parsed.session_id)
        assertEquals(2, parsed.message_count)
        assertEquals("opus-4-7", parsed.info?.model)
        assertEquals(true, parsed.info?.running)
        assertEquals(2, parsed.messages?.size)
    }

    @Test
    fun `OAuthStartResponse discriminates on flow=pkce`() {
        val parsed = json.decodeFromString<OAuthStartResponse>(
            """{"flow":"pkce","auth_url":"https://provider/auth","expires_in":300,"session_id":"oa1"}"""
        )
        assertTrue("expected Pkce, got ${parsed::class.simpleName}", parsed is OAuthStartResponse.Pkce)
        parsed as OAuthStartResponse.Pkce
        assertEquals("https://provider/auth", parsed.auth_url)
        assertEquals(300, parsed.expires_in)
        assertEquals("oa1", parsed.session_id)
    }

    @Test
    fun `OAuthStartResponse discriminates on flow=device_code`() {
        val parsed = json.decodeFromString<OAuthStartResponse>(
            """{"flow":"device_code","expires_in":600,"poll_interval":5,
                 "session_id":"oa2","user_code":"ABCD-1234",
                 "verification_url":"https://provider/device"}"""
        )
        assertTrue(parsed is OAuthStartResponse.DeviceCode)
        parsed as OAuthStartResponse.DeviceCode
        assertEquals("ABCD-1234", parsed.user_code)
        assertEquals(5, parsed.poll_interval)
    }

    @Test
    fun `OAuthStartResponse discriminates on flow=loopback`() {
        val parsed = json.decodeFromString<OAuthStartResponse>(
            """{"flow":"loopback","auth_url":"http://127.0.0.1:8765","expires_in":120,"session_id":"oa3"}"""
        )
        assertTrue(parsed is OAuthStartResponse.Loopback)
    }

    @Test
    fun `unknown fields on server-added shape do not break decode`() {
        // Forward-compat: server adds future_field to SessionCreateResponse,
        // we still parse the known shape via ignoreUnknownKeys.
        val parsed = json.decodeFromString<SessionCreateResponse>(
            """{"session_id":"s1","future_field":42}"""
        )
        assertEquals("s1", parsed.session_id)
    }

    @Test
    fun `SessionInfo decodes the cross-profile aggregator fields`() {
        val src = """
            {"id":"sess1","input_tokens":100,"is_active":true,"last_active":1,
             "message_count":3,"output_tokens":200,"started_at":0,
             "tool_call_count":1,"profile":"default","is_default_profile":true,
             "ended_at":null,"cwd":null,"model":null,"preview":null,"source":null,"title":null}
        """.trimIndent()
        val parsed = json.decodeFromString<SessionInfo>(src)
        assertEquals("default", parsed.profile)
        assertEquals(true, parsed.is_default_profile)
        assertEquals(null, parsed.ended_at)
    }

    @Test
    fun `UsageStats decodes a partial payload as commonly emitted by session info`() {
        // The server may send UsageStats with most fields omitted on a fresh session.
        val parsed = json.decodeFromString<UsageStats>("""{"calls":0,"input":0,"output":0,"total":0}""")
        assertEquals(0, parsed.calls)
        assertEquals(null, parsed.cost_usd)
    }

    @Test
    fun `SessionListResponse decodes the marmaladed session-list rows`() {
        // Verbatim shape from marmalade daemon router.ts `session.list`:
        // rows keyed by the immutable session_id, ms timestamps, the P4
        // unread cursors, and the P2 lifecycle/run_state split.
        val src = """
            {
              "sessions": [
                {"session_id":"sess1","purpose":"main","status":"idle",
                 "lifecycle":"active","run_state":"idle","harness":"claude-code",
                 "last_active":1782710696385,"last_seq":42,"seen_seq":40,
                 "topic":"voice stack","summary":"working on TTS","summary_updated_at":1782710696000},
                {"session_id":"sess2","lifecycle":"ended","run_state":"idle",
                 "last_seq":7,"seen_seq":7}
              ]
            }
        """.trimIndent()
        val parsed = json.decodeFromString<SessionListResponse>(src)
        assertEquals(2, parsed.sessions.size)
        assertEquals("sess1", parsed.sessions[0].session_id)
        assertEquals("voice stack", parsed.sessions[0].topic)
        assertEquals(42L, parsed.sessions[0].last_seq)
        assertEquals(40L, parsed.sessions[0].seen_seq)
        assertEquals("running".equals(parsed.sessions[0].run_state), false)
        assertEquals("ended", parsed.sessions[1].lifecycle)
    }

    @Test
    fun `SessionListRow tolerates minimal rows`() {
        val parsed = json.decodeFromString<SessionListResponse>(
            """{"sessions":[{"session_id":"sess-min"}]}"""
        )
        val row = parsed.sessions.single()
        assertEquals(0L, row.last_seq)
        assertEquals(null, row.last_active)
        assertEquals(null, row.topic)
        assertEquals(null, row.model)
    }

    @Test
    fun `SessionListRow decodes the model field`() {
        val parsed = json.decodeFromString<SessionListResponse>(
            """{"sessions":[{"session_id":"sess-model","model":"claude-opus-4-8"}]}"""
        )
        assertEquals("claude-opus-4-8", parsed.sessions.single().model)
    }

    @Test
    fun `HelloResponse decodes the negotiated handshake result`() {
        val parsed = json.decodeFromString<HelloResponse>(
            """{"protocolVersion":1,"server":{"name":"marmaladed","version":"0.0.1"},
                 "principal":"owner","features":["stable-ids","subscribe"]}"""
        )
        assertEquals(1, parsed.protocolVersion)
        assertEquals("marmaladed", parsed.server?.name)
        assertTrue(parsed.features.contains("stable-ids"))
        assertTrue(parsed.features.contains("subscribe"))
    }

    @Test
    fun `PromptSubmitAck decodes the server-minted user-message identity`() {
        val parsed = json.decodeFromString<PromptSubmitAck>(
            """{"message_id":"aB3xYz12QrSt","seq":5,"ts":1782710696385}"""
        )
        assertEquals("aB3xYz12QrSt", parsed.message_id)
        assertEquals(5L, parsed.seq)
        assertEquals(1782710696385L, parsed.ts)
    }

    @Test
    fun `SessionSubscribeResponse decodes the replay attach result`() {
        val parsed = json.decodeFromString<SessionSubscribeResponse>(
            """{"session_id":"s1","replayed":12,"last_seq":57,
                 "lifecycle":"active","run_state":"running"}"""
        )
        assertEquals(12, parsed.replayed)
        assertEquals(57L, parsed.last_seq)
        assertEquals("running", parsed.run_state)
    }

    @Test
    fun `SessionInfo decodes float epoch-second timestamps without crashing`() {
        // #17 audit: the gateway emits started_at / last_active / ended_at as
        // float epoch SECONDS (time.time()). These were typed Long, so any
        // fractional value threw and rejected the whole session.info payload.
        val src = """
            {"id":"s1","is_active":true,
             "started_at":1782710696.3850453,"last_active":1782710800.5,
             "ended_at":1782710900.25,"input_tokens":10,"output_tokens":20,
             "message_count":3,"tool_call_count":1}
        """.trimIndent()
        val info = json.decodeFromString<SessionInfo>(src)
        assertEquals(1782710696385, info.startedAtMs)
        assertEquals(1782710800500, info.lastActiveMs)
        assertEquals(1782710900250L, info.endedAtMs)
    }

    @Test
    fun `SessionInfo tolerates omitted token and count fields`() {
        // Defaulting the required numeric fields means one missing field can't
        // reject the entire response (the #17 failure mode).
        val info = json.decodeFromString<SessionInfo>("""{"id":"s2","is_active":false}""")
        assertEquals(0, info.input_tokens)
        assertEquals(0, info.message_count)
        assertEquals(0L, info.startedAtMs)
        assertEquals(null, info.endedAtMs)
    }

    @Test
    fun `SessionSearchResult decodes float session_started`() {
        val r = json.decodeFromString<SessionSearchResult>(
            """{"session_id":"s3","snippet":"hi","session_started":1782710696.38}"""
        )
        assertEquals(1782710696.38, r.session_started!!, 1e-9)
    }

    @Test
    fun `SessionResumeResponse decodes the marmaladed shape - just the same id back`() {
        // Resume never re-mints: ids are names, not state. History rides
        // session.subscribe replay, not this response.
        val parsed = json.decodeFromString<SessionResumeResponse>("""{"session_id":"s9"}""")
        assertEquals("s9", parsed.session_id)
    }

    @Test
    fun `ModelListResponse decodes the marmaladed model menu`() {
        // Replaces the fork's ModelOptionProvider (providers/pricing/
        // capabilities) — the daemon's model.list is just ids + labels.
        val parsed = json.decodeFromString<ModelListResponse>(
            """{"models":[{"id":"claude-opus-4-8","label":"Opus 4.8"},{"id":"claude-haiku-4-5","label":"Haiku 4.5"}]}"""
        )
        assertEquals(2, parsed.models.size)
        assertEquals("claude-opus-4-8", parsed.models[0].id)
        assertEquals("Opus 4.8", parsed.models[0].label)
        // Absent daemon-owned defaults → null (old daemons parse fine).
        assertNull(parsed.default_model)
        assertNull(parsed.default_effort)
    }

    @Test
    fun `ModelListResponse decodes the daemon-owned new-session defaults`() {
        val parsed = json.decodeFromString<ModelListResponse>(
            """{"models":[{"id":"claude-opus-4-8","label":"Opus 4.8"}],"default_model":"claude-opus-4-8","default_effort":"high"}"""
        )
        assertEquals("claude-opus-4-8", parsed.default_model)
        assertEquals("high", parsed.default_effort)
    }

    @Test
    fun `SearchMessagesResponse decodes the daemon's search page`() {
        // Wire truth: marmalade/packages/protocol/src/methods.ts
        // SearchMessagesResult. The markers around the matched span are
        // U+E000/U+E001 and must survive decoding intact — SnippetMarkers, not
        // the deserializer, is what strips them.
        val parsed = json.decodeFromString<SearchMessagesResponse>(
            """{"total":23,"hits":[{"session_id":"s1","message_id":"m1","seq":48,
               "role":"user","ts":1753000000000,
               "snippet":"merge the \uE000seen_at\uE001 stamp",
               "text":"merge the seen_at stamp monotonically",
               "reply_text":"Done \u2014 merged monotonically."}],
               "sessions":{"s1":{"title":"Fix unread badge","workspace_id":"ws-client",
               "archived":false,"last_active":1753000100000}}}"""
        )
        assertEquals(23, parsed.total)
        val hit = parsed.hits.single()
        assertEquals(48L, hit.seq)
        assertEquals("user", hit.role)
        assertTrue(hit.snippet.contains('\uE000'))
        assertEquals("Done \u2014 merged monotonically.", hit.reply_text)
        assertEquals("ws-client", parsed.sessions["s1"]?.workspace_id)
    }

    @Test
    fun `SearchHit tolerates an absent reply_text and a quick-chat session`() {
        // reply_text is absent on assistant hits and on a user turn with no
        // answer yet; workspace_id null is the daemon matcher saying "quick chat"
        // — NOT a missing field the client should go re-derive from a path.
        val parsed = json.decodeFromString<SearchMessagesResponse>(
            """{"total":1,"hits":[{"session_id":"s2","message_id":"m9","seq":7,
               "role":"assistant","ts":1753000000000,"snippet":"no markers","text":"x"}],
               "sessions":{"s2":{"title":null,"workspace_id":null,"archived":true,
               "last_active":1753000000000}}}"""
        )
        assertNull(parsed.hits.single().reply_text)
        assertNull(parsed.sessions["s2"]?.workspace_id)
        assertNull(parsed.sessions["s2"]?.title)
        assertEquals(true, parsed.sessions["s2"]?.archived)
    }

    @Test
    fun `SearchMessagesResponse decodes an empty page`() {
        val parsed = json.decodeFromString<SearchMessagesResponse>("""{"total":0}""")
        assertEquals(0, parsed.total)
        assertTrue(parsed.hits.isEmpty())
        assertTrue(parsed.sessions.isEmpty())
    }

    @Test
    fun `an absent corpus means the LIVE session — the openable one`() {
        // Every session entry the daemon sent before the archive existed omits
        // this field, and every one of them is openable. Defaulting the other
        // way would make the whole live corpus look read-only.
        val parsed = json.decodeFromString<SearchMessagesResponse>(
            """{"total":1,"hits":[{"session_id":"s1","message_id":"m1","seq":3,
               "role":"user","ts":1753000000000,"snippet":"x","text":"x"}],
               "sessions":{"s1":{"title":"t","workspace_id":null,"archived":false,
               "last_active":1753000000000}}}"""
        )
        assertNull(parsed.sessions["s1"]?.corpus)
        assertFalse(parsed.sessions["s1"]!!.isArchive)
    }

    @Test
    fun `an archive session entry is marked and carries no reply_text`() {
        // Wire truth: router.ts case "search.messages" archive branch — it
        // stamps corpus:"archive" and deliberately omits reply_text, because
        // reply lookup is live-corpus machinery keyed on the daemon's own
        // message index, which knows nothing of these ids.
        val parsed = json.decodeFromString<SearchMessagesResponse>(
            """{"total":4,"hits":[{"session_id":"b3f1c2de-0000-4aaa-9999-1234567890ab",
               "message_id":"am7","seq":211,"role":"user","ts":1699000000000,
               "snippet":"the wake word loop","text":"the wake word loop"}],
               "sessions":{"b3f1c2de-0000-4aaa-9999-1234567890ab":{"title":null,
               "workspace_id":"ws-client","archived":false,"last_active":1699000000000,
               "corpus":"archive"}}}"""
        )
        val info = parsed.sessions["b3f1c2de-0000-4aaa-9999-1234567890ab"]!!
        assertEquals(SearchCorpus.ARCHIVE, info.corpus)
        assertTrue(info.isArchive)
        assertNull(parsed.hits.single().reply_text)
        // seq IS the archive ordinal — the paging key for search.archive.
        assertEquals(211L, parsed.hits.single().seq)
    }

    @Test
    fun `SearchArchiveResponse decodes a transcript page`() {
        // Wire truth: methods.ts SearchArchiveResult.
        val parsed = json.decodeFromString<SearchArchiveResponse>(
            """{"session":{"title":"Wake word spike","cwd":"/home/user/coding/marmalade",
               "last_active":1699000000000,"message_count":412},
               "total":412,"messages":[
                 {"ordinal":0,"role":"user","ts":1699000000000,"text":"start here"},
                 {"ordinal":1,"role":"assistant","ts":1699000001000,"text":"on it"}]}"""
        )
        assertEquals(412, parsed.total)
        assertEquals("/home/user/coding/marmalade", parsed.session.cwd)
        assertEquals(412, parsed.session.message_count)
        assertEquals(listOf(0, 1), parsed.messages.map { it.ordinal })
        assertEquals("assistant", parsed.messages[1].role)
    }

    @Test
    fun `SearchArchiveResponse tolerates an untitled session and an empty page`() {
        // No title is NORMAL in this corpus — most pre-daemon sessions were
        // never named, which is why the viewer falls back to the uuid and cwd.
        val parsed = json.decodeFromString<SearchArchiveResponse>(
            """{"session":{"title":null,"cwd":"/tmp/x","last_active":1,"message_count":0},
               "total":0,"messages":[]}"""
        )
        assertNull(parsed.session.title)
        assertTrue(parsed.messages.isEmpty())
        assertEquals(0, parsed.total)
    }

    @Test
    fun `SessionMessage tolerates unknown content shapes`() {
        // content can be a string, array, or object — keep it as raw JsonElement
        // so the chat-messages layer can do its own coercion.
        val parsed = json.decodeFromString<SessionMessage>(
            """{"role":"assistant","content":[{"type":"text","text":"hi"}],"timestamp":1}"""
        )
        assertEquals("assistant", parsed.role)
        // content stays as raw JsonElement
        assertTrue(parsed.content != null)
    }
}
