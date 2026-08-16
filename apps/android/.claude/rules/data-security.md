---
paths:
  - "app/src/main/java/app/marmalade/android/data/security/**/*.kt"
  - "app/src/main/java/app/marmalade/android/data/identity/**/*.kt"
  - "app/src/main/java/app/marmalade/android/security/**/*.kt"
---

# Data & security rules

Gotchas for crypto, device identity, and secure storage.
Background: ADR 0007 (Tink + BouncyCastle both required).

## Ed25519: two libraries, two roles

- **Tink** handles **key generation and signing** with hardware-backed
  Android Keystore where available. Don't replace Tink with
  BouncyCastle for signing — you'll lose the Keystore integration and
  weaken the threat model.
- **Bouncy Castle** handles **signature verification**. Tink doesn't
  expose Ed25519 verification cleanly at our version on Android. Don't
  remove BouncyCastle thinking it's redundant — verification will break.
- See ADR 0007 for the full reasoning if a future "simplification" is
  tempting.

## No Firebase

- The fork base used `FirebaseCrashlytics.getInstance().recordException(e)`
  in crypto error paths. **Those calls have been stripped.** Marmalade
  does not depend on Firebase for any reason.
- If you find a Firebase import in `DeviceIdentity.kt` or related crypto
  paths, it's drift — remove it. Don't add new Firebase calls.

## Keyset persistence

- Tink keysets persist via `AndroidKeysetManager` keyed by the
  application's `SharedPreferences`. Don't write keys to other
  preference stores.
- Don't log or surface the private key material (no `Log.d` of
  `KeysetHandle` or its internals — Tink's `toString` is safe; raw key
  bytes are not).
