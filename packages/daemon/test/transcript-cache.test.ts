import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync } from "node:fs";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { makeEvent } from "@marmalade/protocol";

test("append then replay round-trips normalized events in order", () => {
  const dir = join(tmpdir(), `mt-${randomUUID()}`);
  try {
    const tc = new TranscriptCache(dir);
    tc.append("s1", makeEvent("session.info", { model: "x" }, "s1"));
    tc.append("s1", makeEvent("message.delta", { text: "he" }, "s1"));
    tc.append("s1", makeEvent("message.delta", { text: "llo" }, "s1"));
    const replayed = tc.replay("s1");
    assert.equal(replayed.length, 3);
    assert.equal(replayed[0].params.type, "session.info");
    assert.equal((replayed[2].params.payload as any).text, "llo");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("sessions are isolated; unknown session replays empty", () => {
  const dir = join(tmpdir(), `mt-${randomUUID()}`);
  try {
    const tc = new TranscriptCache(dir);
    tc.append("a", makeEvent("message.delta", { text: "a" }, "a"));
    assert.equal(tc.replay("b").length, 0);
    assert.equal(tc.replay("a").length, 1);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("path traversal in a session id can't escape the cache dir", () => {
  const dir = join(tmpdir(), `mt-${randomUUID()}`);
  try {
    const tc = new TranscriptCache(dir);
    // Malicious id — separators are sanitized to underscores.
    tc.append("../../etc/passwd", makeEvent("error", {}, "x"));
    assert.equal(tc.replay("../../etc/passwd").length, 1); // stored under a safe name
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
