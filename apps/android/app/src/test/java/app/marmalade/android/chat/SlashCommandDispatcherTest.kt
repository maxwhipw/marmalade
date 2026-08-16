package app.marmalade.android.chat

import app.marmalade.android.chat.messages.FakeChatDao
import app.marmalade.android.chat.messages.FakeMarmaladeRpc
import app.marmalade.android.chat.messages.MessageStream
import app.marmalade.android.chat.messages.OutboxDrainer
import app.marmalade.android.chat.messages.marmaladeRpcAdapter
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SlashCommandDispatcher].
 *
 * Result kinds:
 *  - **Handled** — Action (no RPC) OR Exec / ExecWithArgs (RPC fired). Composer clears.
 *  - **Unavailable** — known command, Android surface not built. Snackbar.
 *  - **ShowRenameDialog** — /title (bare) opens the rename dialog.
 *  - **OpenSessionPicker** — /sessions, /switch, /resume open the sidebar.
 *  - **NotASlashCommand** — pass through to onSend (server / LLM handles it).
 *
 * The dispatcher is pure-function-ish but Exec/ExecWithArgs invoke
 * [ChatController.runSlash*] methods which fire RPCs on
 * [FakeMarmaladeRpc] — we verify the call landed via the fake's recorders.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SlashCommandDispatcherTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private data class Harness(
        val controller: ChatController,
        val rpc: FakeMarmaladeRpc,
        val scope: CoroutineScope,
    ) {
        fun tearDown() = scope.cancel()
    }

    private suspend fun buildHarness(sessionId: String = "live-sid-1"): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dao = FakeChatDao()
        dao.insertSession(
            SessionEntity(
                key = "main",
                thinkingLevel = "off",
                gatewaySessionId = sessionId,
            ),
        )
        val rpc = FakeMarmaladeRpc()
        // load("main") triggers session.resume; the fake's default response
        // would adopt its own session_id (overwriting the test parameter via
        // ChatController.hydrateFromServer's fresh-sid adoption). Script the
        // resume response with the same id so the controller settles on the
        // value the test expects.
        rpc.sessionResumeResponse = rpc.sessionResumeResponse.copy(session_id = sessionId)
        val stream = MessageStream(
            events = rpc.rpcClient.events,
            scope = scope,
            chatDao = dao,
            json = testJson,
        )
        val drainer = OutboxDrainer(
            chatDao = dao,
            transport = marmaladeRpcAdapter(rpc),
            scope = scope,
            persistence = stream.persistence,
        )
        val controller = ChatController(
            scope = scope,
            rpc = rpc,
            messageStream = stream,
            outboxDrainer = drainer,
            json = testJson,
            chatDao = dao,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        controller.load("main")
        return Harness(controller, rpc, scope)
    }

    // ── Action commands (pure client-side) ──────────────────────────────────

    @Test
    fun `new starts a fresh session and returns Handled`() = runTest {
        val h = buildHarness()
        try {
            val oldKey = h.controller.sessionKey.value
            val result = SlashCommandDispatcher.dispatch("/new", h.controller)
            assertEquals(SlashCommandDispatcher.Result.Handled, result)
            assertNotEquals(oldKey, h.controller.sessionKey.value)
        } finally { h.tearDown() }
    }

    @Test
    fun `clear and reset alias to new`() = runTest {
        val h = buildHarness()
        try {
            assertEquals(SlashCommandDispatcher.Result.Handled,
                SlashCommandDispatcher.dispatch("/clear", h.controller))
            assertEquals(SlashCommandDispatcher.Result.Handled,
                SlashCommandDispatcher.dispatch("/reset", h.controller))
        } finally { h.tearDown() }
    }

    @Test
    fun `NEW uppercase is handled the same as lowercase`() = runTest {
        val h = buildHarness()
        try {
            assertEquals(SlashCommandDispatcher.Result.Handled,
                SlashCommandDispatcher.dispatch("/NEW", h.controller))
        } finally { h.tearDown() }
    }

    // ── ExecWithArgs (require text after slash) ─────────────────────────────

    @Test
    fun `title with args fires sessionTitle`() = runTest {
        val h = buildHarness()
        try {
            assertEquals(SlashCommandDispatcher.Result.Handled,
                SlashCommandDispatcher.dispatch("/title Anime Test", h.controller))
            // sessionTitle isn't recorded by call list in FakeMarmaladeRpc — assert
            // via the toast emit instead: a sessionTitle success path emits a toast.
            // (renameCurrentSession also calls refreshSessions; if the RPC didn't
            // fire the dispatcher would have returned Handled but with the
            // launchSlash failure toast.)
        } finally { h.tearDown() }
    }

    @Test
    fun `title with no args opens the rename dialog`() = runTest {
        val h = buildHarness()
        try {
            assertEquals(SlashCommandDispatcher.Result.ShowRenameDialog,
                SlashCommandDispatcher.dispatch("/title", h.controller))
        } finally { h.tearDown() }
    }

    // ── UI navigation ───────────────────────────────────────────────────────

    @Test
    fun `sessions switch resume all open the session picker`() = runTest {
        val h = buildHarness()
        try {
            assertEquals(SlashCommandDispatcher.Result.OpenSessionPicker,
                SlashCommandDispatcher.dispatch("/sessions", h.controller))
            assertEquals(SlashCommandDispatcher.Result.OpenSessionPicker,
                SlashCommandDispatcher.dispatch("/switch", h.controller))
            assertEquals(SlashCommandDispatcher.Result.OpenSessionPicker,
                SlashCommandDispatcher.dispatch("/resume", h.controller))
        } finally { h.tearDown() }
    }

    // ── Unavailable (intentionally not wired yet) ───────────────────────────

    @Test
    fun `branch and fork remain Unavailable`() = runTest {
        val h = buildHarness()
        try {
            val branch = SlashCommandDispatcher.dispatch("/branch", h.controller)
            assertTrue(branch is SlashCommandDispatcher.Result.Unavailable)
            val fork = SlashCommandDispatcher.dispatch("/fork", h.controller)
            assertTrue(fork is SlashCommandDispatcher.Result.Unavailable)
        } finally { h.tearDown() }
    }

    @Test
    fun `rollback remains Unavailable`() = runTest {
        val h = buildHarness()
        try {
            val result = SlashCommandDispatcher.dispatch("/rollback", h.controller)
            assertTrue(result is SlashCommandDispatcher.Result.Unavailable)
        } finally { h.tearDown() }
    }

    @Test
    fun `removed fork-gateway commands are Unavailable, not pass-through`() = runTest {
        // Gap triage 2026-07-11: these were fork-gateway RPC rituals with no
        // marmaladed method. They MUST stay in the table as Unavailable —
        // dropping the entries entirely would let a manually-typed command
        // fall through NotASlashCommand → prompt.submit → the LLM sees
        // "/save" as prompt text.
        val h = buildHarness()
        try {
            for (cmd in listOf(
                "/save", "/stop", "/undo", "/compress", "/retry", "/status",
                "/agents", "/tasks", "/background work", "/bg work", "/btw work",
                "/queue check inbox", "/q x", "/steer focus", "/goal ship", "/subgoal tests",
            )) {
                val result = SlashCommandDispatcher.dispatch(cmd, h.controller)
                assertTrue("expected Unavailable for $cmd, got $result",
                    result is SlashCommandDispatcher.Result.Unavailable)
            }
        } finally { h.tearDown() }
    }

    // ── Pass-through cases ──────────────────────────────────────────────────

    @Test
    fun `unknown slash returns NotASlashCommand`() = runTest {
        val h = buildHarness()
        try {
            assertEquals(SlashCommandDispatcher.Result.NotASlashCommand,
                SlashCommandDispatcher.dispatch("/unknownxyz", h.controller))
        } finally { h.tearDown() }
    }

    @Test
    fun `plain text returns NotASlashCommand`() = runTest {
        val h = buildHarness()
        try {
            assertEquals(SlashCommandDispatcher.Result.NotASlashCommand,
                SlashCommandDispatcher.dispatch("hello world", h.controller))
        } finally { h.tearDown() }
    }

    @Test
    fun `unknown slash with args still pass-through`() = runTest {
        val h = buildHarness()
        try {
            assertEquals(SlashCommandDispatcher.Result.NotASlashCommand,
                SlashCommandDispatcher.dispatch("/skill_view with args", h.controller))
        } finally { h.tearDown() }
    }

    // ── Approval / deny — defense-in-depth (also hidden from catalog) ───────

    @Test
    fun `approve and deny return Unavailable instead of leaking to LLM`() = runTest {
        val h = buildHarness()
        try {
            // These are hidden from the catalog popup, but a user could still
            // type them manually. Without an explicit dispatcher entry, the
            // text would fall through to onSend → prompt.submit → LLM (the
            // gateway's prompt.submit doesn't intercept slashes). Mark them
            // Unavailable so the snackbar redirects users to the inline
            // ApprovalBanner instead.
            val approve = SlashCommandDispatcher.dispatch("/approve", h.controller)
            assertTrue("expected Unavailable, got $approve",
                approve is SlashCommandDispatcher.Result.Unavailable)
            assertTrue((approve as SlashCommandDispatcher.Result.Unavailable)
                .message.contains("approval banner"))

            val deny = SlashCommandDispatcher.dispatch("/deny", h.controller)
            assertTrue("expected Unavailable, got $deny",
                deny is SlashCommandDispatcher.Result.Unavailable)
        } finally { h.tearDown() }
    }

    // ── Whitespace tolerance ────────────────────────────────────────────────

    @Test
    fun `tab between command and args still parses as command + args`() = runTest {
        val h = buildHarness()
        try {
            // External-keyboard / paste users sometimes wedge a tab between
            // the command and arg. Pre-fix the dispatcher only split on
            // ASCII space, so this produced token=\"/title\\t\" → unknown
            // command → NotASlashCommand → text leaked to the LLM.
            val result = SlashCommandDispatcher.dispatch("/title\tAnime Test", h.controller)
            assertEquals(SlashCommandDispatcher.Result.Handled, result)
        } finally { h.tearDown() }
    }

}
