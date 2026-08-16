# Robustness review + remediation (2026-07-11)

Three sequential Fable reviewers critiqued the daily-usable core (architecture
→ robustness → synthesis). Verdict: **"good bones, barely-enough robustness"** —
a genuinely modular long-term architecture with intuitive APIs, but a failure
plane where one malformed frame killed the daemon and every failure converged
on eternal client silence (the OpenClaw wound). Full findings are in an
internal review note; this file tracks what was fixed vs deferred.

## Fixed this pass (P0 crash/total-loss + P1 silently-broken)

See the "Robustness pass" commit. Highlights: gateway crash-proofing (R1) +
process handlers; `session.resume` re-INSERT crash (H1) + summary-survival +
resume-while-live (S2); a single failure lifecycle so failures become
client-visible error events + terminal status + live-map cleanup (M3/R2/R3/R7);
OpenCode init timeout + serialized sends (R2/R8); cache-token accounting (R10);
topic COALESCE (S1); preload/replay/migration hardening (R6/R13/R14). Covered
by 3 new test suites (gateway real-socket, resume-over-persistent-db,
failure-injection). 70 tests green.

## Deferred — P2 hardening (do opportunistically)
- **R12** — `PromptQueue.push` after `close()` silently drops; make it throw.
  Also the `shouldQuery:false` branch is now dead code (preload prepends).
- **R16** — no cap on live sessions; the CLI should call `session.stop` on
  exit so it doesn't strand a child per connect.
- **M4/budget** — `UsageMeter.isOverBudget` is still unwired and the meter is
  in-memory (resets on restart). R10 (token accounting) is fixed, so wiring
  the guardrail is now SAFE to do later; it needs the meter persisted to
  SQLite + a `BudgetConfig`.
- Supervisor in-turn heartbeat (R4): a long tool run (no SDK messages for
  minutes) can still trip the 120s silent-failure timeout. Needs a
  turn-in-flight state or tool-start heartbeat with a longer in-turn timeout.

## Deferred — P3 architecture rework, GATE BEFORE M2 (Android)
These are structural and the mobile client will hit them on day one:
- **H2 / S4 — RESOLVED 2026-07-11 (P4, commit 272bbff).** Per-session
  subscriber sets + `session.subscribe(since_seq)` replay + seen cursors
  landed and were live-verified; the pre-M2 gate is cleared. (Original
  text kept below for context.) Event delivery WAS welded to the
  *creating* connection's closure; the attach/subscribe RPC + subscriber
  set + replay-from-cursor described here is exactly what got built.
- **H3** — no harness selection: only `ClaudeCodeAdapter` is wired into the
  daemon; `OpenCodeAdapter` is built + tested but unreachable from the gateway,
  and `model`/`provider`/`profile` params are ignored. Needs an adapter
  registry keyed by those params.
- **M1** — `HarnessSession.send` contract is undefined (Claude resolves on
  queue push; OpenCode blocks until turn end). Define it (queue-and-return +
  completion event) and make OpenCode conform.
- **M5** — `CLAUDE_CONFIG_DIR` is set for every harness child (a Claude-ism in
  the neutral seam); move config-dir + the summary MCP tool into
  adapter-owned env/capability hooks. Summary tool is Claude-only today.
- **S3** — `purposeFor` hardcodes `"main"` (every gateway session pays the
  preload); no purpose maps to `metered`, so `buildChildEnv`'s metered branch
  + keyring path are unreachable. Add a policy-matrix test per (purpose,
  authClass) before it goes live.

## Also true (reviewer honesty, kept for the record)
- Hot-path sync writes (sqlite UPDATE, appendFileSync per delta) measured as
  non-issues on this NVMe (~0.02 ms/op) — do NOT prematurely optimize.
- The policy layer (allowlist-from-empty env + HOME isolation + positive-shape
  assertion) was called the strongest code in the repo. Don't soften it.
