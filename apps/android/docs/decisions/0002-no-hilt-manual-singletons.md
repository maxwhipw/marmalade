# 0002. Manual singletons via NodeRuntime; no Hilt or DI framework

Status: Accepted
Date: 2026-04-24 (recording an earlier decision)

## Context

The fork base used manual singleton pattern via a `NodeRuntime` service
locator. The question was whether to migrate to Hilt (Google's
recommended Android DI), Dagger, or any DI framework — or keep the
manual pattern.

Hilt would provide compile-time DI, lifecycle scoping, and testability
benefits. The cost is annotation processing overhead, additional Gradle
configuration, and refactoring every existing service entry point.

## Decision

Keep manual singletons via `NodeRuntime`. **Do not add `@Inject`,
`@HiltViewModel`, `@AndroidEntryPoint`, or any other Hilt or DI
annotations.**

`NodeRuntime` is a service locator constructed at `MarmaladeApplication`
init. Singletons (gateway session, chat controller, settings repository,
etc.) live as properties on `NodeRuntime`. Code accesses them through
the `NodeRuntime` instance.

## Consequences

- Initialization order is **explicit and controllable** — important for
  the dual-session WebSocket pattern where operator and node sessions
  must come up in a specific sequence relative to gateway state
- No annotation processor; faster builds; smaller dependency tree
- Tests construct singletons manually or pass mocks directly — no
  Hilt-test scaffolding
- Refactoring to DI later is non-trivial but possible if needed; the
  service-locator pattern is a known migration source for DI
- Single-module project keeps this simple; if the project ever needs to
  split into modules, manual singletons across module boundaries get
  awkward (would re-trigger the DI question)

## Rejected alternatives

- **Hilt.** Standard recommendation for Android, but the complexity is
  not justified at this scale. Annotation processor overhead and
  Gradle/test scaffolding cost outweigh the benefits when the singleton
  graph is shallow and stable.
- **Dagger.** Same reasoning as Hilt with more boilerplate.
- **Koin.** Avoids annotation processing but introduces runtime DI
  failures and another dependency. Not worth the swap.
