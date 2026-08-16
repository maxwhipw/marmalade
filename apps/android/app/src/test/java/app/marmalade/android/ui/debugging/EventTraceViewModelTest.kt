package app.marmalade.android.ui.debugging

import app.marmalade.android.chat.messages.FakeChatDao
import app.marmalade.android.data.local.entity.GatewayEventEntity
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [EventTraceViewModel] after its move to `:shared` (desktop-client plan
 * Phase 1, option (b)).
 *
 * The pure helpers were already covered by [EventTraceLogicTest]; these cover
 * the reactive wiring that needed an `Application` before the move and so had
 * no coverage at all — the session/type filters composing over the Room flows,
 * and the two different DAO queries the session filter selects between.
 *
 * Both exposed flows are `WhileSubscribed(5_000)` `stateIn`s, so each test
 * subscribes from [backgroundScope] before asserting: an unsubscribed flow
 * never runs its upstream, and `backgroundScope` collectors are cancelled for
 * us at test end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventTraceViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun event(type: String, sessionKey: String?, at: Long) = GatewayEventEntity(
        sessionKey = sessionKey,
        type = type,
        payloadJson = "null",
        receivedAtMs = at,
    )

    @Test
    fun `events are unfiltered by default, newest first`() = runTest(dispatcher) {
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "s1"))
        dao.insertGatewayEvent(event("tool.call", "s1", 100))
        dao.insertGatewayEvent(event("message.delta", "s1", 200))

        val vm = EventTraceViewModel(dao)
        backgroundScope.launch { vm.events.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("message.delta", "tool.call"), vm.events.value.map { it.type })
        assertNull(vm.sessionFilter.value)
        assertEquals("", vm.typeFilter.value)
    }

    @Test
    fun `type filter is a case-insensitive substring match`() = runTest(dispatcher) {
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "s1"))
        dao.insertGatewayEvent(event("tool.call", "s1", 100))
        dao.insertGatewayEvent(event("message.delta", "s1", 200))

        val vm = EventTraceViewModel(dao)
        backgroundScope.launch { vm.events.collect {} }
        advanceUntilIdle()

        vm.setTypeFilter("TOOL")
        advanceUntilIdle()
        assertEquals(listOf("tool.call"), vm.events.value.map { it.type })

        // Clearing the filter restores the full window.
        vm.setTypeFilter("")
        advanceUntilIdle()
        assertEquals(2, vm.events.value.size)
    }

    @Test
    fun `session filter switches to the per-session query`() = runTest(dispatcher) {
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "s1"))
        dao.insertSession(SessionEntity(key = "s2"))
        dao.insertGatewayEvent(event("tool.call", "s1", 100))
        dao.insertGatewayEvent(event("message.delta", "s2", 200))

        val vm = EventTraceViewModel(dao)
        backgroundScope.launch { vm.events.collect {} }
        advanceUntilIdle()
        assertEquals(2, vm.events.value.size)

        vm.setSessionFilter("s2")
        advanceUntilIdle()
        assertEquals(listOf("message.delta"), vm.events.value.map { it.type })

        // null re-selects the all-sessions query rather than filtering to
        // events whose sessionKey is literally null.
        vm.setSessionFilter(null)
        advanceUntilIdle()
        assertEquals(2, vm.events.value.size)
    }

    @Test
    fun `session and type filters compose`() = runTest(dispatcher) {
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "s1"))
        dao.insertGatewayEvent(event("tool.call", "s1", 100))
        dao.insertGatewayEvent(event("tool.result", "s1", 150))
        dao.insertGatewayEvent(event("message.delta", "s1", 200))

        val vm = EventTraceViewModel(dao)
        backgroundScope.launch { vm.events.collect {} }
        advanceUntilIdle()

        vm.setSessionFilter("s1")
        vm.setTypeFilter("tool")
        advanceUntilIdle()

        assertEquals(listOf("tool.result", "tool.call"), vm.events.value.map { it.type })
    }

    @Test
    fun `sessions exposes the dropdown rows`() = runTest(dispatcher) {
        val dao = FakeChatDao()
        dao.insertSession(SessionEntity(key = "s1"))
        dao.insertSession(SessionEntity(key = "s2"))

        val vm = EventTraceViewModel(dao)
        backgroundScope.launch { vm.sessions.collect {} }
        advanceUntilIdle()

        assertEquals(setOf("s1", "s2"), vm.sessions.value.map { it.key }.toSet())
    }
}
