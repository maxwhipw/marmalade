package app.marmalade.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marmalade.android.rpc.DevicePairingHost
import app.marmalade.android.rpc.MarmaladeRpc
import app.marmalade.android.rpc.SetupCode
import app.marmalade.android.rpc.types.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Claim (pair-this-phone) progress for [PairingScreen]. */
sealed class ClaimState {
    data object Idle : ClaimState()
    data object Claiming : ClaimState()
    data class Paired(val deviceId: String) : ClaimState()
    data class Error(val message: String) : ClaimState()
}

/** Paired-device roster state for [PairingScreen]. */
sealed class DevicesState {
    data object Loading : DevicesState()
    data class Loaded(val devices: List<DeviceInfo>) : DevicesState()
    data class Error(val message: String) : DevicesState()
}

/**
 * M2 device pairing (REBUILT 2026-07-12 against the marmaladed daemon —
 * pairing.claim + device.list/revoke; the previous file was fork-gateway
 * messaging-DM approval UI and is gone with it):
 *
 *  - **Claim**: a scanned/pasted `marmalade pair` setup code goes through
 *    [MarmaladeRuntime.claimPairing] — throwaway socket, pairing.claim,
 *    persist URL + device token, reconnect.
 *  - **Roster**: device.list over the LIVE connection (needs one — the
 *    roster is unavailable until the phone is paired/connected).
 *
 * Plain multiplatform [ViewModel] in `:shared`, no Hilt (repo constraint).
 * The split of its two dependencies is the point: the *roster* half is
 * ordinary RPC ([MarmaladeRpc], supplied by `LocalMarmaladeRpc`), while the
 * *claim* half and this device's identity are genuinely runtime-owned and
 * arrive through the narrow [DevicePairingHost] port. See that interface for
 * why claiming can't just be another RPC call.
 */
class PairingViewModel(
    private val rpc: MarmaladeRpc,
    private val host: DevicePairingHost,
) : ViewModel() {

    val thisDeviceId: String get() = host.deviceId

    private val _claimState = MutableStateFlow<ClaimState>(ClaimState.Idle)
    val claimState: StateFlow<ClaimState> = _claimState.asStateFlow()

    private val _devicesState = MutableStateFlow<DevicesState>(DevicesState.Loading)
    val devicesState: StateFlow<DevicesState> = _devicesState.asStateFlow()

    /** One-shot snackbar message (revoke outcomes). */
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    init {
        loadDevices()
    }

    /** Parse + claim a setup code (paste or QR). */
    fun claim(rawSetupCode: String) {
        if (_claimState.value is ClaimState.Claiming) return
        val parsed = SetupCode.parse(rawSetupCode)
        val code = parsed.getOrElse {
            _claimState.value = ClaimState.Error(it.message ?: "Invalid setup code")
            return
        }
        _claimState.value = ClaimState.Claiming
        viewModelScope.launch {
            try {
                val deviceId = host.claimPairing(code)
                _claimState.value = ClaimState.Paired(deviceId)
                loadDevices()
            } catch (t: Throwable) {
                _claimState.value = ClaimState.Error(t.message ?: "Pairing failed")
            }
        }
    }

    fun dismissClaimResult() {
        _claimState.value = ClaimState.Idle
    }

    fun loadDevices() {
        viewModelScope.launch {
            _devicesState.value = DevicesState.Loading
            _devicesState.value = try {
                DevicesState.Loaded(rpc.deviceList().devices)
            } catch (t: Throwable) {
                DevicesState.Error(t.message ?: "Could not load devices (not connected?)")
            }
        }
    }

    fun revoke(deviceId: String) {
        viewModelScope.launch {
            try {
                rpc.deviceRevoke(deviceId)
                _actionMessage.value = if (deviceId == thisDeviceId) {
                    "This phone's access was revoked — pair again to reconnect"
                } else {
                    "Revoked $deviceId"
                }
            } catch (t: Throwable) {
                _actionMessage.value = t.message ?: "Revoke failed"
            }
            loadDevices()
        }
    }

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    companion object {
        /** Factory for `viewModel(factory = PairingViewModel.factory(rpc, host))`. */
        fun factory(rpc: MarmaladeRpc, host: DevicePairingHost) = viewModelFactory {
            initializer { PairingViewModel(rpc, host) }
        }
    }
}
