package app.marmalade.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle-only transition tests for [RunStateTransitions] and
 * [RunStateMachine].
 *
 * Step D rewrite: the machine no longer carries rendering data
 * (assistantText, blocks, pendingTools) — that lives in
 * `ChatController.sessionMessages` and is exercised by
 * `ChatControllerLocalStoreTest`. These tests verify only the lifecycle
 * FSM invariants:
 *  - State transitions: Pending → Accepted → Streaming → Completed
 *  - Terminal stickiness (with the LifecycleStart-on-Errored carve-out)
 *  - Idempotent duplicate events
 *  - Effect emission (RequestHistoryRefetch, FireAbortRpc, MarkAssistant*, etc.)
 *  - "No silent drops" — every event produces a state change OR a Log effect
 */
class RunStateMachineTest {

    private val t0 = 1_000L
    private val runA = "run-a"
    private val runB = "run-b"

    // -- Send / SendAcked --------------------------------------------------

    @Test
    fun `Send on empty machine produces Pending state`() {
        val m = RunStateMachine()
        m.apply(RunEvent.Send(runA, t0))
        val state = m.states.value[runA]
        assertTrue("Expected Pending, got ${state?.let { it::class.simpleName }}", state is RunState.Pending)
        assertEquals(t0, state!!.startedAtMs)
    }

    @Test
    fun `SendAcked Pending transitions to Accepted with serverRunId`() {
        val m = RunStateMachine().also { it.apply(RunEvent.Send(runA, t0)) }
        m.apply(RunEvent.SendAcked(runId = runA, serverRunId = "svr-1", nowMs = t0 + 10))
        val state = m.states.value[runA]
        assertTrue(state is RunState.Accepted)
        assertEquals("svr-1", (state as RunState.Accepted).serverRunId)
    }

    @Test
    fun `SendFailed transitions to Errored and emits banner`() {
        val m = RunStateMachine().also { it.apply(RunEvent.Send(runA, t0)) }
        val effects = m.apply(RunEvent.SendFailed(runA, "no route", t0 + 10))
        val state = m.states.value[runA]
        assertTrue(state is RunState.Errored)
        assertTrue((state as RunState.Errored).cause is ErrorCause.SendRpcFailed)
        assertTrue(effects.any { it is RunEffect.ShowBanner })
    }

    // -- Stream events promote to Streaming -------------------------------

    @Test
    fun `AssistantText on Accepted promotes to Streaming`() {
        val m = freshAt(runA, RunState.Accepted(runA, t0, t0))
        m.apply(RunEvent.AssistantText(runA, "hello", t0 + 100))
        assertTrue(m.states.value[runA] is RunState.Streaming)
    }

    @Test
    fun `AssistantText containing marmalade_action emits DispatchMarmaladeAction effect`() {
        val m = freshAt(runA, RunState.Accepted(runA, t0, t0))
        val effects = m.apply(
            RunEvent.AssistantText(
                runA,
                """{"marmalade_action":{"action":"x"}}""",
                t0 + 10,
            ),
        )
        assertTrue(effects.any { it is RunEffect.DispatchMarmaladeAction })
    }

    @Test
    fun `ThinkingText on Accepted promotes to Streaming`() {
        val m = freshAt(runA, RunState.Accepted(runA, t0, t0))
        m.apply(RunEvent.ThinkingText(runA, "thinking", t0 + 100))
        assertTrue(m.states.value[runA] is RunState.Streaming)
    }

    @Test
    fun `ToolStart on Accepted promotes to Streaming`() {
        val m = freshAt(runA, RunState.Accepted(runA, t0, t0))
        m.apply(RunEvent.ToolStart(runA, "t1", "weather", null, t0 + 10))
        assertTrue(m.states.value[runA] is RunState.Streaming)
    }

    @Test
    fun `Stream events for unknown runId synthesize fresh Streaming`() {
        // Server-initiated runs (resumed after reconnect, ack-id remap, etc.)
        // emit stream events without a prior Send. The machine synthesizes
        // a Streaming state so subsequent events route correctly instead of
        // silently dropping.
        val m = RunStateMachine()
        m.apply(RunEvent.AssistantText(runA, "hello", t0 + 10))
        assertTrue(m.states.value[runA] is RunState.Streaming)
    }

    // -- Lifecycle=end → Completed (Step E: no more CPH limbo state) ------

    @Test
    fun `LifecycleEnd on Streaming transitions directly to Completed and emits MarkAssistantSent`() {
        val m = freshAt(runA, RunState.Accepted(runA, t0, t0))
        m.apply(RunEvent.AssistantText(runA, "hello", t0 + 10))
        val effects = m.apply(RunEvent.LifecycleEnd(runA, t0 + 50))
        assertTrue(m.states.value[runA] is RunState.Completed)
        assertTrue(effects.any { it is RunEffect.MarkAssistantSent })
        assertTrue(effects.any { it is RunEffect.NotifyMessageCompleted })
        assertTrue("no chat.history refetch on terminal", effects.none { it is RunEffect.RequestHistoryRefetch })
    }

    @Test
    fun `Duplicate LifecycleEnd is idempotent`() {
        val m = freshAt(runA, RunState.Accepted(runA, t0, t0))
        m.apply(RunEvent.LifecycleEnd(runA, t0 + 50))
        val before = m.states.value[runA]
        val effects = m.apply(RunEvent.LifecycleEnd(runA, t0 + 60))
        assertEquals("state should not change on duplicate", before, m.states.value[runA])
        assertTrue("debug-log only", effects.any { it is RunEffect.LogDebug })
    }

    // -- Lifecycle=error → Errored ----------------------------------------

    @Test
    fun `LifecycleErrorEvent transitions to Errored with banner`() {
        val m = freshAt(runA, RunState.Accepted(runA, t0, t0))
        val effects = m.apply(RunEvent.LifecycleErrorEvent(runA, "boom", t0 + 30))
        val state = m.states.value[runA]
        assertTrue(state is RunState.Errored)
        assertTrue((state as RunState.Errored).cause is ErrorCause.LifecycleError)
        assertTrue(effects.any { it is RunEffect.ShowBanner })
    }

    @Test
    fun `LifecycleStart on Errored resurrects to Streaming and clears banner`() {
        // Carve-out: gateway emits phase=error with livenessState="blocked"
        // then phase=start to resume on the same runId. Resurrect.
        val m = freshAt(
            runA,
            RunState.Errored(runA, t0, t0, t0, ErrorCause.LifecycleError("blocked")),
        )
        val effects = m.apply(RunEvent.LifecycleStart(runA, t0 + 10))
        assertTrue(m.states.value[runA] is RunState.Streaming)
        assertTrue(effects.any { it is RunEffect.ClearBanner })
    }

    // -- Chat state events ------------------------------------------------

    @Test
    fun `ChatFinal on Streaming without prior LifecycleEnd warns and transitions to Completed`() {
        val m = freshAt(runA, RunState.Streaming(runA, t0, t0))
        val effects = m.apply(RunEvent.ChatFinal(runA, t0 + 50))
        assertTrue(m.states.value[runA] is RunState.Completed)
        assertTrue(effects.any { it is RunEffect.LogWarn })
        assertTrue(effects.any { it is RunEffect.MarkAssistantSent })
    }

    @Test
    fun `ChatAborted transitions to Aborted with GatewayAborted reason and flips status`() {
        val m = freshAt(runA, RunState.Streaming(runA, t0, t0))
        val effects = m.apply(RunEvent.ChatAborted(runA, stopReason = "timeout", endedAtMs = t0 + 10))
        val state = m.states.value[runA]
        assertTrue(state is RunState.Aborted)
        assertTrue((state as RunState.Aborted).reason is AbortReason.GatewayAborted)
        assertTrue(effects.any { it is RunEffect.MarkAssistantAborted })
        assertTrue("no chat.history refetch", effects.none { it is RunEffect.RequestHistoryRefetch })
    }

    @Test
    fun `ChatError transitions to Errored and flips status to failed`() {
        val m = freshAt(runA, RunState.Streaming(runA, t0, t0))
        val effects = m.apply(RunEvent.ChatError(runA, "model failed", t0 + 10))
        assertTrue(m.states.value[runA] is RunState.Errored)
        assertTrue(effects.any { it is RunEffect.MarkAssistantFailed })
        assertFalse(effects.any { it is RunEffect.FireAbortRpc })
    }

    // -- User abort + client timeout --------------------------------------

    @Test
    fun `Abort on Streaming transitions to Aborting and fires abort RPC`() {
        // Abort is now two-phase: Streaming -> Aborting (transitional, UI
        // shows spinner) -> Aborted on chat=aborted ack (or 5s defensive
        // timeout in the controller's executor). Tests the first transition.
        val m = freshAt(runA, RunState.Streaming(runA, t0, t0))
        val effects = m.apply(RunEvent.Abort(runA, t0 + 10))
        val state = m.states.value[runA]
        assertTrue("expected Aborting, got $state", state is RunState.Aborting)
        assertTrue(effects.any { it is RunEffect.FireAbortRpc })
    }

    @Test
    fun `chat=aborted on Aborting transitions to Aborted with UserRequested reason`() {
        // The user pressed stop (Streaming -> Aborting), and the gateway
        // confirmed via chat=aborted. Preserve the user-initiated reason
        // (not GatewayAborted, which is for server-side timeouts).
        val m = freshAt(runA, RunState.Streaming(runA, t0, t0))
        m.apply(RunEvent.Abort(runA, t0 + 10))
        assertTrue(m.states.value[runA] is RunState.Aborting)
        m.apply(RunEvent.ChatAborted(runA, stopReason = null, endedAtMs = t0 + 100))
        val state = m.states.value[runA]
        assertTrue("expected Aborted, got $state", state is RunState.Aborted)
        assertEquals(AbortReason.UserRequested, (state as RunState.Aborted).reason)
    }

    @Test
    fun `lifecycle=end on Aborting transitions to Completed (race won by gateway)`() {
        // User pressed stop, but the run finished before the abort RPC
        // registered server-side. Honour the final answer.
        val m = freshAt(runA, RunState.Streaming(runA, t0, t0))
        m.apply(RunEvent.Abort(runA, t0 + 10))
        assertTrue(m.states.value[runA] is RunState.Aborting)
        val effects = m.apply(RunEvent.LifecycleEnd(runA, t0 + 50))
        assertTrue(m.states.value[runA] is RunState.Completed)
        assertTrue(effects.any { it is RunEffect.MarkAssistantSent })
    }

    @Test
    fun `duplicate Abort while Aborting is dropped`() {
        val m = freshAt(runA, RunState.Streaming(runA, t0, t0))
        m.apply(RunEvent.Abort(runA, t0 + 10))
        val before = m.states.value[runA]
        val effects = m.apply(RunEvent.Abort(runA, t0 + 20))
        val after = m.states.value[runA]
        assertEquals(before, after)
        assertTrue(effects.none { it is RunEffect.FireAbortRpc })
    }

    @Test
    fun `stream events on Aborting are debug-dropped (no resurrect to Streaming)`() {
        // Once aborting, late tokens shouldn't un-spinner the UI. The
        // bubble already has the content the user saw; further deltas
        // are discarded.
        val m = freshAt(runA, RunState.Streaming(runA, t0, t0))
        m.apply(RunEvent.Abort(runA, t0 + 10))
        assertTrue(m.states.value[runA] is RunState.Aborting)
        m.apply(RunEvent.AssistantText(runA, "late token", t0 + 20))
        assertTrue(
            "AssistantText should not resurrect Aborting → Streaming",
            m.states.value[runA] is RunState.Aborting,
        )
    }

    // -- Broadcast events --------------------------------------------------

    @Test
    fun `SeqGap broadcast transitions every non-terminal run to Errored and flips bubble`() {
        val m = RunStateMachine()
        m.apply(RunEvent.Send(runA, t0))
        val effects = m.apply(RunEvent.SeqGap(t0 + 10))
        val state = m.states.value[runA]
        assertTrue(state is RunState.Errored)
        assertEquals(ErrorCause.SeqGap, (state as RunState.Errored).cause)
        // The banner alone doesn't release the bubble — the per-message
        // status flip is what tells the user their pending response died.
        assertTrue(
            "SeqGap must emit MarkAssistantFailed",
            effects.any { it is RunEffect.MarkAssistantFailed && it.runId == runA },
        )
        assertTrue(
            "SeqGap must notify non-foreground sessions",
            effects.any { it is RunEffect.NotifyMessageCompleted && it.runId == runA },
        )
    }

    // -- Terminal stickiness invariant ------------------------------------

    @Test
    fun `Terminal states never transition to non-terminal on any event`() {
        // Carve-out: LifecycleStart on Errored is the one allowed resurrection.
        // All other (terminal, event) combinations either stay or DEBUG-drop.
        val terminals = listOf(
            RunState.Completed(runA, t0, t0, t0),
            RunState.Aborted(runA, t0, t0, t0, AbortReason.UserRequested),
            RunState.Aborted(runA, t0, t0, t0, AbortReason.GatewayAborted("timeout")),
        )
        for (terminal in terminals) {
            val m = freshAt(runA, terminal)
            m.apply(RunEvent.AssistantText(runA, "stray", t0 + 1000))
            m.apply(RunEvent.LifecycleEnd(runA, t0 + 1001))
            m.apply(RunEvent.ChatFinal(runA, t0 + 1002))
            m.apply(RunEvent.ToolStart(runA, "stray", "x", null, t0 + 1003))
            assertTrue(
                "terminal ${terminal::class.simpleName} should stay sticky",
                m.states.value[runA]?.isTerminal == true,
            )
        }
    }

    @Test
    fun `dropTerminal removes terminal run from map`() {
        val m = freshAt(runA, RunState.Completed(runA, t0, t0, t0))
        m.dropTerminal(runA)
        assertNull(m.states.value[runA])
    }

    @Test
    fun `dropTerminal does nothing for non-terminal run`() {
        val m = freshAt(runA, RunState.Streaming(runA, t0, t0))
        m.dropTerminal(runA)
        assertNotNull("non-terminal should not be removed", m.states.value[runA])
    }

    // -- "No silent drops" invariant --------------------------------------

    @Test
    fun `Event for unknown runId produces synthesized Streaming OR a Log effect`() {
        // Stream events synthesize a Streaming state for unknown runIds
        // (server-initiated runs). Other events log-and-drop.
        val m = RunStateMachine()
        m.apply(RunEvent.AssistantText("unknown-1", "x", t0))
        assertTrue("synthesizes Streaming", m.states.value["unknown-1"] is RunState.Streaming)

        val ackEffects = m.apply(RunEvent.SendAcked("unknown-2", null, t0))
        assertTrue("no state created from SendAcked-on-unknown", m.states.value["unknown-2"] == null)
        assertTrue(ackEffects.any { it is RunEffect.LogDebug })
    }

    // -- Helpers -----------------------------------------------------------

    private fun freshAt(runId: RunId, state: RunState): RunStateMachine {
        // Inject a state directly via a Send + manual transition. We can't
        // construct a machine with arbitrary state from the public API, so
        // exploit the fact that Send creates Pending and then we can replace
        // by going through a sequence — but for the test cases we want
        // arbitrary starting states (Streaming, Errored, etc.). Use the
        // package-private constructor seam: re-create via reflection-free
        // approach by going through Send for runs that need it.
        //
        // Simpler approach: directly mutate via a Send event then replace
        // the state by re-applying transitions. For Errored / Completed /
        // Streaming starting states, we step through the machine.
        val m = RunStateMachine()
        m.apply(RunEvent.Send(runId, state.startedAtMs))
        when (state) {
            is RunState.Pending -> { /* already Pending after Send */ }
            is RunState.Accepted -> m.apply(RunEvent.SendAcked(runId, null, state.lastActivityAtMs))
            is RunState.Streaming -> {
                m.apply(RunEvent.SendAcked(runId, null, state.lastActivityAtMs))
                m.apply(RunEvent.AssistantText(runId, "_seed", state.lastActivityAtMs))
            }
            is RunState.Completed -> {
                m.apply(RunEvent.SendAcked(runId, null, t0))
                // Step E: LifecycleEnd transitions directly to Completed.
                m.apply(RunEvent.LifecycleEnd(runId, state.endedAtMs))
            }
            is RunState.Errored -> {
                m.apply(RunEvent.SendAcked(runId, null, t0))
                when (state.cause) {
                    is ErrorCause.LifecycleError ->
                        m.apply(RunEvent.LifecycleErrorEvent(runId, state.cause.message, state.endedAtMs))
                    is ErrorCause.AgentError ->
                        m.apply(RunEvent.AgentError(runId, state.cause.message, state.endedAtMs))
                    is ErrorCause.ChatStateError ->
                        m.apply(RunEvent.ChatError(runId, state.cause.message, state.endedAtMs))
                    is ErrorCause.SendRpcFailed ->
                        m.apply(RunEvent.SendFailed(runId, state.cause.message, state.endedAtMs))
                    ErrorCause.SeqGap ->
                        m.apply(RunEvent.SeqGap(state.endedAtMs))
                    is ErrorCause.Unknown ->
                        m.apply(RunEvent.AgentError(runId, state.cause.message, state.endedAtMs))
                }
            }
            is RunState.Aborting -> {
                m.apply(RunEvent.SendAcked(runId, null, t0))
                m.apply(RunEvent.Abort(runId, state.abortRequestedAtMs))
            }
            is RunState.Aborted -> {
                m.apply(RunEvent.SendAcked(runId, null, t0))
                // Abort transitions to Aborting; chat=aborted lands the
                // run in terminal Aborted (mirrors the real flow).
                m.apply(RunEvent.Abort(runId, state.endedAtMs))
                m.apply(RunEvent.ChatAborted(runId, stopReason = null, endedAtMs = state.endedAtMs))
            }
        }
        return m
    }
}
