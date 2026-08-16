import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync, rmSync } from "node:fs";
import { assembleStatePreload, resolveStateTiers } from "../dist/state-preload.js";
import { renderMainSystemPrompt } from "../dist/behavior.js";

function fakeWiki(): string {
  const root = join(tmpdir(), `mw-${randomUUID()}`);
  const journal = join(root, "journal");
  mkdirSync(join(journal, "weekly"), { recursive: true });
  writeFileSync(join(journal, "2026-07-09.md"), "# older daily");
  writeFileSync(join(journal, "2026-07-11.md"), "# latest daily\nThe user shipped M1.");
  writeFileSync(join(journal, "weekly", "2026-07-06.md"), "# the week\nOrchestrator pivot.");
  return root;
}

test("resolveStateTiers picks the newest date-named file per tier", () => {
  const root = fakeWiki();
  try {
    const tiers = resolveStateTiers(root);
    const daily = tiers.find((t) => t.label.includes("daily"))!;
    const weekly = tiers.find((t) => t.label.includes("weekly"))!;
    assert.ok(daily.path?.endsWith("2026-07-11.md")); // latest, not 07-09
    assert.ok(weekly.path?.endsWith("2026-07-06.md"));
    // Missing tiers resolve to null.
    assert.equal(tiers.find((t) => t.label.includes("monthly"))!.path, null);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("assembleStatePreload includes framing + newest content, weights recent", () => {
  const root = fakeWiki();
  try {
    const preload = assembleStatePreload(root);
    assert.ok(preload.includes("imperfect observations"));
    assert.ok(preload.includes("The user shipped M1."));
    assert.ok(preload.includes("Orchestrator pivot."));
    assert.ok(!preload.includes("older daily")); // only the latest daily
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("assembleStatePreload is empty (never throws) when no rollups exist", () => {
  const root = join(tmpdir(), `mw-${randomUUID()}`);
  mkdirSync(root, { recursive: true });
  try {
    assert.equal(assembleStatePreload(root), "");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("renderMainSystemPrompt concatenates the real behavior spec", () => {
  // The repo's own behavior/ dir (resolved up from dist).
  const behaviorDir = join(import.meta.dirname, "..", "..", "..", "behavior");
  const prompt = renderMainSystemPrompt(behaviorDir);
  assert.ok(prompt.includes("Marmalade")); // identity.md
  assert.ok(prompt.toLowerCase().includes("journal")); // state-upkeep.md
});

// ── The user behavior addendum (locked core + user additions, 2026-07-27) ────
// ~/.marmalade/behavior.md lets the user APPEND behaviors without editing the
// locked core spec. It must land LAST, be byte-identical to today's output
// when missing/empty, and never be able to reach the core files.

const REAL_BEHAVIOR_DIR = join(import.meta.dirname, "..", "..", "..", "behavior");

/** Run `fn` with a temp addendum file (or a path that doesn't exist). */
function withAddendum(content: string | null, fn: (path: string) => void): void {
  const dir = join(tmpdir(), `ub-${randomUUID()}`);
  mkdirSync(dir, { recursive: true });
  const path = join(dir, "behavior.md");
  if (content !== null) writeFileSync(path, content);
  try { fn(path); } finally { rmSync(dir, { recursive: true, force: true }); }
}

test("the user addendum is appended LAST, under its own heading", () => {
  withAddendum("  Prefer Opus for implementation subagents.  \n", (path) => {
    const base = renderMainSystemPrompt(REAL_BEHAVIOR_DIR);
    const withUser = renderMainSystemPrompt(REAL_BEHAVIOR_DIR, path);
    assert.ok(withUser.startsWith(base), "the core prompt is untouched — this seam only appends");
    assert.equal(
      withUser.slice(base.length),
      "\n\n---\n\n## User additions\n\nPrefer Opus for implementation subagents.",
      "same joiner as every other section, content trimmed",
    );
  });
});

test("a missing, empty, or whitespace-only addendum leaves the prompt exactly as it was", () => {
  const base = renderMainSystemPrompt(REAL_BEHAVIOR_DIR);
  assert.equal(renderMainSystemPrompt(REAL_BEHAVIOR_DIR, undefined), base);
  withAddendum(null, (path) => {
    assert.equal(renderMainSystemPrompt(REAL_BEHAVIOR_DIR, path), base);
  });
  withAddendum("", (path) => {
    assert.equal(renderMainSystemPrompt(REAL_BEHAVIOR_DIR, path), base);
  });
  withAddendum("\n\n   \n", (path) => {
    assert.equal(renderMainSystemPrompt(REAL_BEHAVIOR_DIR, path), base);
  });
});

test("a runaway addendum is truncated at 32 KB and logged, never fatal", () => {
  const cap = 32 * 1024;
  withAddendum("x".repeat(cap + 5_000), (path) => {
    const lines: string[] = [];
    const base = renderMainSystemPrompt(REAL_BEHAVIOR_DIR);
    const prompt = renderMainSystemPrompt(REAL_BEHAVIOR_DIR, path, (l) => lines.push(l));
    assert.equal(prompt.slice(base.length), `\n\n---\n\n## User additions\n\n${"x".repeat(cap)}`);
    assert.equal(lines.length, 1);
    assert.match(lines[0], /truncated/);
  });
  // Exactly at the cap is NOT truncated (and logs nothing).
  withAddendum("y".repeat(32 * 1024), (path) => {
    const lines: string[] = [];
    assert.ok(renderMainSystemPrompt(REAL_BEHAVIOR_DIR, path, (l) => lines.push(l)).endsWith("y"));
    assert.deepEqual(lines, []);
  });
});

test("an addendum with NO core spec renders nothing — it can't become the persona", () => {
  const emptyDir = join(tmpdir(), `nospec-${randomUUID()}`);
  mkdirSync(emptyDir, { recursive: true });
  try {
    withAddendum("I am the whole prompt now.", (path) => {
      assert.equal(renderMainSystemPrompt(emptyDir, path), "");
    });
  } finally { rmSync(emptyDir, { recursive: true, force: true }); }
});
