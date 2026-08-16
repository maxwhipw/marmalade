# 0011. KMP shared library module for desktop-client reuse

Status: Accepted
Date: 2026-07-20

## Context

The desktop marmalade client (plan ratified 2026-07-20; design note kept
internally) reuses this Android client's Kotlin/Compose UI + voice
pipeline via **Compose Multiplatform Desktop**. Phase 0 proved the voice core
runs on desktop JVM (phase 0 findings kept internally);
Phase 1 is the in-place KMP conversion. Two structural questions had to be
settled before touching the shipping app's build; the plan doc named the goal
("put the core in commonMain") but didn't grapple with either, and both only
surface once you read the imports:

1. **Module shape.** This repo is deliberately single-module (see the "No
   multi-module" rule in `CLAUDE.md`, an MVP-era constraint). But Compose
   Multiplatform's whole model is a **shared library** consumed by thin
   per-platform app modules. Trying to bolt a `jvm("desktop")` target onto the
   `com.android.application` module (so the desktop app's entrypoint + jar live
   inside the Android app module) fights AGP and every KMP/CMP template.

2. **`commonMain` strictness.** Kotlin `commonMain` only sees the Kotlin *common*
   stdlib — **not `java.*`**, even when every target is JVM. `rpc/types/`
   (`MarmaladeTypes.kt`, `TerminalTypes.kt`) is pure Kotlin + kotlinx.serialization
   and drops into `commonMain` cleanly. But `rpc/JsonRpcClient` (OkHttp) and much
   of `chat/` use `java.*` (UUID, ConcurrentHashMap, SimpleDateFormat, atomics,
   Base64) + OkHttp — none of which exist in true `commonMain`.

## Decision

**Extract a single shared KMP library module, `:shared`**, with targets
`androidTarget()` + `jvm("desktop")`. The existing `:app` (Android application)
consumes it; a future desktop CMP app module will consume the same `:shared`.
This **supersedes the MVP "No multi-module" rule** for the cross-platform split
— but only to the minimum needed: **one** shared library, not a `:core:*` /
`:feature:*` proliferation.

**Source-set hierarchy inside `:shared`:**

- `commonMain` — **strictly pure Kotlin only** (Kotlin common stdlib +
  multiplatform libs like kotlinx-serialization / kotlinx-coroutines /
  kotlinx-datetime). First slice: `rpc/types/`.
- **intermediate `jvmSharedMain`** (depended on by both `androidMain` and
  `desktopMain`) — JVM-coupled shared code: `java.*`, OkHttp, etc. Because both
  desktop and Android are **JVM**, this code is shared *as-is* with no rewrite.
- `androidMain` / `desktopMain` — platform-only edges (Android `Context`,
  desktop mic/D-Bus, credential storage, notifications, DB builder).

**Do NOT** rewrite OkHttp→Ktor or purge `java.*`→kotlinx to force everything into
strict `commonMain`. The only payoff of that churn is a **non-JVM** target
(iOS/native/web), and none is planned — the desktop client is JVM (CMP Desktop),
the entire voice stack is JVM. Forcing purity would be large, risky churn on a
shipping app for zero present benefit and would violate Phase 1's "zero Android
behavior change" goal.

Package namespace is preserved on move (e.g. `app.marmalade.android.rpc.types.*`
stays), so `:app` import sites are unchanged — it just gains a `:shared`
dependency. `NodeRuntime` (the service locator, ADR 0002) stays in `:app`;
`:shared` holds platform-agnostic types + logic, so this does not re-trigger the
DI question.

## Consequences

- Minimal churn: pure slices move to `commonMain`; JVM-coupled slices move to the
  shared JVM source set untouched. No networking/serialization rewrites.
- `:app` stays `com.android.application` and keeps building an APK exactly as
  before; the migration is additive (a new module + a dependency), so
  `:app:assembleDebug :app:testDebugUnitTest` must stay green at every increment.
- Adds the `org.jetbrains.kotlin.multiplatform` plugin (and, once UI slices move,
  Compose Multiplatform 1.7.x — Kotlin 2.1.0 compatible) to the build. Increment 1
  (rpc/types) needs only KMP + kotlinx-serialization — no Compose Multiplatform yet.
- If a non-JVM target is ever wanted, the `java.*`/OkHttp purge becomes real work
  — explicitly deferred until such a target exists, and it would supersede this
  ADR's "no purge" stance rather than silently expand scope.
- Convert **incrementally, in place** (slice by slice), never big-bang; Android
  keeps shipping throughout.

## Rejected alternatives

- **Single-module KMP (jvm target inside `com.android.application`).** Keeps the
  no-multi-module rule but is non-standard, fights AGP, and awkwardly nests the
  desktop app's jar/entrypoint inside the Android app module. Rejected.
- **Strict `commonMain` for everything (OkHttp→Ktor, java.\*→kotlinx purge).**
  Maximum portability, but high-churn on a shipping app for a non-JVM target that
  doesn't exist. Rejected now; revisit only if such a target is ever added.
- **SQLDelight instead of Room.** Out of scope here; Room KMP is stable and the
  plan keeps Room (toolkit doc). Not part of this decision.
