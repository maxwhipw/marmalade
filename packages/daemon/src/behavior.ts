// behavior.ts — render the main-session custom system prompt (M4a).
//
// One canonical spec, three hand-maintained files (simp-M2 — no renderer until
// drift hurts). The main session gets identity + duties as a CUSTOM system
// prompt (Decision 4): it replaces the harness persona, so identity.md itself
// carries the safety/judgment posture. The prompt is a STATIC string, so it
// shares a prompt cache entry for free — `exclude_dynamic_sections` is a no-op
// on a string prompt (coh-M1) and is not used.
//
// Coding sessions instead use the preset + a short append (not built here yet);
// OpenCode/Codex get the same duties content as an AGENTS.md block (M3+).
//
// LOCKED CORE + USER ADDITIONS (2026-07-27). The three spec files above are the
// locked core — nothing outside this repo can edit them. `~/.marmalade/
// behavior.md` (cfg.userBehaviorPath) is the user's own append seam: when it
// exists and is non-empty it becomes a trailing "## User additions" section, so
// the user can add behaviors without touching the core. The split is the point —
// this seam can only APPEND.
//   Scope: the addendum currently reaches the MAIN session only. Coding
//   sessions run on the harness preset instead of this prompt (see the note
//   above), so their append seam is a known follow-up, not an oversight.

import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const MAIN_FILES = ["identity.md", "state-upkeep.md", "self-improve.md"];

/** Hard cap on the user addendum. A runaway file (a log accidentally written
 *  there, a pasted transcript) must not blow up every main-session prompt, so
 *  the read is truncated rather than failed — a too-long addendum still works,
 *  just clipped, and the truncation is logged. */
const USER_BEHAVIOR_MAX_BYTES = 32 * 1024;

/** Read + trim the user addendum. Missing/empty/unreadable = "" (the prompt is
 *  then exactly what it was without this feature). */
function readUserBehavior(path: string | undefined, log?: (line: string) => void): string {
  if (!path || !existsSync(path)) return "";
  let raw: string;
  try {
    raw = readFileSync(path, "utf8");
  } catch (e) {
    log?.(`[behavior] user addendum ${path} unreadable: ${(e as Error).message}`);
    return "";
  }
  const buf = Buffer.from(raw, "utf8");
  if (buf.byteLength > USER_BEHAVIOR_MAX_BYTES) {
    log?.(`[behavior] user addendum ${path} truncated: ${buf.byteLength} bytes > ${USER_BEHAVIOR_MAX_BYTES} cap`);
    // Slice on bytes, then decode: a split multi-byte char decodes to U+FFFD
    // rather than throwing, which is the right trade for a hard byte cap.
    raw = buf.subarray(0, USER_BEHAVIOR_MAX_BYTES).toString("utf8");
  }
  return raw.trim();
}

/** Device awareness — the STATIC half of the P3 split: disposition only, no
 *  per-turn data, so the system prompt stays one cache-shared string. The
 *  dynamic half (this turn's origin device) is injected per turn by the
 *  router (originPreamble); the roster is live via list_devices. */
export const DEVICE_DISPOSITION = `## Device awareness

Your user may talk to you from different devices (desktop, phone, CLI). Each user turn begins with a bracketed \`[turn origin — …]\` line naming the device and platform it came from — treat it as metadata, not part of their message, and never echo it back. Actions that touch a device (open an app, send a notification, read a sensor) target the current turn's origin device unless they name another. The live device roster is available via the marmalade \`list_devices\` tool.`;

/** Dynamic-UI vocabulary teaching (dynamic-ui plan step 6) — the model knows
 *  nothing about \`\`\`marmalade-ui until this section teaches it, so nothing
 *  emits trees on harnesses/sessions without the behavior spec. One STATIC
 *  string appended alongside the disposition, so the prompt stays one
 *  cache-shared entry (coh-M1). Vocabulary truth:
 *  docs/dynamic-ui/marmalade-ui-v1.md — keep the catalog in sync with it. */
export const DYNAMIC_UI_CATALOG = `## Interactive UI blocks (marmalade-ui v1)

Marmalade clients render native interactive UI from a fenced block you emit:

\`\`\`marmalade-ui
{"type":"card","title":"…","children":[…]}
\`\`\`

The body is ONE JSON node (or NDJSON lines, treated as a column). Closed node
vocabulary — emit ONLY these types, with exactly these props:

- Layout: \`column\`/\`row\`/\`card\` {title?} — all with \`children\`; \`divider\` {}.
- \`text\` {text, style?: headline|title|body|caption, bold?, color?: default|primary|success|warning|error}
- \`list\` {items:[string], ordered?} · \`table\` {columns:[string], rows:[[string]]}
- \`code\` {code, language?} · \`alert\` {text, level?: info|success|warning|error, title?}
- \`button\` {label, action: callback|open_url|copy_to_clipboard, event?, collect_from?:[ids], url?, text?, variant?: primary|secondary|danger}
- \`text_input\` {id, label?, placeholder?, value?} · \`select\` {id, label?, options:[{id,label}|string]}
- \`checkbox\` {id, label, checked?} · \`chip_group\` {id, options, multi?}
- \`progress\` {value?: 0..1, label?} · \`status\` {text, state?: pending|active|success|error} · \`countdown\` {until|seconds, label?}

Interaction: inputs hold local state only; a \`callback\` button's press comes
back to you as a PLAIN user message — \`Pressed: <event>\` or
\`Responded with: <event>: id=value; …\` for \`collect_from\` ids. Conversation
history is the only state between screens.

Rules: never render a button implying an action its callback can't perform;
no fake loading/progress states — \`progress\`/\`status\` only for real work;
keep trees shallow (≤4 levels) and small (≤40 nodes), prefer several small
cards; ids are required on inputs a button collects from; always accompany a
tree with one short plain-text line for clients that can't render it. Use
trees when structured choices/forms genuinely help — plain prose remains the
default.`;

/** Session-orchestration teaching (assistant plan 2026-07-19) — the main
 *  session is the hub that watches over every other session, so its prompt
 *  spells the toolset out. STATIC string (cache-shared, coh-M1); other
 *  sessions get the same tools with only the tool descriptions as guidance. */
export const SESSION_ORCHESTRATION = `## Other sessions (the marmalade session tools)

You are THE main session — always on, and the one voice/wake-word turns land in. Coding and task sessions run beside you; your user will often ask you about them by voice ("what's the status on the build?", "tell it to continue"). The marmalade tools:

- \`list_sessions\` — every session with title, topic/summary, workspace, run_state, last_active. Start here; the summaries usually answer status questions without reading transcripts.
- \`get_session_turns\` — the last N turns of a session as text. Tool calls and thinking are opt-in; leave them off unless you're debugging what a session actually did.
- \`send_to_session\` — queue a prompt as that session's next turn ("continue", a question, new instructions). The receiving session sees it came from you.
- \`steer_session\` — mid-turn course correction when a session is running right now.
- \`interrupt_session\` — stop a session's turn (only when asked, or a run is clearly misbehaving).
- \`watch_session\` — one-shot: when that session's turn completes or errors, you get a \`[session watch]\` digest as a new turn. Use it whenever your user asks to be told when something finishes — then, when the digest arrives, RELAY it (speak/notify), don't just note it.

Keep your OWN summary current with \`update_session_summary\` on the same terms you'd want from them — every session's summary is what \`list_sessions\` shows you, so a stale one costs you the next status question. A session's first title and summary are seeded automatically from its opening exchange; after that the session itself owns them.

Rules: chains stop at one hop — a turn that another session started in you (\`via agent\`) cannot itself send/steer/interrupt; answer to your user instead. Keep relayed status conversational and short: lead with the outcome, name the session by its title, never dump ids or raw transcripts unless asked.`;

/** Assemble the main-session system prompt from the behavior spec files.
 *  Missing files are skipped (never throws) so a partial spec still boots.
 *  Returns "" when NO spec files exist — the caller then keeps the harness's
 *  default persona, so the disposition is only appended to a real spec.
 *
 *  `userBehaviorPath` (cfg.userBehaviorPath) adds the user's own addendum as
 *  the LAST section. Absent path / missing / empty file = byte-identical to
 *  the output before this seam existed. */
export function renderMainSystemPrompt(
  behaviorDir: string,
  userBehaviorPath?: string,
  log?: (line: string) => void,
): string {
  const parts: string[] = [];
  for (const f of MAIN_FILES) {
    const p = join(behaviorDir, f);
    if (existsSync(p)) parts.push(readFileSync(p, "utf8").trim());
  }
  if (parts.length > 0) {
    parts.push(DEVICE_DISPOSITION, DYNAMIC_UI_CATALOG, SESSION_ORCHESTRATION);
    // LAST, and only onto a real spec — an addendum with no core to add to
    // would silently become the whole persona.
    const user = readUserBehavior(userBehaviorPath, log);
    if (user) parts.push(`## User additions\n\n${user}`);
  }
  return parts.join("\n\n---\n\n");
}
