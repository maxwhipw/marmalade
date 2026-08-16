// state-preload.ts — assemble the main session's state-read context (M4a).
//
// The main session always loads the most recent of each rollup tier (daily /
// weekly / monthly / quarterly / yearly — Decision, state-layer note). Injected
// as FIRST-MESSAGE context, not the system prompt (keeps the custom prompt
// static + cache-shared), ONCE per session generation (coh-H3 lifecycle rule).
//
// The framing header is load-bearing (state-layer Decision 1): these are the
// agent's imperfect observations of the user, not the user's own diary.

import { readdirSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const FRAMING = `# Your loaded context on your user (rollup state)

The following are rollup reports — the assistant's imperfect observations and
memories of the user, compiled from daily journals. They do NOT capture
everything the user does; this is the agent's information about the user, not
the user's own diary. Weight recent entries more, and verify anything
load-bearing against the actual files rather than trusting these summaries.`;

const DATE_FILE = /^\d{4}-\d{2}-\d{2}\.md$/;

/** Latest date-named `.md` in a dir (YYYY-MM-DD.md), or null. */
function latestDateFile(dir: string): string | null {
  if (!existsSync(dir)) return null;
  const files = readdirSync(dir).filter((f) => DATE_FILE.test(f)).sort();
  return files.length ? join(dir, files[files.length - 1]) : null;
}

export interface PreloadTier {
  label: string;
  path: string | null;
}

/** Resolve the newest file for each rollup tier that exists in the wiki. */
export function resolveStateTiers(wikiRoot: string): PreloadTier[] {
  const journal = join(wikiRoot, "journal");
  return [
    { label: "Most recent daily", path: latestDateFile(journal) },
    { label: "Most recent weekly", path: latestDateFile(join(journal, "weekly")) },
    { label: "Most recent monthly", path: latestDateFile(join(journal, "monthly")) },
    { label: "Most recent quarterly", path: latestDateFile(join(journal, "quarterly")) },
    { label: "Most recent yearly", path: latestDateFile(join(journal, "yearly")) },
  ];
}

/** Assemble the preload string. Empty string when no rollups exist yet (the
 *  session then just starts without state — never throws). */
export function assembleStatePreload(wikiRoot: string): string {
  const tiers = resolveStateTiers(wikiRoot).filter((t) => t.path);
  if (tiers.length === 0) return "";
  const sections = tiers.map((t) => {
    const body = readFileSync(t.path!, "utf8").trim();
    return `## ${t.label} (${t.path})\n\n${body}`;
  });
  return `${FRAMING}\n\n${sections.join("\n\n---\n\n")}\n\n(End of loaded context. Await the user's request.)`;
}
