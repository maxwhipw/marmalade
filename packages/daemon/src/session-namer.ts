// session-namer.ts — seed a session's title and summary from its first
// exchange with one cheap side-call (Haiku).
//
// WHY a side-call and not the session's own model (decision 2026-07-25):
//  - Titling is a labelling task. Asking the working model to name itself
//    burns that session's context and tempts it to re-title every turn.
//  - This runs ONCE per session and produces a title AND a first summary, so
//    a session opened cold in the client's panel has something to show before
//    it has done enough work for a real rollup.
//
// After this seed, the SUMMARY is maintained by the session's own model via
// the `update_session_summary` tool (see behavior.ts) — it knows what mattered
// in a way a transcript reader does not. The TITLE is written once and then
// belongs to the user; nothing overwrites it automatically.
//
// The call goes through the agent SDK the daemon already depends on, NOT the
// raw Anthropic API: that keeps it on the same subscription auth as everything
// else, with no second credential and no separate billing.

import { query } from "@anthropic-ai/claude-agent-sdk";

/** Cheapest model that can do this well. Overridable via config. */
export const DEFAULT_NAMING_MODEL = "claude-haiku-4-5";

/** Titles are list labels. The session manager caps too; this keeps the model
 *  from generating a paragraph we then truncate mid-word. */
export const MAX_TITLE_CHARS = 60;
/** Matches SessionManager.MAX_SUMMARY — reject rather than silently truncate. */
export const MAX_SUMMARY_CHARS = 1000;

export interface Naming {
  title: string;
  summary: string;
}

// The digest is always the first ONE exchange (router: turns: 2, fired after
// the first completed turn), so "is this enough to name?" has to be answered
// for a single request — that is the normal case, not the edge case. An
// earlier wording offered the empty string for anything "too thin (a greeting,
// a single yes/no)" and Haiku read a perfectly nameable one-question exchange
// as thin: the session kept "New Chat" and got only a summary, which is the
// worse half to have (the list shows the title). The escape hatch is now
// reserved for an exchange with no subject at all.
const SYSTEM_PROMPT = [
  "You label agent sessions for a session list. You are given the opening",
  "exchange of one session — usually a single request and its reply. That is",
  "normally enough: name what was asked for.",
  "",
  "Reply with ONLY a JSON object, no prose and no code fence:",
  '{"title": "...", "summary": "..."}',
  "",
  `title: ${MAX_TITLE_CHARS} characters or fewer. Name the WORK, not the`,
  "  conversation — \"Fix terminal geometry\", not \"User asks about a bug\".",
  "  Sentence case, no trailing period, no quotes. Use the empty string ONLY",
  "  when the exchange has no subject to name at all — a bare greeting, a",
  "  thank-you, a test message. A single clear request is nameable.",
  "summary: two sentences at most. What this session is for and where it got",
  "  to. Plain past/present tense, no preamble like \"This session\".",
].join("\n");

/**
 * Pull a [Naming] out of a model reply.
 *
 * Separate from the call so it can be tested without the SDK, and tolerant on
 * purpose: small models wrap JSON in fences or add a lead-in sentence often
 * enough that being strict here would mean losing titles for no reason.
 *
 * Returns null when there is nothing usable — an unparseable reply, or a model
 * that correctly judged the exchange too thin to name.
 */
export function parseNaming(raw: string): Naming | null {
  const fenced = raw.match(/```(?:json)?\s*([\s\S]*?)```/);
  const body = (fenced ? fenced[1] : raw).trim();
  // First balanced-looking object in the text: a lead-in sentence before the
  // JSON is the common failure, a trailing one is rarer but both survive this.
  const start = body.indexOf("{");
  const end = body.lastIndexOf("}");
  if (start < 0 || end <= start) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(body.slice(start, end + 1));
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object") return null;

  const obj = parsed as Record<string, unknown>;
  const title = typeof obj.title === "string" ? obj.title.trim() : "";
  const summary = typeof obj.summary === "string" ? obj.summary.trim() : "";
  // A summary alone is still worth keeping (the model may have judged the
  // exchange unnameable but summarizable); a title alone likewise.
  if (!title && !summary) return null;

  return {
    // Strip surrounding quotes — models add them to a "title" field often.
    title: title.replace(/^["'“]|["'”]$/g, "").slice(0, MAX_TITLE_CHARS),
    summary: summary.slice(0, MAX_SUMMARY_CHARS),
  };
}

export interface NamingDeps {
  model?: string;
  pathToClaudeCodeExecutable?: string;
  log: (msg: string) => void;
}

/**
 * Ask the naming model to label [digest] (a rendered transcript excerpt).
 *
 * Never throws: naming is decoration, and a session that fails to get a title
 * must still work exactly as before. Returns null on any failure.
 *
 * The call is deliberately inert — no tools, no MCP servers, no filesystem
 * settings (`settingSources: []`), one turn. It must not be able to touch the
 * workspace it is describing.
 */
export async function generateNaming(
  digest: string,
  deps: NamingDeps,
): Promise<Naming | null> {
  if (!digest.trim()) return null;
  try {
    const q = query({
      prompt: `Opening exchange:\n\n${digest}`,
      options: {
        model: deps.model ?? DEFAULT_NAMING_MODEL,
        systemPrompt: SYSTEM_PROMPT,
        maxTurns: 1,
        allowedTools: [],
        // SDK isolation mode: no ~/.claude/settings.json, no project
        // CLAUDE.md. This call describes a workspace; it must not inherit
        // that workspace's configuration.
        settingSources: [],
        ...(deps.pathToClaudeCodeExecutable
          ? { pathToClaudeCodeExecutable: deps.pathToClaudeCodeExecutable }
          : {}),
      },
    });

    let text = "";
    for await (const msg of q) {
      if (msg.type === "result" && "result" in msg && typeof msg.result === "string") {
        text = msg.result;
      }
    }
    const naming = parseNaming(text);
    if (!naming) deps.log(`[namer] unusable reply (${text.length} chars)`);
    return naming;
  } catch (e) {
    deps.log(`[namer] failed: ${(e as Error).message}`);
    return null;
  }
}

/** Titles the client and the daemon generate as stand-ins. A session still
 *  wearing one of these has not been named by a human, so the seed may claim
 *  it; anything else is the user's and is left alone. */
const PLACEHOLDER_TITLES = new Set(["new chat", "new session", "untitled", "chat"]);

/** True when [title] is absent or a known stand-in — i.e. naming it is safe. */
export function isPlaceholderTitle(title: string | null | undefined): boolean {
  const t = (title ?? "").trim().toLowerCase();
  return t === "" || PLACEHOLDER_TITLES.has(t);
}
