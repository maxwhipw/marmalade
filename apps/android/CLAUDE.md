# Marmalade Android client

The Android client for the Marmalade orchestrator daemon. It lives in this
monorepo alongside the daemon it talks to.

## Build

Binary assets are not committed. Fetch them once before the first build:

```
MARMALADE_ASSETS_BASE_URL=<release-assets-url> ./scripts/fetch-assets.sh
```

One source must be named explicitly — there is no default. See the header of
that script for the two modes (download via `MARMALADE_ASSETS_BASE_URL`, or
copy from a local directory via `MARMALADE_ASSETS_DIR`).
`assets-manifest.json` records the path, size and sha256 of every asset it
restores.

You also need an `sdk.dir` line in `local.properties` pointing at your
Android SDK (this file is machine-specific and gitignored).

Then, from this directory:

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

## Wire protocol

The daemon owns the protocol; this client mirrors it. The authoritative
definitions are in this repo:

- `../../packages/protocol/src/` — frames, handshake, method shapes.
- `../../packages/daemon/src/router.ts` — RPC dispatch, i.e. the real
  method surface.

When client and daemon disagree, the daemon is right. Client-side identity
invariants (session id shapes, lineage) are in `.claude/rules/session-ids.md`.

## Conventions

- `.claude/rules/*.md` — subsystem gotchas, scoped by path via `paths:`
  frontmatter. Read the ones matching what you are touching.
- `docs/decisions/NNNN-*.md` — ADRs. Never edit an accepted ADR; supersede
  it with a new one.
- Code is the source of truth; docs cover only what code cannot say
  (rejected alternatives, licensing, non-obvious invariants).

## Hard constraints

- **No Hilt.** Dependencies are manual singletons via `NodeRuntime`.
- **Modules:** `:app` plus one `:shared` KMP library (ADR 0011). Do not add
  `:core:*` / `:feature:*` modules.
- **No GPL / LGPL / AGPL dependencies.** Borrowed code must be MIT or
  Apache-2.0 and credited in `CREDITS.md` plus a comment at the borrow site.
- **The server owns run lifecycle.** The client renders the event stream;
  the only client-side terminations are a user-pressed Stop
  (`session.interrupt`) and server-sent terminal events. No idle timers, no
  disconnect-driven teardown. A WebSocket reconnect must not kill a run.

## Definition of done

A change is done when `./gradlew :app:assembleDebug :app:testDebugUnitTest`
is green and it is committed. Prefer offline unit / digital-twin tests (the
`Fake*` doubles under `app/src/test/`) over device runs — they are what catch
the reconcile, hydration and scroll regressions that actually bite.
