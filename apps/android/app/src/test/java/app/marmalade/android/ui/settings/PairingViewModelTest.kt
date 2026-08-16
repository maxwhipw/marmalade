package app.marmalade.android.ui.settings

import app.marmalade.android.rpc.DevicePairingHost
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.SetupCode
import app.marmalade.android.rpc.types.DeviceInfo
import app.marmalade.android.rpc.types.DeviceListResponse
import app.marmalade.android.rpc.types.DeviceRevokeResponse
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PairingViewModel] after its move to `:shared` (desktop-client plan Phase 1,
 * option (b)).
 *
 * This one moved behind a two-member [DevicePairingHost] port rather than
 * straight across, so the tests exist mostly to pin that split: the roster
 * half must go to [MarmaladeRpc], the claim half to the host. None of this was
 * reachable before the move — the VM needed a real `MarmaladeApplication`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun device(id: String, connected: Boolean = false) =
        DeviceInfo(device_id = id, paired = true, connected = connected)

    /**
     * A wire-form setup code that is valid and unexpired, so `claim` gets past
     * [SetupCode.parse] and reaches the host. Encoded the way the daemon does
     * it (base64url of the JSON) rather than hand-written, so this stays
     * honest if the payload shape moves.
     */
    private fun encodedSetupCode(): String {
        val code = SetupCode(
            url = "ws://localhost:9119/api/ws",
            token = "t0ken",
            expires_at_ms = Long.MAX_VALUE,
        )
        val jsonText = Json.encodeToString(SetupCode.serializer(), code)
        return Base64.getUrlEncoder().encodeToString(jsonText.toByteArray(Charsets.UTF_8))
    }

    private open class FakeRpc(
        private val devices: List<DeviceInfo> = emptyList(),
        private val listError: Throwable? = null,
    ) : MarmaladeRpc(StubJsonRpcClient) {
        val revoked = mutableListOf<String>()
        var listCalls = 0

        override suspend fun deviceList(): DeviceListResponse {
            listCalls++
            listError?.let { throw it }
            return DeviceListResponse(devices)
        }

        override suspend fun deviceRevoke(deviceId: String): DeviceRevokeResponse {
            revoked += deviceId
            return DeviceRevokeResponse(revoked = true)
        }
    }

    private class FakeHost(
        override val deviceId: String = "this-device",
        val result: Result<String> = Result.success("claimed-device"),
        /** When set, `claimPairing` suspends on it — lets a test observe the
         *  in-flight Claiming state. Awaiting a never-completed deferred is the
         *  safe way to hold a call open; looping on yield() spins the test
         *  scheduler forever. */
        val gate: CompletableDeferred<Unit>? = null,
    ) : DevicePairingHost {
        var claims = 0
        override suspend fun claimPairing(setup: SetupCode): String {
            claims++
            gate?.await()
            return result.getOrThrow()
        }
    }

    // ----- roster (the MarmaladeRpc half) -------------------------------------

    @Test
    fun `init loads the device roster`() = runTest(dispatcher) {
        val rpc = FakeRpc(listOf(device("a"), device("b", connected = true)))
        val vm = PairingViewModel(rpc, FakeHost())
        advanceUntilIdle()

        val state = vm.devicesState.value as DevicesState.Loaded
        assertEquals(listOf("a", "b"), state.devices.map { it.device_id })
    }

    @Test
    fun `a failing device_list surfaces as an error state, not a crash`() = runTest(dispatcher) {
        val vm = PairingViewModel(FakeRpc(listError = IllegalStateException("not connected")), FakeHost())
        advanceUntilIdle()

        assertEquals("not connected", (vm.devicesState.value as DevicesState.Error).message)
    }

    @Test
    fun `revoke calls the rpc and refreshes the roster`() = runTest(dispatcher) {
        val rpc = FakeRpc(listOf(device("a")))
        val vm = PairingViewModel(rpc, FakeHost())
        advanceUntilIdle()
        val listCallsAfterInit = rpc.listCalls

        vm.revoke("a")
        advanceUntilIdle()

        assertEquals(listOf("a"), rpc.revoked)
        assertEquals(listCallsAfterInit + 1, rpc.listCalls)
        assertEquals("Revoked a", vm.actionMessage.value)
    }

    @Test
    fun `revoking this device says so explicitly`() = runTest(dispatcher) {
        val vm = PairingViewModel(FakeRpc(), FakeHost(deviceId = "this-device"))
        advanceUntilIdle()

        vm.revoke("this-device")
        advanceUntilIdle()

        assertTrue(vm.actionMessage.value!!.startsWith("This phone's access was revoked"))
        vm.consumeActionMessage()
        assertNull(vm.actionMessage.value)
    }

    // ----- claim (the DevicePairingHost half) ---------------------------------

    @Test
    fun `an unparseable setup code fails without reaching the host`() = runTest(dispatcher) {
        val host = FakeHost()
        val vm = PairingViewModel(FakeRpc(), host)
        advanceUntilIdle()

        vm.claim("this is not a setup code")
        advanceUntilIdle()

        assertTrue(vm.claimState.value is ClaimState.Error)
        assertEquals(0, host.claims)
    }

    @Test
    fun `a successful claim reports the daemon's device id and reloads the roster`() = runTest(dispatcher) {
        val rpc = FakeRpc(listOf(device("a")))
        val host = FakeHost(result = Result.success("bound-id"))
        val vm = PairingViewModel(rpc, host)
        advanceUntilIdle()
        val listCallsAfterInit = rpc.listCalls

        vm.claim(encodedSetupCode())
        advanceUntilIdle()

        assertEquals("bound-id", (vm.claimState.value as ClaimState.Paired).deviceId)
        assertEquals(1, host.claims)
        assertEquals(listCallsAfterInit + 1, rpc.listCalls)
    }

    @Test
    fun `a failing claim surfaces the host's message`() = runTest(dispatcher) {
        val host = FakeHost(result = Result.failure(IllegalStateException("Setup code expired")))
        val vm = PairingViewModel(FakeRpc(), host)
        advanceUntilIdle()

        vm.claim(encodedSetupCode())
        advanceUntilIdle()

        assertEquals("Setup code expired", (vm.claimState.value as ClaimState.Error).message)
        vm.dismissClaimResult()
        assertTrue(vm.claimState.value is ClaimState.Idle)
    }

    @Test
    fun `a second claim while one is in flight is ignored`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val host = FakeHost(gate = gate)
        val vm = PairingViewModel(FakeRpc(), host)
        advanceUntilIdle()

        vm.claim(encodedSetupCode())
        advanceUntilIdle()
        assertTrue(vm.claimState.value is ClaimState.Claiming)

        // Second tap while Claiming — must not start another claim.
        vm.claim(encodedSetupCode())
        advanceUntilIdle()
        assertEquals(1, host.claims)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.claimState.value is ClaimState.Paired)
    }
}
