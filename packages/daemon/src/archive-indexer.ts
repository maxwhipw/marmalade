// archive-indexer.ts — the pre-daemon Claude Code corpus (`~/.claude/projects`)
// parsed into search-store's archive tables.
//
// WHAT THIS CORPUS IS. Before marmaladed existed, every conversation the user had
// with Claude Code was appended to a JSONL transcript under
// `~/.claude/projects/<flattened-cwd>/<session-uuid>.jsonl`. It is years of real
// history that the daemon can read but must never write: this module is the
// ONLY thing in the daemon that touches those files, and it only ever opens
// them for reading.
//
// LAYOUT — TOP LEVEL ONLY. A session's own transcript is exactly
// `projects/<dir>/<uuid>.jsonl`. Anything DEEPER is subagent sidechain output
// (`projects/<dir>/<uuid>/subagents/agent-*.jsonl`): the same work seen from
// inside a Task tool call, which would double every hit and surface prompts the
// user never wrote. The glob enforces depth, and a first-message
// `isSidechain: true` is the belt-and-braces second check.
//
// WHAT COUNTS AS A MESSAGE. Only `type: "user"` / `type: "assistant"` lines, and
// within them only human-readable prose:
//   - user `message.content` may be a plain STRING or an array of blocks;
//     `text` blocks are taken, `tool_result` / `image` / everything else is not.
//   - assistant `message.content` is always blocks; only `text` survives —
//     `thinking` and `tool_use` are excluded for the same reason the live
//     indexer excludes them (they aren't what anyone searches for). Several
//     text blocks in one message join with a newline: the hit unit is the
//     MESSAGE, matching the live corpus.
//   - `isMeta: true` lines are harness plumbing (skill preambles, injected
//     context), not speech.
//   - synthetic ENVELOPES the CLI writes as if the user had typed them —
//     `<command-name>…`, `<local-command-…>`, `<task-notification>` — are
//     skipped by prefix. They are UI machinery; matching them buries the real
//     conversation under slash-command echoes.
// Other line types (`system`, `attachment`, `queue-operation`, `last-prompt`,
// `mode`, `file-history-*`, `ai-title`, `summary`) carry no conversation.
//
// TITLE. Observed reality (2026-07-28, 902 files): this corpus has ZERO
// `summary` lines — Claude Code writes `{"type":"ai-title","aiTitle":"…"}`
// instead. Both are honoured, last one seen wins (a session gets retitled as it
// grows), then the first user message truncated, then null.
//
// ROBUSTNESS. These files are live — Claude Code may be appending to one while
// we read it, so a trailing half-written line is NORMAL, not corruption. An
// unparseable line is skipped and counted; it never aborts the file. A file
// that throws is logged and never aborts the scan.

import { readFile, readdir, stat } from "node:fs/promises";
import { basename, join } from "node:path";
import { homedir } from "node:os";
import type { ArchiveRow, ArchiveSessionMeta, SearchStore } from "./search-store.js";

/** Where the pre-daemon corpus lives. Injectable everywhere below so tests
 *  never read the real one. */
export function defaultArchiveDir(): string {
  return join(homedir(), ".claude", "projects");
}

/** How long a fallback title (first user message) may be. */
const TITLE_CAP = 80;

/** Synthetic "user" text the CLI injects. Never conversation. */
const ENVELOPE_PREFIXES = ["<command-", "<local-command", "<task-notification"];

export interface ArchiveExtract {
  title: string | null;
  cwd: string | null;
  lastTs: number;
  messages: ArchiveRow[];
  /** Lines that failed to parse — a tail-truncated live file scores 1. */
  badLines: number;
  /** True when the first user/assistant line said `isSidechain: true`. The
   *  caller must not index the file. */
  sidechain: boolean;
}

interface AnyLine {
  type?: unknown;
  uuid?: unknown;
  cwd?: unknown;
  timestamp?: unknown;
  isMeta?: unknown;
  isSidechain?: unknown;
  summary?: unknown;
  aiTitle?: unknown;
  message?: { content?: unknown } | unknown;
}

/** The text a user/assistant line contributes, or "" for none. */
function textOfLine(d: AnyLine): string {
  const msg = d.message;
  if (typeof msg !== "object" || msg === null) return "";
  const content = (msg as { content?: unknown }).content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  const parts: string[] = [];
  for (const b of content) {
    if (typeof b !== "object" || b === null) continue;
    const blk = b as { type?: unknown; text?: unknown };
    if (blk.type === "text" && typeof blk.text === "string" && blk.text !== "") parts.push(blk.text);
  }
  return parts.join("\n");
}

/** Parse one transcript's raw contents. Pure — no filesystem, so the extraction
 *  rules above are testable against synthetic fixtures. */
export function extractArchiveSession(contents: string): ArchiveExtract {
  const out: ArchiveExtract = { title: null, cwd: null, lastTs: 0, messages: [], badLines: 0, sidechain: false };
  let firstSpeech = true;
  let fallbackTitle: string | null = null;

  for (const line of contents.split("\n")) {
    if (line.trim() === "") continue;
    let d: AnyLine;
    try {
      d = JSON.parse(line) as AnyLine;
    } catch {
      out.badLines++;
      continue;
    }
    if (typeof d !== "object" || d === null) { out.badLines++; continue; }

    // Titles ride on their own line types and carry no cwd/ts.
    if (d.type === "summary" && typeof d.summary === "string" && d.summary !== "") { out.title = d.summary; continue; }
    if (d.type === "ai-title" && typeof d.aiTitle === "string" && d.aiTitle !== "") { out.title = d.aiTitle; continue; }

    const role = d.type === "user" ? "user" : d.type === "assistant" ? "assistant" : null;
    if (role === null) continue;

    if (firstSpeech) {
      firstSpeech = false;
      // Belt-and-braces against a sidechain file reached some other way than
      // the depth-limited glob. Whole file is out.
      if (d.isSidechain === true) { out.sidechain = true; return out; }
    }
    if (out.cwd === null && typeof d.cwd === "string" && d.cwd !== "") out.cwd = d.cwd;
    if (d.isMeta === true) continue;

    const text = textOfLine(d);
    if (text === "") continue;
    if (role === "user" && ENVELOPE_PREFIXES.some((p) => text.startsWith(p))) continue;

    const ts = typeof d.timestamp === "string" ? Date.parse(d.timestamp) : NaN;
    const stamp = Number.isFinite(ts) ? ts : 0;
    if (stamp > out.lastTs) out.lastTs = stamp;
    out.messages.push({
      ordinal: out.messages.length,
      messageUuid: typeof d.uuid === "string" ? d.uuid : `${out.messages.length}`,
      role,
      ts: stamp,
      text,
    });
    if (fallbackTitle === null && role === "user") {
      fallbackTitle = text.length > TITLE_CAP ? `${text.slice(0, TITLE_CAP)}…` : text;
    }
  }

  if (out.title === null) out.title = fallbackTitle;
  return out;
}

export interface ScanSummary {
  indexed: number;
  skipped: number;
  empty: number;
  failed: number;
}

/** Every `<dir>/<uuid>.jsonl` directly under the archive root. Depth is the
 *  whole point — see the header. A missing root is a clean empty list. */
export async function listArchiveFiles(dir: string): Promise<string[]> {
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return [];
  }
  const files: string[] = [];
  for (const e of entries) {
    if (!e.isDirectory()) continue;
    const sub = join(dir, e.name);
    let inner;
    try {
      inner = await readdir(sub, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const f of inner) {
      if (f.isFile() && f.name.endsWith(".jsonl")) files.push(join(sub, f.name));
    }
  }
  return files;
}

export interface ScanOptions {
  log?: (line: string) => void;
  /** Yield to the event loop between files so a boot-time scan never starves
   *  the gateway. Overridable in tests to run tight. */
  breathe?: () => Promise<void>;
}

const yieldToLoop = (): Promise<void> => new Promise<void>((r) => { setImmediate(r); });

/** Bring the archive index in line with the files on disk.
 *
 *  Freshness is (mtime_ms, size): unchanged → skip, changed or new →
 *  reindex WHOLESALE. Not index-once — Claude Code is still running, so these
 *  files still grow. Rows for a file that has since been DELETED are left
 *  alone: the index is the only remaining copy of that conversation's text and
 *  dropping it would destroy history to save a few kilobytes. */
export async function scanArchive(
  store: SearchStore,
  dir: string,
  opts: ScanOptions = {},
): Promise<ScanSummary> {
  const breathe = opts.breathe ?? yieldToLoop;
  const summary: ScanSummary = { indexed: 0, skipped: 0, empty: 0, failed: 0 };
  const files = await listArchiveFiles(dir);

  for (const path of files) {
    await breathe();
    const sessionId = basename(path, ".jsonl");
    try {
      const st = await stat(path);
      const mtimeMs = Math.floor(st.mtimeMs);
      const size = st.size;
      const known = store.archiveFileState(sessionId);
      if (known && known.mtimeMs === mtimeMs && known.size === size) { summary.skipped++; continue; }

      const extract = extractArchiveSession(await readFile(path, "utf8"));
      if (extract.sidechain || extract.messages.length === 0) { summary.empty++; continue; }

      const meta: ArchiveSessionMeta = {
        sessionId,
        path,
        cwd: extract.cwd ?? "",
        title: extract.title,
        lastTs: extract.lastTs,
        messageCount: extract.messages.length,
        mtimeMs,
        size,
      };
      store.indexArchiveSession(meta, extract.messages);
      summary.indexed++;
    } catch (e) {
      // One unreadable file must never end the scan.
      summary.failed++;
      opts.log?.(`[archive] index ${sessionId} failed: ${(e as Error).message}`);
    }
  }
  return summary;
}
