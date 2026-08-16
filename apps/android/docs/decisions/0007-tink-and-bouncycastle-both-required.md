# 0007. Tink for signing, BouncyCastle for verification (both required)

Status: Accepted
Date: 2026-04-24 (recording phase 01 decision)

## Context

The OpenClaw protocol uses Ed25519 device identity for pairing and
gateway authentication. The Android client needs to:
1. **Generate** an Ed25519 keypair, store the private key in
   hardware-backed Android Keystore where available
2. **Sign** outgoing protocol messages
3. **Verify** Ed25519 signatures on incoming messages

The codebase carries both Google Tink and Bouncy Castle as dependencies.
A reader could reasonably look at this and wonder why both — they
appear redundant.

## Decision

**Keep both.** They are not interchangeable.

- **Tink** handles **key generation and signing** with
  hardware-backed Android Keystore integration. Tink's API is the right
  abstraction for "generate this keypair, store it securely, sign with
  the private key" on Android.
- **Bouncy Castle** handles **signature verification**. Tink does not
  expose Ed25519 verification cleanly in the Android distribution at
  the version we're using.

Specifically: `DeviceIdentity.kt` uses Tink for `KeysetHandle.generateNew(...)`
and signing operations; verification of inbound signatures uses Bouncy
Castle's `Ed25519Signer` / `Ed25519PublicKeyParameters`.

## Consequences

- Two crypto dependencies in the build instead of one — small APK size
  cost
- A future reader (human or agent) might try to "simplify" by removing
  one — this ADR is the durable record of why that breaks signing or
  verification
- If Tink ever exposes clean Android Ed25519 verification, the
  BouncyCastle dependency could be removed via a new ADR superseding
  this one
- Note: the fork base used `FirebaseCrashlytics` for crypto error
  reporting; that has been **stripped out**. Marmalade does not depend
  on Firebase for any reason. Don't reintroduce Firebase calls in
  `DeviceIdentity.kt` or related crypto paths.

## Rejected alternatives

- **Tink only.** Verification path doesn't work cleanly at our Tink
  version on Android. Tested; rejected.
- **BouncyCastle only.** Loses Android Keystore hardware integration on
  the signing side; private keys end up in software-only storage,
  which weakens the threat model.
- **conscrypt or platform `java.security`.** Less consistent across
  Android API levels and harder to test against.
