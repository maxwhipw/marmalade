import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync, mkdirSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { MessageStore } from "../dist/message-store.js";
import { SessionManager } from "../dist/session-manager.js";

// The messages table (P1) — tested over a REAL FILE db (the build-review
// meta-lesson: :memory:-only tests hid the resume crash).

const ORIGIN = { userId: "owner", deviceId: "d1", platform: "cli", source: "text" as const };

function row(messageId: string, sessionId: string, seq: number, status = "streaming") {
  return {
    messageId, sessionId, role: "assistant" as const, parentMessageId: null,
    origin: ORIGIN, seq, startedAt: 1000 + seq, endedAt: null, status: status as any,
  };
}

test("file db: insert/get/list round-trip, seq-ordered, schema idempotent on reopen", () => {
  const dir = join(tmpdir(), `mms-${randomUUID()}`);
  mkdirSync(dir, { recursive: true });
  const dbPath = join(dir, "m.db");
  try {
    let db = new DatabaseSync(dbPath);
    let store = new MessageStore(db);
    store.insert(row("m2", "S", 2));
    store.insert(row("m1", "S", 1));
    store.insert(row("other", "S2", 1));
    assert.deepEqual(store.list("S").map((m) => m.messageId), ["m1", "m2"], "seq order, session-scoped");
    assert.equal(store.maxSeq("S"), 2);
    assert.equal(store.maxSeq("empty"), 0);
    db.close();

    // Reopen: CREATE IF NOT EXISTS must not clobber or throw.
    db = new DatabaseSync(dbPath);
    store = new MessageStore(db);
    assert.equal(store.list("S").length, 2);
    db.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("duplicate message_id is rejected by the PK — ids are minted once, never reused", () => {
  const store = new MessageStore(new DatabaseSync(":memory:"));
  store.insert(row("dup", "S", 1));
  assert.throws(() => store.insert(row("dup", "S", 2)), /UNIQUE|PRIMARY/i);
});

test("closeAllOpen marks streaming rows incomplete (daemon-restart reconcile)", () => {
  const store = new MessageStore(new DatabaseSync(":memory:"));
  store.insert(row("open1", "S", 1, "streaming"));
  store.insert(row("done", "S", 2, "complete"));
  store.insert(row("open2", "S2", 1, "streaming"));
  assert.equal(store.closeAllOpen(9999), 2);
  assert.equal(store.get("open1")!.status, "incomplete");
  assert.equal(store.get("open1")!.endedAt, 9999);
  assert.equal(store.get("done")!.status, "complete", "closed rows untouched");
});

test("tz offset round-trips; absence stays absent", () => {
  const store = new MessageStore(new DatabaseSync(":memory:"));
  store.insert({ ...row("tz", "S", 1), origin: { ...ORIGIN, tzOffset: -420 } });
  store.insert(row("notz", "S", 2));
  assert.equal(store.get("tz")!.origin.tzOffset, -420);
  assert.equal("tzOffset" in store.get("notz")!.origin, false);
});

test("SessionManager owns a MessageStore over the SAME file db; orphan reconcile closes open messages", () => {
  const dir = join(tmpdir(), `mms-${randomUUID()}`);
  try {
    const m = new SessionManager(join(dir, "sessions.db"));
    m.messages.insert(row("stream", "S", 1, "streaming"));
    m.markOrphansExited(5000);
    assert.equal(m.messages.get("stream")!.status, "incomplete");
    m.close();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
