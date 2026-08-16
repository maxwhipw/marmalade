// TranscriptCache.compact(): fold each message's raw delta run into ONE
// consolidated event at turn end. The live WS stream is untouched — this is
// purely the on-disk cache shape. Contract under test:
//   - order is preserved exactly (prose → tool → prose stays that way)
//   - idempotent (compact twice === compact once)
//   - lastSeq() is unchanged (it seeds the seq counter on resume)
//   - skipMessageIds leaves a still-streaming run raw
//   - partialMessageIds flags a message that never completed cleanly
//   - thinking/reasoning fold as their own streams, not into message text
//   - corrupt lines survive, and truncateFromMessages still cuts correctly
//   - renderSessionTurns renders identically before and after

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync, readFileSync, writeFileSync, appendFileSync, existsSync } from "node:fs";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { renderSessionTurns } from "../dist/session-digest.js";

type Ev = { jsonrpc: "2.0"; method: "event"; params: { type: string; payload: Record<string, unknown>; session_id: string } };

const ev = (type: string, payload: Record<string, unknown>): Ev =>
  ({ jsonrpc: "2.0", method: "event", params: { type, payload, session_id: "s1" } });

/** Run a body with a throwaway cache dir. */
function withCache(body: (tc: any, dir: string) => void): void {
  const dir = join(tmpdir(), `mtc-${randomUUID()}`);
  try {
    body(new TranscriptCache(dir), dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const pathFor = (dir: string, id = "s1") => join(dir, `${id}.ndjson`);
const lines = (dir: string, id = "s1") => readFileSync(pathFor(dir, id), "utf8").split("\n").filter(Boolean);
const parsed = (dir: string, id = "s1") => lines(dir, id).map((l) => JSON.parse(l) as Ev);
const payload = (e: Ev) => e.params.payload as Record<string, any>;

/** A realistic turn, shaped like a live transcript: a user prompt, thinking,
 *  mid-turn prose (own message_id, deltas only, NO message.complete), a tool
 *  call, then the final message with its message.complete. Text is synthetic. */
function turn(): Ev[] {
  return [
    ev("status.update", { seq: 1, ts: 1000, run_state: "running" }),
    ev("message.user", { message_id: "u1", seq: 2, ts: 1001, text: "count the widgets", origin: { source: "text", device_id: "d" } }),
    ev("message.start", { message_id: "a1", seq: 3, ts: 1002 }),
    ev("thinking.delta", { message_id: "a1", seq: 4, ts: 1003, text: "the user " }),
    ev("thinking.delta", { message_id: "a1", seq: 5, ts: 1004, text: "wants a count" }),
    ev("message.delta", { message_id: "a1", seq: 6, ts: 1005, text: "Let me " }),
    ev("message.delta", { message_id: "a1", seq: 7, ts: 1006, text: "check " }),
    ev("message.delta", { message_id: "a1", seq: 8, ts: 1007, text: "that." }),
    ev("tool.start", { message_id: "a1", seq: 9, ts: 1008, id: "t1", name: "Bash", input: { command: "count" } }),
    ev("tool.complete", { message_id: "a1", seq: 10, ts: 1009, tool_use_id: "t1" }),
    ev("message.start", { message_id: "a2", seq: 11, ts: 1010 }),
    ev("message.delta", { message_id: "a2", seq: 12, ts: 1011, text: "There are " }),
    ev("message.delta", { message_id: "a2", seq: 13, ts: 1012, text: "seven " }),
    ev("message.delta", { message_id: "a2", seq: 14, ts: 1013, text: "widgets." }),
    ev("message.complete", { message_id: "a2", seq: 15, ts: 1014, text: "There are seven widgets." }),
    ev("status.update", { seq: 16, ts: 1015, run_state: "idle" }),
  ];
}

function seed(tc: any, events: Ev[], id = "s1"): void {
  for (const e of events) tc.append(id, e);
}

test("compact folds each delta run into one event and preserves order", () => {
  withCache((tc, dir) => {
    seed(tc, turn());
    assert.equal(lines(dir).length, 16);
    tc.compact("s1");

    const out = parsed(dir);
    // 8 delta lines (2 thinking + 3 + 3 message) collapse to 3 events.
    assert.deepEqual(
      out.map((e) => e.params.type),
      [
        "status.update", "message.user", "message.start",
        "thinking.delta", "message.delta",       // a1's two streams, in place
        "tool.start", "tool.complete",
        "message.start", "message.delta",        // a2's prose stays AFTER the tool
        "message.complete", "status.update",
      ],
    );
    const think = out[3], prose1 = out[4], prose2 = out[8];
    assert.equal(payload(think).text, "the user wants a count");
    assert.equal(payload(think).message_id, "a1");
    assert.equal(payload(prose1).text, "Let me check that.");
    assert.equal(payload(prose2).text, "There are seven widgets.");
    // seq = the run's max (so a since_seq replay can't re-send a seen run);
    // ts = the last delta's (when the message actually stopped streaming).
    assert.equal(payload(prose1).seq, 8);
    assert.equal(payload(prose1).ts, 1007);
    assert.equal(payload(think).seq, 5);
    // first_seq..seq is the run's span — session.subscribe uses it to spot a
    // cursor sitting INSIDE a folded run.
    assert.equal(payload(prose1).first_seq, 6);
    assert.equal(payload(prose2).first_seq, 12);
    assert.equal(payload(think).first_seq, 4);
    for (const e of [think, prose1, prose2]) {
      assert.equal(payload(e).consolidated, true);
      assert.equal(payload(e).partial, undefined);
    }
    // Untouched events keep their exact payloads.
    assert.equal(payload(out[9]).text, "There are seven widgets.");
    assert.equal(payload(out[5]).name, "Bash");
  });
});

test("compact is idempotent — second pass is a byte-for-byte no-op", () => {
  withCache((tc, dir) => {
    seed(tc, turn());
    tc.compact("s1");
    const once = readFileSync(pathFor(dir), "utf8");
    tc.compact("s1");
    tc.compact("s1", { partialMessageIds: new Set(["a1"]) }); // already folded: no re-flagging
    assert.equal(readFileSync(pathFor(dir), "utf8"), once);
  });
});

test("lastSeq is unchanged by compaction", () => {
  withCache((tc) => {
    seed(tc, turn());
    const before = tc.lastSeq("s1");
    assert.equal(before, 16);
    tc.compact("s1");
    assert.equal(tc.lastSeq("s1"), before);
  });
});

test("skipMessageIds leaves a still-streaming run raw", () => {
  withCache((tc, dir) => {
    seed(tc, turn());
    tc.compact("s1", { skipMessageIds: new Set(["a2"]) });
    const out = parsed(dir);
    const a2Deltas = out.filter((e) => e.params.type === "message.delta" && payload(e).message_id === "a2");
    assert.equal(a2Deltas.length, 3, "a2 stayed raw");
    for (const d of a2Deltas) assert.equal(payload(d).consolidated, undefined);
    // a1 folded regardless.
    const a1 = out.filter((e) => e.params.type === "message.delta" && payload(e).message_id === "a1");
    assert.equal(a1.length, 1);
    assert.equal(payload(a1[0]).text, "Let me check that.");

    // Once it settles, a later compact folds a2 too — and does not re-touch a1.
    tc.compact("s1");
    const after = parsed(dir).filter((e) => e.params.type === "message.delta");
    assert.equal(after.length, 2);
    assert.equal(payload(after[1]).text, "There are seven widgets.");
  });
});

test("partialMessageIds flags only the messages that never completed", () => {
  withCache((tc, dir) => {
    seed(tc, turn());
    tc.compact("s1", { partialMessageIds: new Set(["a1"]) });
    const byId = new Map(
      parsed(dir).filter((e) => payload(e).consolidated).map((e) => [`${e.params.type}:${payload(e).message_id}`, payload(e)]),
    );
    assert.equal(byId.get("message.delta:a1")!.partial, true);
    assert.equal(byId.get("thinking.delta:a1")!.partial, true, "both of a1's streams are partial");
    assert.equal(byId.get("message.delta:a2")!.partial, undefined);
  });
});

test("thinking/reasoning fold separately from message text of the same message", () => {
  withCache((tc, dir) => {
    seed(tc, [
      ev("message.delta", { message_id: "m", seq: 1, ts: 1, text: "say" }),
      ev("thinking.delta", { message_id: "m", seq: 2, ts: 2, text: "think" }),
      ev("reasoning.delta", { message_id: "m", seq: 3, ts: 3, text: "reason" }),
      ev("message.delta", { message_id: "m", seq: 4, ts: 4, text: "-more" }),
      ev("thinking.delta", { message_id: "m", seq: 5, ts: 5, text: "-more" }),
    ]);
    tc.compact("s1");
    const out = parsed(dir);
    assert.equal(out.length, 3, "three streams, one event each — interleaving doesn't split a run");
    const byType = new Map(out.map((e) => [e.params.type, payload(e)]));
    assert.equal(byType.get("message.delta")!.text, "say-more");
    assert.equal(byType.get("thinking.delta")!.text, "think-more");
    assert.equal(byType.get("reasoning.delta")!.text, "reason");
    // In-place: the first event of each run keeps its slot.
    assert.deepEqual(out.map((e) => e.params.type), ["message.delta", "thinking.delta", "reasoning.delta"]);
  });
});

test("a delta run is never hoisted across an intervening event", () => {
  withCache((tc, dir) => {
    // Same message_id on both sides of a tool call (the pathological case the
    // adapter doesn't currently produce, but the fold must not reorder it).
    seed(tc, [
      ev("message.delta", { message_id: "a", seq: 1, ts: 1, text: "before " }),
      ev("message.delta", { message_id: "a", seq: 2, ts: 2, text: "tool" }),
      ev("tool.start", { message_id: "a", seq: 3, ts: 3, id: "t", name: "Read" }),
      ev("message.delta", { message_id: "a", seq: 4, ts: 4, text: "after " }),
      ev("message.delta", { message_id: "a", seq: 5, ts: 5, text: "tool" }),
    ]);
    tc.compact("s1");
    const out = parsed(dir);
    assert.deepEqual(out.map((e) => e.params.type), ["message.delta", "tool.start", "message.delta"]);
    assert.equal(payload(out[0]).text, "before tool");
    assert.equal(payload(out[2]).text, "after tool");
  });
});

test("corrupt lines survive compaction and don't merge runs across them", () => {
  withCache((tc, dir) => {
    seed(tc, [ev("message.delta", { message_id: "a", seq: 1, ts: 1, text: "x" })]);
    appendFileSync(pathFor(dir), "{not json\n");
    seed(tc, [ev("message.delta", { message_id: "a", seq: 2, ts: 2, text: "y" })]);
    tc.compact("s1");
    const raw = lines(dir);
    assert.equal(raw.length, 3);
    assert.equal(raw[1], "{not json");
    assert.equal(payload(JSON.parse(raw[0])).text, "x");
    assert.equal(payload(JSON.parse(raw[2])).text, "y");
    assert.equal(tc.replay("s1").length, 2, "replay still tolerates the corrupt line");
  });
});

test("a delta with no message_id passes through untouched", () => {
  withCache((tc, dir) => {
    seed(tc, [
      ev("message.delta", { seq: 1, ts: 1, text: "orphan" }),
      ev("message.delta", { message_id: "a", seq: 2, ts: 2, text: "kept" }),
    ]);
    tc.compact("s1");
    const out = parsed(dir);
    assert.equal(out.length, 2);
    assert.equal(payload(out[0]).consolidated, undefined);
    assert.equal(payload(out[0]).first_seq, undefined, "raw deltas carry no first_seq");
    assert.equal(payload(out[1]).consolidated, true);
    assert.equal(payload(out[1]).first_seq, 2);
  });
});

test("compact on a missing file is a no-op", () => {
  withCache((tc, dir) => {
    tc.compact("nope");
    assert.equal(existsSync(pathFor(dir, "nope")), false);
  });
});

test("truncateFromMessages still cuts correctly after compaction", () => {
  withCache((tc, dir) => {
    seed(tc, turn());
    // A second turn to pop.
    seed(tc, [
      ev("message.user", { message_id: "u2", seq: 17, ts: 2000, text: "again", origin: { source: "text", device_id: "d" } }),
      ev("message.delta", { message_id: "a3", seq: 18, ts: 2001, text: "still " }),
      ev("message.delta", { message_id: "a3", seq: 19, ts: 2002, text: "seven." }),
      ev("message.complete", { message_id: "a3", seq: 20, ts: 2003, text: "still seven." }),
    ]);
    tc.compact("s1");
    assert.equal(parsed(dir).length, 14, "consolidated events keep their message_id for the cut");

    tc.truncateFromMessages("s1", new Set(["u2", "a3"]));
    const out = parsed(dir);
    assert.equal(out.length, 11, "cut at the popped turn's message.user");
    assert.equal(out[out.length - 1].params.type, "status.update");
    assert.ok(!out.some((e) => ["u2", "a3"].includes(payload(e).message_id)));
  });
});

test("renderSessionTurns renders identically before and after compaction", () => {
  withCache((tc, dir) => {
    seed(tc, turn());
    const opts = { turns: 10, includeToolCalls: true, includeThinking: true };
    const before = renderSessionTurns(tc.replay("s1"), opts);
    tc.compact("s1");
    const after = renderSessionTurns(tc.replay("s1"), opts);
    assert.equal(after, before);
    assert.match(after, /\[assistant\] Let me check that\./);
    assert.match(after, /\[assistant\] There are seven widgets\./);
    assert.match(after, /\[thinking\] the user wants a count/);
  });
});

test("an empty file and a delta-less file are left alone", () => {
  withCache((tc, dir) => {
    writeFileSync(pathFor(dir), "");
    tc.compact("s1");
    assert.equal(readFileSync(pathFor(dir), "utf8"), "");

    seed(tc, [ev("status.update", { seq: 1, ts: 1, run_state: "idle" })], "s2");
    const before = readFileSync(pathFor(dir, "s2"), "utf8");
    tc.compact("s2");
    assert.equal(readFileSync(pathFor(dir, "s2"), "utf8"), before);
  });
});
