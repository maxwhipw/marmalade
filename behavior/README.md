# behavior/ — the canonical marmalade behavior spec

One source of truth for marmalade's identity and duties (agent-wiki state
upkeep, daily journals, todos, session summaries, self-improvement). Three
hand-maintained files, **no renderer** — a renderer isn't worth building until
drift between the variants actually hurts (simp-M2):

- `identity.md` — who Marmalade is (main-session persona; a custom system
  prompt replaces the harness default, so identity.md carries the safety
  posture too). Ships **generic on purpose** — personal context belongs in
  `~/.marmalade/behavior.md`, which `renderMainSystemPrompt()` appends last as
  a "## User additions" section. Don't personalize the core files.
- `state-upkeep.md` — journals, todos, keeping a session summary current.
- `self-improve.md` — the disposition to propose durable fixes.

How they're used today (`behavior.ts` / router M4a):
- Claude Code **main session** → `renderMainSystemPrompt()` concatenates the
  three files into a custom system prompt (a static string → cache-shared for
  free; `exclude_dynamic_sections` is a no-op on a string prompt, coh-M1, and
  is NOT used).
- Claude Code **coding sessions** → the harness default + (future) a short
  duties-only append; not wired yet.
- OpenCode / Codex → the same duties content as an AGENTS.md block; not wired
  yet.

If keeping the variants in sync by hand ever becomes error-prone, that's when a
renderer earns its keep — not before.
