package app.marmalade.android.mic

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [MicOwnershipManager] — the request/release/forceRelease/
 * safety-net state machine that replaced the ACTION_PAUSE/RESUME_HOTWORD
 * broadcast handoff.
 *
 * Style mirrors [app.marmalade.android.service.HotwordEligibilityTest]: plain
 * JUnit4, descriptive backtick names, `org.junit.Assert.*`. Runs under
 * Robolectric only to obtain a real [Context] — the manager never touches it.
 *
 * Two seams keep this deterministic:
 *  - `internal constructor` so each test gets a fresh instance without the
 *    process-wide singleton bleeding state. Because `getInstance` is also
 *    exercised by production code paths, [resetSingleton] additionally clears
 *    the static `instance` field by reflection in [setUp].
 *  - the injectable [CoroutineScope] + `safetyNetTimeoutMs` constructor params
 *    let `runTest`'s virtual clock drive the 90s net and the 50ms restart
 *    settle delay without real sleeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MicOwnershipManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetSingleton()
    }

    /**
     * Clear the static singleton field. [MicOwnershipManager] is a process-wide
     * singleton; without this reset a prior test's `getInstance` could leak its
     * instance (and owner state) into the next test.
     */
    private fun resetSingleton() {
        val field = MicOwnershipManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun manager(scope: CoroutineScope, timeoutMs: Long = 90_000L) =
        MicOwnershipManager(context, scope = scope, safetyNetTimeoutMs = timeoutMs)

    // ── Initial state ─────────────────────────────────────────────────────

    @Test
    fun `initial owner is NONE`() = runTest {
        val mgr = manager(this)
        assertEquals(MicOwner.NONE, mgr.currentOwner.value)
    }

    // ── Acquisition ───────────────────────────────────────────────────────

    @Test
    fun `requestMic from NONE grants and sets owner`() = runTest {
        val mgr = manager(this)
        assertTrue(mgr.requestMic(MicOwner.VOICE_SESSION))
        assertEquals(MicOwner.VOICE_SESSION, mgr.currentOwner.value)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        advanceUntilIdle()
    }

    @Test
    fun `requestMic KWS from NONE grants`() = runTest {
        val mgr = manager(this)
        assertTrue(mgr.requestMic(MicOwner.KWS))
        assertEquals(MicOwner.KWS, mgr.currentOwner.value)
    }

    @Test
    fun `requestMic VOICE_SESSION when owner is KWS grants — KWS is preempted`() = runTest {
        val mgr = manager(this)
        mgr.requestMic(MicOwner.KWS)
        assertTrue(mgr.requestMic(MicOwner.VOICE_SESSION))
        assertEquals(MicOwner.VOICE_SESSION, mgr.currentOwner.value)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        advanceUntilIdle()
    }

    @Test
    fun `requestMic INLINE_STT when owner is KWS grants`() = runTest {
        val mgr = manager(this)
        mgr.requestMic(MicOwner.KWS)
        assertTrue(mgr.requestMic(MicOwner.INLINE_STT))
        assertEquals(MicOwner.INLINE_STT, mgr.currentOwner.value)
        mgr.releaseMic(MicOwner.INLINE_STT)
        advanceUntilIdle()
    }

    @Test
    fun `requestMic VOICE_SESSION when owner is INLINE_STT is denied and owner unchanged`() = runTest {
        val mgr = manager(this)
        mgr.requestMic(MicOwner.INLINE_STT)
        assertFalse(mgr.requestMic(MicOwner.VOICE_SESSION))
        assertEquals(MicOwner.INLINE_STT, mgr.currentOwner.value)
        mgr.releaseMic(MicOwner.INLINE_STT)
        advanceUntilIdle()
    }

    @Test
    fun `requestMic INLINE_STT when owner is VOICE_SESSION is denied and owner unchanged`() = runTest {
        val mgr = manager(this)
        mgr.requestMic(MicOwner.VOICE_SESSION)
        assertFalse(mgr.requestMic(MicOwner.INLINE_STT))
        assertEquals(MicOwner.VOICE_SESSION, mgr.currentOwner.value)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        advanceUntilIdle()
    }

    @Test
    fun `requestMic by current owner is idempotent — returns true and owner unchanged`() = runTest {
        val mgr = manager(this)
        mgr.requestMic(MicOwner.VOICE_SESSION)
        assertTrue(mgr.requestMic(MicOwner.VOICE_SESSION))
        assertEquals(MicOwner.VOICE_SESSION, mgr.currentOwner.value)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        advanceUntilIdle()
    }

    // ── Release ───────────────────────────────────────────────────────────

    @Test
    fun `releaseMic by current owner sets owner to NONE`() = runTest {
        val mgr = manager(this)
        mgr.requestMic(MicOwner.VOICE_SESSION)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        assertEquals(MicOwner.NONE, mgr.currentOwner.value)
        advanceUntilIdle()
    }

    @Test
    fun `releaseMic by non-owner is a no-op — owner unchanged`() = runTest {
        val mgr = manager(this)
        mgr.requestMic(MicOwner.VOICE_SESSION)
        mgr.releaseMic(MicOwner.INLINE_STT)
        assertEquals(MicOwner.VOICE_SESSION, mgr.currentOwner.value)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        advanceUntilIdle()
    }

    @Test
    fun `releaseMic when owner is NONE is a no-op`() = runTest {
        val mgr = manager(this)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        assertEquals(MicOwner.NONE, mgr.currentOwner.value)
    }

    // ── forceRelease (safety net) ─────────────────────────────────────────

    @Test
    fun `forceRelease when a non-KWS owner holds it sets owner to NONE`() = runTest {
        val mgr = manager(this)
        mgr.requestMic(MicOwner.INLINE_STT)
        mgr.forceRelease("test")
        assertEquals(MicOwner.NONE, mgr.currentOwner.value)
        advanceUntilIdle()
    }

    @Test
    fun `forceRelease when owner is NONE is a no-op`() = runTest {
        val mgr = manager(this)
        mgr.forceRelease("test")
        assertEquals(MicOwner.NONE, mgr.currentOwner.value)
    }

    @Test
    fun `forceRelease when owner is KWS is a no-op`() = runTest {
        val mgr = manager(this)
        mgr.requestMic(MicOwner.KWS)
        mgr.forceRelease("test")
        assertEquals(MicOwner.KWS, mgr.currentOwner.value)
    }

    // ── KWS-restart callback ──────────────────────────────────────────────

    @Test
    fun `setOnMicReleasedToKws callback fires after releaseMic`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mgr = manager(CoroutineScope(dispatcher))
        val fired = AtomicInteger(0)
        mgr.setOnMicReleasedToKws { fired.incrementAndGet() }

        mgr.requestMic(MicOwner.VOICE_SESSION)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        // Callback is posted with a 50ms settle delay.
        advanceTimeBy(MicOwnershipManager.KWS_RESTART_SETTLE_MS + 1)
        assertEquals(1, fired.get())
    }

    @Test
    fun `callback fires after forceRelease`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mgr = manager(CoroutineScope(dispatcher))
        val fired = AtomicInteger(0)
        mgr.setOnMicReleasedToKws { fired.incrementAndGet() }

        mgr.requestMic(MicOwner.INLINE_STT)
        mgr.forceRelease("test")
        advanceTimeBy(MicOwnershipManager.KWS_RESTART_SETTLE_MS + 1)
        assertEquals(1, fired.get())
    }

    @Test
    fun `setOnMicReleasedToKws null unregisters — no callback after release`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mgr = manager(CoroutineScope(dispatcher))
        val fired = AtomicInteger(0)
        mgr.setOnMicReleasedToKws { fired.incrementAndGet() }
        mgr.setOnMicReleasedToKws(null)

        mgr.requestMic(MicOwner.VOICE_SESSION)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        advanceTimeBy(MicOwnershipManager.KWS_RESTART_SETTLE_MS + 1)
        assertEquals(0, fired.get())
    }

    // ── Safety-net timeout ────────────────────────────────────────────────

    @Test
    fun `safety net force-releases a stuck owner after the timeout`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mgr = manager(CoroutineScope(dispatcher), timeoutMs = 90_000L)
        val fired = AtomicInteger(0)
        mgr.setOnMicReleasedToKws { fired.incrementAndGet() }

        mgr.requestMic(MicOwner.VOICE_SESSION)
        // Just before the timeout — still held.
        advanceTimeBy(89_000L)
        assertEquals(MicOwner.VOICE_SESSION, mgr.currentOwner.value)
        // Past the timeout — net fires forceRelease, KWS callback follows.
        advanceTimeBy(1_001L + MicOwnershipManager.KWS_RESTART_SETTLE_MS)
        assertEquals(MicOwner.NONE, mgr.currentOwner.value)
        assertEquals(1, fired.get())
    }

    @Test
    fun `idempotent requestMic refreshes the safety net so a healthy session is not reclaimed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mgr = manager(CoroutineScope(dispatcher), timeoutMs = 90_000L)

        mgr.requestMic(MicOwner.VOICE_SESSION)
        // A keep-alive ping just before the net would fire.
        advanceTimeBy(89_000L)
        mgr.requestMic(MicOwner.VOICE_SESSION) // refreshes the timer
        // The original 90s mark passes — still held thanks to the refresh.
        advanceTimeBy(2_000L)
        assertEquals(MicOwner.VOICE_SESSION, mgr.currentOwner.value)

        mgr.releaseMic(MicOwner.VOICE_SESSION)
        advanceUntilIdle()
    }

    @Test
    fun `releaseMic cancels the safety net so it cannot fire later`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mgr = manager(CoroutineScope(dispatcher), timeoutMs = 90_000L)
        val fired = AtomicInteger(0)
        mgr.setOnMicReleasedToKws { fired.incrementAndGet() }

        mgr.requestMic(MicOwner.VOICE_SESSION)
        mgr.releaseMic(MicOwner.VOICE_SESSION)
        advanceTimeBy(MicOwnershipManager.KWS_RESTART_SETTLE_MS + 1)
        // Exactly one callback from the explicit release.
        assertEquals(1, fired.get())
        // Long past the would-be timeout: no second (force-release) callback.
        advanceTimeBy(200_000L)
        assertEquals(1, fired.get())
        assertEquals(MicOwner.NONE, mgr.currentOwner.value)
    }
}
