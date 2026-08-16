# Agent SDK facts — verified for M1 (2026-07-10)

The round-2 review flagged SDK claims to verify before building on them.
Checked against `@anthropic-ai/claude-agent-sdk` type defs (v0.2.126, from
a local `node_modules` install) and the published docs. npm latest is
0.3.207 — **pin and re-verify at M1 install time**, these can move.

## `total_cost_usd` — RESOLVED (coh-M5)
`SDKResultMessage = SDKResultSuccess | SDKResultError`. Both variants declare
`total_cost_usd: number` (**non-optional**) and `usage: NonNullableUsage` +
`modelUsage: Record<string, ModelUsage>`. Discriminator is `subtype`
(`'success'` vs `'error_during_execution' | 'error_max_turns' |
'error_max_budget_usd' | 'error_max_structured_output_retries'`).

**Consequence for the usage meter (M1):** the field is always present, so the
meter won't null-crash.

**EMPIRICALLY VERIFIED 2026-07-11** (live one-turn session on a real
subscription via the ClaudeCodeAdapter): `total_cost_usd` **IS populated under
subscription auth** — a 2-in/5-out-token "PONG" turn reported
`total_cost_usd: 0.4225`. So the value is non-zero and represents the
**notional API-equivalent cost** (dominated by per-session/cache overhead at
this token count), NOT the actual subscription draw. Usable as a "what this
would cost if metered" signal. The usage meter tracks BOTH dollars and tokens;
tokens remain the ground-truth metric, cost is the metered-equivalent estimate.

**`apiKeySource`** on the init message came back **undefined** in the live
subscription run (field present in the type, value empty for pattern-A2
subscription sessions). So it can't be relied on to prove "this is a
subscription session" — the meter records whatever value appears but does not
gate on it.

## `exclude_dynamic_sections` — RESOLVED (coh-M1)
Docs verbatim: "applies only to the preset object form and **has no effect
when `systemPrompt` is a string**." So the main session's custom string
prompt is cache-shareable for free (nothing dynamic to strip); the flag
belongs ONLY on the coding-session `claude_code` preset + `append` path.
Requires SDK ≥ v0.2.98 (TS) there.

## Sessions / resume — CONFIRMED from docs
- Sessions persist under `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl`
  (or `$CLAUDE_CONFIG_DIR/projects/...`). **Resume is cwd-sensitive** — a
  resume from the wrong cwd silently starts fresh. The adapter MUST persist
  (marmalade id ↔ SDK session id ↔ cwd); `session-manager.ts` has the columns
  + `idx_sessions_cwd`.
- `resume` (by id) and `fork_session` are `query()` options; `ClaudeSDKClient`
  (Python) / `continue:true` (TS) auto-track within a process.
- `persistSession:false` (TS) keeps a session in memory only. Cross-host
  resume needs the `.jsonl` moved to the same encoded-cwd path, or a
  `SessionStore` adapter — relevant if the daemon ever runs sessions off-box.

## Permissions — CONFIRMED (coh-H2, the auto-approve trap)
Docs verbatim: "Auto-approved tools never reach `canUseTool`." An `allowedTools`
wildcard (e.g. `mcp__android-bridge__*`) **skips** the `canUseTool` callback.
Exception: tools whose MCP server sets
`_meta["anthropic/requiresUserInteraction"]` (CC ≥2.1.199) reach the callback
even under an allow rule. **Therefore** device-tool approval is enforced
INSIDE the android-bridge server (M5), not via canUseTool — also the only
locus that works for OpenCode, which never passes through canUseTool.
Evaluation order: hooks → deny → ask → permission-mode → allow → canUseTool.

## MCP injection — CONFIRMED
`mcpServers` (TS) / `mcp_servers` (Py) accept servers programmatically per
`query()` — stdio (`command`/`args`/`env`), http/sse (`url`/`headers`), or
in-process SDK servers. Tool names are `mcp__<server>__<tool>`. `allowedTools`
supports `mcp__server__*` wildcards (server segment must be glob-free). This
is how the daemon injects per-session android-bridge/caldav with per-session
tokens (sec-H3).

## Skills — CONFIRMED (coh-M3)
Loaded from filesystem via `settingSources`/`setting_sources` (`'user'` →
`~/.claude/skills/` + `~/.claude/CLAUDE.md`; `'project'` → `.claude/skills/`).
`skills` option filters (`"all"` | names | `[]`). **`['user']` also injects
`~/.claude/CLAUDE.md`** — so any future guest gets `settingSources: []` and
loads guest skills via `plugins`, never `'user'`.
