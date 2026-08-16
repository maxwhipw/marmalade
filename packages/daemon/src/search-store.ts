// search-store.ts — the FTS5 search sidecar (search.messages).
//
// A SEPARATE database file (`search.db`, beside `sessions.db`) on purpose. The
// locked rule is "SQLite = index, NDJSON = content" (message-store.ts:1-11) and
// an FTS table stores CONTENT — folding it into the identity db would quietly
// break that sentence. As a sidecar it is disposable BY DECLARATION: delete
// search.db and the boot reconcile rebuilds it from the transcripts. Every
// consistency question therefore has the same answer: rebuild.
//
// WHAT GETS INDEXED — message text only, never tool calls, tool results,
// thinking/reasoning or system prompts. That falls out of the event model
// rather than being filtered:
//   - `message.user`            → the user's text (authoritative).
//   - CONSOLIDATED `message.delta` → the assistant's prose. After the turn-end
//     compaction (transcript-cache.ts DELTA CONTRACT) each message's delta run
//     is ONE event carrying the whole text. RAW deltas are skipped: they belong
//     to a live turn and will be re-seen, whole, once it compacts.
//   - `message.complete` with a `text` field whose message has NO delta event
//     in the file (cached-without-streaming edge) → the assistant's text. When
//     deltas exist they win: `complete` carries only the FINAL text block of a
//     turn, so prose emitted between tool calls is delta-only.
// `thinking.delta` / `reasoning.delta` are separate event types and excluded
// wholesale. `partial: true` messages ARE indexed — their text is real.
//
// WATERMARK — one comparison self-heals every crash window. Per session we
// store `indexed_through_seq`; against the transcript's last seq:
//   lastSeq < watermark → the file was truncated (undo / clear) → drop the
//                         session's rows and reindex the whole file
//   lastSeq > watermark → index the tail
//   equal               → nothing to do
//
// SCOPE is NOT resolved here. Workspace membership is a cwd prefix match
// (deepest wins) owned by workspace-store's matcher; the router resolves scope
// to a set of session ids and passes it in. Re-deriving it in SQL would
// guarantee search scope eventually disagrees with the session list.
//
// THE ARCHIVE CORPUS (`archive_fts` / `archive_sessions`) is a SECOND, fully
// separate corpus in the same sidecar file: the pre-daemon Claude Code history
// under `~/.claude/projects`, read-only. Separate TABLES rather than a `corpus`
// column so the live path's SQL is untouched — a live query cannot accidentally
// see archive rows, and `rm search.db` still rebuilds both from scratch.
//
// Its differences from the live corpus are structural, not incidental:
//   - no watermark: the unit of change is a FILE, and the freshness key is
//     (mtime_ms, size). A changed file is reindexed WHOLESALE — the archive is
//     small enough that whole-file honesty beats tail-append cleverness.
//   - `ordinal` (0-based position among a session's extracted messages) stands
//     in for `seq`, and `message_uuid` for `message_id`.
//   - full message text lives in the FTS table, so the transcript viewer
//     (`search.archive`) is served from the index and never re-reads the file.
// Parsing lives next door in archive-indexer.ts; this file only stores.

import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import type { JsonRpcEvent } from "@marmalade/protocol";
import { SNIPPET_OPEN, SNIPPET_CLOSE, SEARCH_TEXT_CAP } from "@marmalade/protocol";
import type { MessageRole } from "./message-store.js";

const SCHEMA = `
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
  text, session_id UNINDEXED, message_id UNINDEXED, seq UNINDEXED,
  role UNINDEXED, ts UNINDEXED,
  tokenize='unicode61', prefix='2 3'
);
CREATE TABLE IF NOT EXISTS watermarks (
  session_id TEXT PRIMARY KEY, indexed_through_seq INTEGER NOT NULL
);
CREATE VIRTUAL TABLE IF NOT EXISTS archive_fts USING fts5(
  text, session_id UNINDEXED, ordinal UNINDEXED, message_uuid UNINDEXED,
  role UNINDEXED, ts UNINDEXED,
  tokenize='unicode61', prefix='2 3'
);
CREATE TABLE IF NOT EXISTS archive_sessions (
  session_id TEXT PRIMARY KEY,
  path TEXT NOT NULL,
  cwd TEXT NOT NULL,
  title TEXT,
  last_ts INTEGER NOT NULL,
  message_count INTEGER NOT NULL,
  mtime_ms INTEGER NOT NULL,
  size INTEGER NOT NULL
);
`;
// NOTE: no porter stemming — it mangles identifiers, and this corpus is
// code-flavoured chat. unicode61 treats `_` as a separator, so `seq_high_water`
// matches as a phrase. Its default remove_diacritics folds accents on BOTH
// sides, so "café" and "cafe" find each other. Its one real limit is scripts
// that don't space their words: an unbroken CJK run is ONE token, so a
// Japanese query matches at a token boundary (or with the `*` prefix marker)
// but not mid-token — a trigram tokenizer is the fix if that ever bites.

/** How many tokens of context `snippet()` returns around a match. */
const SNIPPET_TOKENS = 12;

export function defaultSearchDbPath(stateDir: string): string {
  return join(stateDir, "search.db");
}

/** One indexable message, already folded to whole text. */
export interface IndexRow {
  messageId: string;
  role: MessageRole;
  /** The message's highest event seq — the watermark comparison key. */
  seq: number;
  ts: number;
  text: string;
}

interface EventPayload {
  message_id?: string;
  seq?: number;
  ts?: number;
  text?: string;
  consolidated?: boolean;
}

function payloadOf(ev: JsonRpcEvent): EventPayload {
  return (ev.params?.payload ?? {}) as EventPayload;
}

/** Highest stamped seq across a transcript's events (0 if none) — the value the
 *  watermark advances to. Mirrors TranscriptCache.lastSeq without a second
 *  file read. */
export function maxEventSeq(events: readonly JsonRpcEvent[]): number {
  let max = 0;
  for (const ev of events) {
    const s = payloadOf(ev).seq;
    if (typeof s === "number" && s > max) max = s;
  }
  return max;
}

/** Transcript events → one row per message, in first-appearance order.
 *  See the extraction rules in the file header. A message whose consolidated
 *  prose is split across a tool call (two consolidated events, same
 *  message_id) is joined back into one row — the hit unit is the MESSAGE. */
export function extractMessages(events: readonly JsonRpcEvent[]): IndexRow[] {
  // Any delta event at all (raw or consolidated) means this message's text is
  // the delta stream's job, so `message.complete` must not also claim it.
  const hasDelta = new Set<string>();
  for (const ev of events) {
    if (ev.params?.type !== "message.delta") continue;
    const id = payloadOf(ev).message_id;
    if (typeof id === "string") hasDelta.add(id);
  }

  const rows = new Map<string, IndexRow>();
  const add = (p: EventPayload, role: MessageRole): void => {
    if (typeof p.message_id !== "string" || typeof p.text !== "string" || p.text === "") return;
    const seq = typeof p.seq === "number" ? p.seq : 0;
    const ts = typeof p.ts === "number" ? p.ts : 0;
    const prev = rows.get(p.message_id);
    if (prev) {
      prev.text += `\n${p.text}`;
      if (seq > prev.seq) prev.seq = seq;
      if (ts > prev.ts) prev.ts = ts;
      return;
    }
    rows.set(p.message_id, { messageId: p.message_id, role, seq, ts, text: p.text });
  };

  for (const ev of events) {
    const p = payloadOf(ev);
    switch (ev.params?.type) {
      case "message.user":
        add(p, "user");
        break;
      case "message.delta":
        // Raw deltas belong to a turn still in flight — skipped, not lost: the
        // turn-end compaction re-presents them as one consolidated event.
        if (p.consolidated === true) add(p, "assistant");
        break;
      case "message.complete":
        if (typeof p.message_id === "string" && !hasDelta.has(p.message_id)) add(p, "assistant");
        break;
      default:
        // tool.*, thinking.delta, reasoning.delta, status.update, session.info…
        break;
    }
  }
  return [...rows.values()];
}

/** The FTS MATCH expression built from raw user input. NEVER interpolate the
 *  raw string: a lone `"` is a syntax error, and `NEAR`/`OR`/`-`/`(` would let
 *  a user (or a pasted code fragment) rewrite the query's semantics.
 *
 *  `"quoted phrases"` survive as phrases; everything else splits on whitespace.
 *  Each token keeps only LETTERS, NUMBERS and `_` (Unicode-wide: \p{L}\p{N},
 *  not ASCII — an ASCII-only class would silently make every Japanese query
 *  return nothing and cut "café" down to "caf", while unicode61 underneath
 *  handles both fine) plus a TRAILING `*` (the prefix marker), and is emitted
 *  double-quoted — so every FTS operator arrives as a literal term. Quoting,
 *  not the character class, is what closes the injection surface. Terms AND
 *  together. Returns "" when nothing survives sanitizing; callers must then
 *  return an empty result rather than query. */
export function buildMatchExpression(raw: string): string {
  const terms: string[] = [];

  /** "foo-bar*" → `"foobar"*`; returns "" when nothing is left. */
  const sanitizeToken = (tok: string): string => {
    const prefix = tok.endsWith("*");
    const body = tok.replace(/[^\p{L}\p{N}_]/gu, "");
    return body ? `"${body}"${prefix ? "*" : ""}` : "";
  };

  // Only PAIRED quotes make a phrase; a trailing unpaired quote falls through
  // to the loose-token path below and is stripped there.
  const loose = raw.replace(/"([^"]*)"/g, (_m, inner: string) => {
    const words = inner.split(/\s+/).map((w) => w.replace(/[^\p{L}\p{N}_]/gu, "")).filter(Boolean);
    if (words.length) terms.push(`"${words.join(" ")}"`);
    return " ";
  });

  for (const tok of loose.split(/\s+/)) {
    if (!tok) continue;
    const t = sanitizeToken(tok);
    if (t) terms.push(t);
  }
  return terms.join(" AND ");
}

export interface SearchQuery {
  query: string;
  role?: MessageRole;
  since?: number;
  sort: "rank" | "recent";
  limit: number;
  offset: number;
}

/** One indexable message from an archive transcript. `ordinal` is assigned by
 *  the extractor: 0-based within the session's extracted messages. */
export interface ArchiveRow {
  ordinal: number;
  messageUuid: string;
  role: MessageRole;
  ts: number;
  text: string;
}

/** The `archive_sessions` row — one per indexed .jsonl file. */
export interface ArchiveSessionMeta {
  sessionId: string;
  path: string;
  cwd: string;
  title: string | null;
  lastTs: number;
  messageCount: number;
  mtimeMs: number;
  size: number;
}

export interface SearchHit {
  sessionId: string;
  messageId: string;
  seq: number;
  role: MessageRole;
  ts: number;
  snippet: string;
  text: string;
}

export class SearchStore {
  private db: DatabaseSync;

  constructor(dbPath: string) {
    if (dbPath !== ":memory:") mkdirSync(dirname(dbPath), { recursive: true });
    this.db = new DatabaseSync(dbPath);
    this.db.exec(SCHEMA);
  }

  private watermark(sessionId: string): number {
    const row = this.db
      .prepare(`SELECT indexed_through_seq AS s FROM watermarks WHERE session_id = ?`)
      .get(sessionId) as { s: number } | undefined;
    return row?.s ?? 0;
  }

  private setWatermark(sessionId: string, seq: number): void {
    this.db
      .prepare(
        `INSERT INTO watermarks (session_id, indexed_through_seq) VALUES (?, ?)
         ON CONFLICT(session_id) DO UPDATE SET indexed_through_seq = excluded.indexed_through_seq`,
      )
      .run(sessionId, seq);
  }

  /** Index the events past the watermark, then advance it to the file's max
   *  seq. Re-inserting a message replaces its row (delete-then-insert), so a
   *  message whose parts straddle the watermark can't end up doubled. */
  indexTail(sessionId: string, events: readonly JsonRpcEvent[]): number {
    const wm = this.watermark(sessionId);
    const rows = extractMessages(events).filter((r) => r.seq > wm);
    const del = this.db.prepare(`DELETE FROM messages_fts WHERE message_id = ?`);
    const ins = this.db.prepare(
      `INSERT INTO messages_fts (text, session_id, message_id, seq, role, ts) VALUES (?,?,?,?,?,?)`,
    );
    for (const r of rows) {
      del.run(r.messageId);
      ins.run(r.text, sessionId, r.messageId, r.seq, r.role, r.ts);
    }
    const last = maxEventSeq(events);
    if (last > wm) this.setWatermark(sessionId, last);
    return rows.length;
  }

  /** The self-healing entry point — see the WATERMARK note in the header.
   *  `transcriptLastSeq` is the transcript file's highest seq (maxEventSeq of
   *  the same events, or TranscriptCache.lastSeq). */
  reconcile(sessionId: string, transcriptLastSeq: number, events: readonly JsonRpcEvent[]): void {
    const wm = this.watermark(sessionId);
    if (transcriptLastSeq === wm) return;
    // Truncation (undo / clear / a rebuilt file): the tail we indexed no longer
    // exists, so the only honest move is to start this session over.
    if (transcriptLastSeq < wm) this.dropSession(sessionId);
    this.indexTail(sessionId, events);
  }

  /** session.delete / session.clear cascade. Idempotent. */
  dropSession(sessionId: string): void {
    this.db.prepare(`DELETE FROM messages_fts WHERE session_id = ?`).run(sessionId);
    this.db.prepare(`DELETE FROM watermarks WHERE session_id = ?`).run(sessionId);
  }

  /** One message's indexed text (capped) — powers a user hit's reply preview
   *  without re-reading the transcript. */
  textOf(messageId: string, cap = SEARCH_TEXT_CAP): string | undefined {
    const row = this.db
      .prepare(`SELECT substr(text, 1, ?) AS t FROM messages_fts WHERE message_id = ?`)
      .get(cap, messageId) as { t: string } | undefined;
    return row?.t;
  }

  /** Run a search inside `allowedSessionIds` — the router's principal +
   *  scope + archived resolution. An empty set never reaches SQL. */
  search(q: SearchQuery, allowedSessionIds: readonly string[]): { total: number; hits: SearchHit[] } {
    const empty = { total: 0, hits: [] as SearchHit[] };
    if (allowedSessionIds.length === 0) return empty;
    const match = buildMatchExpression(q.query);
    if (!match) return empty;

    const where = [`messages_fts MATCH ?`, `session_id IN (${allowedSessionIds.map(() => "?").join(",")})`];
    const args: (string | number)[] = [match, ...allowedSessionIds];
    if (q.role) { where.push(`role = ?`); args.push(q.role); }
    if (q.since !== undefined) { where.push(`ts >= ?`); args.push(q.since); }
    const whereSql = where.join(" AND ");

    const totalRow = this.db
      .prepare(`SELECT COUNT(*) AS c FROM messages_fts WHERE ${whereSql}`)
      .get(...args) as { c: number };

    // bm25() is negative-better, so plain ASC is best-first.
    const order = q.sort === "recent" ? `ts DESC, seq DESC` : `bm25(messages_fts), ts DESC`;
    const rows = this.db
      .prepare(
        `SELECT session_id, message_id, seq, role, ts,
                snippet(messages_fts, 0, ?, ?, ?, ${SNIPPET_TOKENS}) AS snippet,
                substr(text, 1, ?) AS text
         FROM messages_fts
         WHERE ${whereSql}
         ORDER BY ${order}
         LIMIT ? OFFSET ?`,
      )
      .all(SNIPPET_OPEN, SNIPPET_CLOSE, "…", SEARCH_TEXT_CAP, ...args, q.limit, q.offset) as Record<string, unknown>[];

    return {
      total: totalRow?.c ?? 0,
      hits: rows.map((r) => ({
        sessionId: r.session_id as string,
        messageId: r.message_id as string,
        seq: r.seq as number,
        role: r.role as MessageRole,
        ts: r.ts as number,
        snippet: r.snippet as string,
        text: r.text as string,
      })),
    };
  }

  // ---- archive corpus (read-only; see the header) ---------------------------

  /** The freshness key for one archive file, or undefined if never indexed.
   *  The scan driver compares it against the file's stat to decide skip vs
   *  reindex. */
  archiveFileState(sessionId: string): { mtimeMs: number; size: number } | undefined {
    const row = this.db
      .prepare(`SELECT mtime_ms AS m, size AS s FROM archive_sessions WHERE session_id = ?`)
      .get(sessionId) as { m: number; s: number } | undefined;
    return row ? { mtimeMs: row.m, size: row.s } : undefined;
  }

  /** Replace one archive session wholesale: drop its rows, insert the fresh
   *  extraction, upsert the metadata. Idempotent — reindexing an unchanged
   *  file yields the identical rows, never duplicates. */
  indexArchiveSession(meta: ArchiveSessionMeta, rows: readonly ArchiveRow[]): void {
    this.dropArchiveSession(meta.sessionId);
    const ins = this.db.prepare(
      `INSERT INTO archive_fts (text, session_id, ordinal, message_uuid, role, ts) VALUES (?,?,?,?,?,?)`,
    );
    for (const r of rows) ins.run(r.text, meta.sessionId, r.ordinal, r.messageUuid, r.role, r.ts);
    this.db
      .prepare(
        `INSERT INTO archive_sessions
           (session_id, path, cwd, title, last_ts, message_count, mtime_ms, size)
         VALUES (?,?,?,?,?,?,?,?)
         ON CONFLICT(session_id) DO UPDATE SET
           path = excluded.path, cwd = excluded.cwd, title = excluded.title,
           last_ts = excluded.last_ts, message_count = excluded.message_count,
           mtime_ms = excluded.mtime_ms, size = excluded.size`,
      )
      .run(
        meta.sessionId, meta.path, meta.cwd, meta.title,
        meta.lastTs, meta.messageCount, meta.mtimeMs, meta.size,
      );
  }

  /** Idempotent. Used by reindex; there is no delete RPC — the archive is not
   *  ours to mutate. */
  dropArchiveSession(sessionId: string): void {
    this.db.prepare(`DELETE FROM archive_fts WHERE session_id = ?`).run(sessionId);
    this.db.prepare(`DELETE FROM archive_sessions WHERE session_id = ?`).run(sessionId);
  }

  archiveSession(sessionId: string): ArchiveSessionMeta | undefined {
    const r = this.db
      .prepare(`SELECT * FROM archive_sessions WHERE session_id = ?`)
      .get(sessionId) as Record<string, unknown> | undefined;
    return r ? rowToArchiveMeta(r) : undefined;
  }

  /** Every archive session's (id, cwd) — the router runs these through the
   *  workspace matcher to resolve scope, exactly as it does live rows. */
  archiveSessionCwds(): { sessionId: string; cwd: string }[] {
    const rows = this.db
      .prepare(`SELECT session_id, cwd FROM archive_sessions`)
      .all() as Record<string, unknown>[];
    return rows.map((r) => ({ sessionId: r.session_id as string, cwd: r.cwd as string }));
  }

  /** Mirror of `search()` over the archive corpus. `allowedSessionIds` is the
   *  router's scope + dedupe resolution; an empty set never reaches SQL. */
  searchArchive(q: SearchQuery, allowedSessionIds: readonly string[]): { total: number; hits: SearchHit[] } {
    const empty = { total: 0, hits: [] as SearchHit[] };
    if (allowedSessionIds.length === 0) return empty;
    const match = buildMatchExpression(q.query);
    if (!match) return empty;

    const where = [`archive_fts MATCH ?`, `session_id IN (${allowedSessionIds.map(() => "?").join(",")})`];
    const args: (string | number)[] = [match, ...allowedSessionIds];
    if (q.role) { where.push(`role = ?`); args.push(q.role); }
    if (q.since !== undefined) { where.push(`ts >= ?`); args.push(q.since); }
    const whereSql = where.join(" AND ");

    const totalRow = this.db
      .prepare(`SELECT COUNT(*) AS c FROM archive_fts WHERE ${whereSql}`)
      .get(...args) as { c: number };

    const order = q.sort === "recent" ? `ts DESC, ordinal DESC` : `bm25(archive_fts), ts DESC`;
    const rows = this.db
      .prepare(
        `SELECT session_id, message_uuid, ordinal, role, ts,
                snippet(archive_fts, 0, ?, ?, ?, ${SNIPPET_TOKENS}) AS snippet,
                substr(text, 1, ?) AS text
         FROM archive_fts
         WHERE ${whereSql}
         ORDER BY ${order}
         LIMIT ? OFFSET ?`,
      )
      .all(SNIPPET_OPEN, SNIPPET_CLOSE, "…", SEARCH_TEXT_CAP, ...args, q.limit, q.offset) as Record<string, unknown>[];

    return {
      total: totalRow?.c ?? 0,
      hits: rows.map((r) => ({
        sessionId: r.session_id as string,
        // The archive's stand-ins for the live deep-link tuple.
        messageId: r.message_uuid as string,
        seq: r.ordinal as number,
        role: r.role as MessageRole,
        ts: r.ts as number,
        snippet: r.snippet as string,
        text: r.text as string,
      })),
    };
  }

  /** One archive session's transcript page, ascending by ordinal — served from
   *  the index, never the file. Full text (uncapped): this IS the transcript
   *  fetch, not a peek. */
  archiveMessages(
    sessionId: string,
    limit: number,
    offset: number,
  ): { total: number; messages: { ordinal: number; role: MessageRole; ts: number; text: string }[] } {
    const total = (this.db
      .prepare(`SELECT COUNT(*) AS c FROM archive_fts WHERE session_id = ?`)
      .get(sessionId) as { c: number } | undefined)?.c ?? 0;
    const rows = this.db
      .prepare(
        `SELECT ordinal, role, ts, text FROM archive_fts
         WHERE session_id = ? ORDER BY ordinal ASC LIMIT ? OFFSET ?`,
      )
      .all(sessionId, limit, offset) as Record<string, unknown>[];
    return {
      total,
      messages: rows.map((r) => ({
        ordinal: r.ordinal as number,
        role: r.role as MessageRole,
        ts: r.ts as number,
        text: r.text as string,
      })),
    };
  }

  close(): void {
    this.db.close();
  }
}

function rowToArchiveMeta(r: Record<string, unknown>): ArchiveSessionMeta {
  return {
    sessionId: r.session_id as string,
    path: r.path as string,
    cwd: r.cwd as string,
    title: (r.title as string | null) ?? null,
    lastTs: r.last_ts as number,
    messageCount: r.message_count as number,
    mtimeMs: r.mtime_ms as number,
    size: r.size as number,
  };
}
