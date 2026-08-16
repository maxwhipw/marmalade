package app.marmalade.android.rpc

/**
 * The two pairing facts a host runtime owns and a shared ViewModel must not
 * reach for itself: this device's stable identity, and the act of claiming a
 * setup code.
 *
 * Both are genuinely platform-bound. The device id is persisted per-platform
 * (Android: `DeviceIdentity` over the Keystore-backed Ed25519 key), and
 * claiming runs a throwaway socket, persists URL + device token to platform
 * storage, and reconnects the live client — a runtime lifecycle operation, not
 * a plain RPC. So they stay in the host and arrive through this port, the same
 * way [MarmaladeRpc] arrives through `LocalMarmaladeRpc`.
 *
 * Deliberately narrow: the *roster* half of pairing (device.list / revoke) is
 * ordinary RPC and does NOT belong here — PairingViewModel takes a
 * [MarmaladeRpc] for that. Keeping this to two members is what stops it
 * becoming a mirror of the whole runtime.
 *
 * Android's `MarmaladeRuntime` already declared both with these exact
 * signatures, so it satisfies this port by adding the supertype alone.
 */
interface DevicePairingHost {

    /** Stable local device identifier. */
    val deviceId: String

    /**
     * Claim a parsed `marmalade pair` setup code: pairing.claim over a
     * throwaway connection, persist the URL + returned device token, reconnect.
     *
     * @return the verified device id the daemon bound the token to.
     * @throws Exception transport/claim failure (message is user-renderable).
     */
    suspend fun claimPairing(setup: SetupCode): String
}
