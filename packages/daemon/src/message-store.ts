// message-store.ts — the `messages` table (identity plan P1).
//
// One row per DOMAIN message (user + assistant): the immutable messageId, the
// per-session seq, origin, and timing/status. Message CONTENT is not stored
// here — the transcript NDJSON cache holds the event stream (text included);
// this table is the identity/ordering INDEX, same division of labor as the
// sessions table (SQLite = index, NDJSON = transcript).
//
// The harness's own message uuid is captured PRIVATELY in
// harness_message_uuid — it never crosses the gateway to a client (the
// two-id-spaces rule: domain ids are stable, harness ids churn).

import type { DatabaseSync } from "node:sqlite";
import type { Origin } from "./identity.js";

export type MessageRole = "user" | "assistant";
export type MessageStatus = "streaming" | "complete" | "incomplete" | "error";

export interface MessageRecord {
  messageId: string;
  sessionId: string;
  role: MessageRole;
  /** assistant → the user message it answers; user → null (no separate turnId
   *  — parentMessageId expresses the turn, per the identity plan). */
  parentMessageId: string | null;
  origin: Origin;
  /** Monotonic per-session ordering key. seq orders; timestamps are metadata —
   *  NEVER order by wall-clock (locked decision, identity plan). */
  seq: number;
  startedAt: number;
  endedAt: number | null;
  status: MessageStatus;
  /** PRIVATE — never leaks to clients. */
  harnessMessageUuid: string | null;
  /** True for a mid-turn steer message (session.steer). Turn boundaries are
   *  user rows with steered=false — session.undo pops from the last one. */
  steered: boolean;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS messages (
  message_id        TEXT PRIMARY KEY,
  session_id        TEXT NOT NULL,
  role              TEXT NOT NULL,
  parent_message_id TEXT,
  origin_user_id    TEXT NOT NULL,
  origin_device_id  TEXT NOT NULL,
  origin_platform   TEXT NOT NULL,
  origin_source     TEXT NOT NULL,
  origin_tz_offset  INTEGER,
  seq               INTEGER NOT NULL,
  started_at        INTEGER NOT NULL,
  ended_at          INTEGER,
  status            TEXT NOT NULL,
  harness_message_uuid TEXT,
  steered           INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_messages_session_seq ON messages(session_id, seq);
CREATE INDEX IF NOT EXISTS idx_messages_status      ON messages(status);
`;

export class MessageStore {
  constructor(private db: DatabaseSync) {
    this.db.exec(SCHEMA);
    // Additive migration for dbs created before the column existed (same
    // duplicate-column-only swallow as SessionManager.migrate).
    try {
      this.db.exec(`ALTER TABLE messages ADD COLUMN steered INTEGER NOT NULL DEFAULT 0`);
    } catch (e) {
      if (!/duplicate column/i.test((e as Error).message)) throw e;
    }
  }

  insert(rec: Omit<MessageRecord, "harnessMessageUuid" | "steered"> & { steered?: boolean }): void {
    this.db
      .prepare(
        `INSERT INTO messages
         (message_id, session_id, role, parent_message_id,
          origin_user_id, origin_device_id, origin_platform, origin_source,
          origin_tz_offset, seq, started_at, ended_at, status, steered)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      )
      .run(
        rec.messageId, rec.sessionId, rec.role, rec.parentMessageId,
        rec.origin.userId, rec.origin.deviceId, rec.origin.platform,
        rec.origin.source, rec.origin.tzOffset ?? null,
        rec.seq, rec.startedAt, rec.endedAt, rec.status,
        rec.steered ? 1 : 0,
      );
  }

  /** Terminal status transition. Immutable identity: only status/ended_at ever
   *  change on a message row — never its id, seq, or origin. */
  setStatus(messageId: string, status: MessageStatus, endedAt: number): void {
    this.db
      .prepare(`UPDATE messages SET status = ?, ended_at = ? WHERE message_id = ?`)
      .run(status, endedAt, messageId);
  }

  bindHarnessUuid(messageId: string, uuid: string): void {
    this.db
      .prepare(`UPDATE messages SET harness_message_uuid = ? WHERE message_id = ?`)
      .run(uuid, messageId);
  }

  get(messageId: string): MessageRecord | undefined {
    const row = this.db.prepare(`SELECT * FROM messages WHERE message_id = ?`).get(messageId) as
      | Record<string, unknown>
      | undefined;
    return row ? rowToRecord(row) : undefined;
  }

  /** The assistant message that ANSWERS a user message — the first row naming
   *  it as parent (a turn has one answer; lowest seq wins if a harness ever
   *  emits more). Feeds search.messages' reply preview: a user hit shows the
   *  exchange, not a lone question. */
  replyTo(userMessageId: string): MessageRecord | undefined {
    const row = this.db
      .prepare(
        `SELECT * FROM messages WHERE parent_message_id = ? AND role = 'assistant'
         ORDER BY seq ASC LIMIT 1`,
      )
      .get(userMessageId) as Record<string, unknown> | undefined;
    return row ? rowToRecord(row) : undefined;
  }

  /** All of a session's messages in seq order (the only correct order). */
  list(sessionId: string): MessageRecord[] {
    const rows = this.db
      .prepare(`SELECT * FROM messages WHERE session_id = ? ORDER BY seq ASC`)
      .all(sessionId) as Record<string, unknown>[];
    return rows.map(rowToRecord);
  }

  /** Highest seq issued for a session (0 if none) — seeds the counter on
   *  resume/restart so seq NEVER goes backward. */
  maxSeq(sessionId: string): number {
    const row = this.db
      .prepare(`SELECT MAX(seq) AS m FROM messages WHERE session_id = ?`)
      .get(sessionId) as { m: number | null };
    return row?.m ?? 0;
  }

  /** Highest message seq per session, one query — decorates session.list with
   *  last_seq so a client can derive unread (last_seq > this device's
   *  seen_seq). Message seq only: intermediate event stamps (deltas, tool
   *  progress) don't count as "something new to read". */
  maxSeqBySession(): Map<string, number> {
    const rows = this.db
      .prepare(`SELECT session_id, MAX(seq) AS m FROM messages GROUP BY session_id`)
      .all() as { session_id: string; m: number }[];
    return new Map(rows.map((r) => [r.session_id, r.m]));
  }

  /** session.delete cascade: drop every identity row the session owns. */
  deleteSession(sessionId: string): number {
    const res = this.db
      .prepare(`DELETE FROM messages WHERE session_id = ?`)
      .run(sessionId);
    return Number(res.changes ?? 0);
  }

  /** A session's message ids that are NOT cleanly complete, split by whether
   *  the message is still being written. Feeds transcript compaction: a
   *  `streaming` message must not be folded yet (its deltas are still
   *  arriving), while `incomplete`/`error` ones never finished cleanly, so
   *  their folded event is flagged `partial`. */
  unsettledIds(sessionId: string): { streaming: Set<string>; partial: Set<string> } {
    const rows = this.db
      .prepare(`SELECT message_id, status FROM messages WHERE session_id = ? AND status <> 'complete'`)
      .all(sessionId) as { message_id: string; status: MessageStatus }[];
    const streaming = new Set<string>();
    const partial = new Set<string>();
    for (const r of rows) (r.status === "streaming" ? streaming : partial).add(r.message_id);
    return { streaming, partial };
  }

  /** Daemon-restart reconcile: a message still `streaming` from a prior
   *  process was interrupted — mark it incomplete (the id persists; only the
   *  status field records the interruption). Returns count. */
  closeAllOpen(endedAt: number): number {
    const res = this.db
      .prepare(`UPDATE messages SET status = 'incomplete', ended_at = ? WHERE status = 'streaming'`)
      .run(endedAt);
    return Number(res.changes ?? 0);
  }
}

function rowToRecord(row: Record<string, unknown>): MessageRecord {
  return {
    messageId: row.message_id as string,
    sessionId: row.session_id as string,
    role: row.role as MessageRole,
    parentMessageId: (row.parent_message_id as string | null) ?? null,
    origin: {
      userId: row.origin_user_id as string,
      deviceId: row.origin_device_id as string,
      platform: row.origin_platform as string,
      source: row.origin_source as "text" | "voice",
      ...(row.origin_tz_offset === null ? {} : { tzOffset: row.origin_tz_offset as number }),
    },
    seq: row.seq as number,
    startedAt: row.started_at as number,
    endedAt: (row.ended_at as number | null) ?? null,
    status: row.status as MessageStatus,
    harnessMessageUuid: (row.harness_message_uuid as string | null) ?? null,
    steered: Boolean(row.steered),
  };
}
