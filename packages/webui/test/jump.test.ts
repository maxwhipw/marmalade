// jump.test.ts — open-at-seq deep linking + the match navigator (design-lab
// labs/session-search, lab 3 frame 1).
//
// The interesting behavior is all TIMING: the transcript arrives by event
// replay, so a jump is requested before its target exists, and the stick-to-
// bottom autoscroll fires on every replayed frame. Both live in pure helpers
// (src/components/jump.ts) precisely so they can be tested here — jsdom can't
// scroll, so a DOM assertion would prove nothing.

import { describe, expect, test } from "vitest";
import {
  MATCH_CAP,
  collectMatchRefs,
  collectSessionMatches,
  jumpPillText,
  matchIndexOf,
  openTargetFromHit,
  resolveJumpAnchor,
  shouldAutoscroll,
  stepMatchIndex,
  type MatchRef,
} from "../src/components/jump.js";

const msg = (id: string, seq: number) => ({ id, seq });

// ── Landing the jump (replay timing) ────────────────────────────────────────

describe("resolveJumpAnchor", () => {
  const transcript = [msg("a", 2), msg("b", 5), msg("c", 9)];

  test("anchors on the exact message_id when it has replayed", () => {
    expect(resolveJumpAnchor(transcript, { messageId: "b", seq: 5 })).toBe("b");
  });

  test("an empty transcript is 'not yet' — never a wrong anchor", () => {
    expect(resolveJumpAnchor([], { messageId: "b", seq: 5 })).toBeNull();
  });

  test("waits while the target is still replaying (nothing at or past its seq)", () => {
    expect(resolveJumpAnchor([msg("a", 2)], { messageId: "b", seq: 5 })).toBeNull();
  });

  test("falls back to the NEAREST message at or past the seq when the id never renders", () => {
    // The id isn't in the transcript (compacted away, or not a bubble at all),
    // but replay is seq-ordered, so seq 9 existing means seq 6 has gone by.
    expect(resolveJumpAnchor(transcript, { messageId: "gone", seq: 6 })).toBe("c");
  });

  test("the fallback picks the nearest, not the last", () => {
    expect(resolveJumpAnchor(transcript, { seq: 3 })).toBe("b");
  });

  test("a seq-only target works (no message_id at all)", () => {
    expect(resolveJumpAnchor(transcript, { seq: 9 })).toBe("c");
    expect(resolveJumpAnchor(transcript, { seq: 10 })).toBeNull();
  });

  test("the exact id wins even when an earlier message also satisfies the seq", () => {
    // A stale seq on the hit must not drag the landing backwards.
    expect(resolveJumpAnchor(transcript, { messageId: "c", seq: 1 })).toBe("c");
  });
});

describe("shouldAutoscroll", () => {
  test("suppressed while a jump is pending — it would fight the replay", () => {
    expect(shouldAutoscroll({ pendingJump: true, navigatorOpen: true })).toBe(false);
    expect(shouldAutoscroll({ pendingJump: true, navigatorOpen: false })).toBe(false);
  });

  test("suppressed while the navigator is open — new content must not yank you off a match", () => {
    expect(shouldAutoscroll({ pendingJump: false, navigatorOpen: true })).toBe(false);
  });

  test("restored once the navigator is dismissed", () => {
    expect(shouldAutoscroll({ pendingJump: false, navigatorOpen: false })).toBe(true);
  });
});

describe("jumpPillText", () => {
  const transcript = [msg("a", 1), msg("b", 2), msg("c", 3), msg("d", 4), msg("e", 5), msg("f", 6), msg("g", 7)];

  test("counts message positions back from the bottom", () => {
    expect(jumpPillText(transcript, "a")).toBe("jumped 6 messages back");
  });

  test("says nothing for a short hop — you can see where you went", () => {
    expect(jumpPillText(transcript, "e")).toBeNull();
    expect(jumpPillText(transcript, "g")).toBeNull();
  });

  test("an unknown anchor yields no pill rather than a wrong number", () => {
    expect(jumpPillText(transcript, "zz")).toBeNull();
  });
});

// ── Search hit → open target (the threading) ────────────────────────────────

describe("openTargetFromHit", () => {
  const hit = { session_id: "s1", message_id: "m4", seq: 48, snippet: "…", text: "…" };

  test("carries session + message + seq + the query that found it", () => {
    expect(openTargetFromHit(hit, "seen_at monotonic")).toEqual({
      sessionId: "s1",
      messageId: "m4",
      seq: 48,
      query: "seen_at monotonic",
    });
  });

  test("is a JumpTarget too — the same object drives the first jump", () => {
    const t = openTargetFromHit(hit, "q");
    expect(resolveJumpAnchor([msg("m4", 48)], t)).toBe("m4");
  });
});

// ── The navigator's match list ──────────────────────────────────────────────

describe("collectMatchRefs", () => {
  test("re-sorts the daemon's RANK order into transcript order", () => {
    const refs = collectMatchRefs([
      { message_id: "m9", seq: 90 },
      { message_id: "m1", seq: 10 },
      { message_id: "m4", seq: 40 },
    ]);
    expect(refs.map((r) => r.messageId)).toEqual(["m1", "m4", "m9"]);
  });

  test("de-dupes by message (a message can't be two navigator stops)", () => {
    const refs = collectMatchRefs([
      { message_id: "m1", seq: 10 },
      { message_id: "m1", seq: 10 },
    ]);
    expect(refs).toHaveLength(1);
  });
});

describe("matchIndexOf", () => {
  const matches: MatchRef[] = [
    { messageId: "m1", seq: 10 },
    { messageId: "m4", seq: 40 },
    { messageId: "m9", seq: 90 },
  ];

  test("opens on the match you clicked", () => {
    expect(matchIndexOf(matches, { messageId: "m4", seq: 40 })).toBe(1);
  });

  test("falls back to the first match at or past the seq", () => {
    expect(matchIndexOf(matches, { messageId: "unknown", seq: 41 })).toBe(2);
  });

  test("never returns -1 — the navigator always opens somewhere", () => {
    expect(matchIndexOf(matches, { messageId: "unknown", seq: 999 })).toBe(0);
    expect(matchIndexOf([], { messageId: "m1", seq: 10 })).toBe(0);
  });
});

describe("stepMatchIndex", () => {
  test("walks the list", () => {
    expect(stepMatchIndex(0, 3, 1)).toBe(1);
    expect(stepMatchIndex(2, 3, -1)).toBe(1);
  });

  test("clamps at both ends — no wraparound (the arrows disable there)", () => {
    expect(stepMatchIndex(2, 3, 1)).toBe(2);
    expect(stepMatchIndex(0, 3, -1)).toBe(0);
  });

  test("an empty match list can only be index 0", () => {
    expect(stepMatchIndex(0, 0, 1)).toBe(0);
  });
});

// ── Paging the match list ───────────────────────────────────────────────────

describe("collectSessionMatches", () => {
  /** A fake search.messages: `total` matches, answered a page at a time. */
  const fakeSearch = (total: number, calls: { offset: number; limit: number }[] = []) =>
    async (offset: number, limit: number) => {
      calls.push({ offset, limit });
      const hits = [];
      for (let i = offset; i < Math.min(total, offset + limit); i++) {
        hits.push({ message_id: `m${i}`, seq: (i + 1) * 10 });
      }
      return { hits, total };
    };

  test("one page covers a small session — no second round-trip", async () => {
    const calls: { offset: number; limit: number }[] = [];
    const c = await collectSessionMatches(fakeSearch(3, calls), 250, 50);
    expect(calls).toEqual([{ offset: 0, limit: 50 }]);
    expect(c.matches).toHaveLength(3);
    expect(c.total).toBe(3);
    expect(c.capped).toBe(false);
  });

  test("pages until the total is collected, in transcript order", async () => {
    const calls: { offset: number; limit: number }[] = [];
    const c = await collectSessionMatches(fakeSearch(120, calls), 250, 50);
    expect(calls.map((x) => x.offset)).toEqual([0, 50, 100]);
    expect(c.matches).toHaveLength(120);
    expect(c.matches[0].seq).toBeLessThan(c.matches[119].seq);
    expect(c.capped).toBe(false);
  });

  test("stops at the cap and says so — the count stays the daemon's honest total", async () => {
    const calls: { offset: number; limit: number }[] = [];
    const c = await collectSessionMatches(fakeSearch(900, calls), 100, 50);
    expect(calls.map((x) => x.offset)).toEqual([0, 50]);
    expect(c.matches).toHaveLength(100);
    expect(c.total).toBe(900); // "n / 900", not a fabricated 100
    expect(c.capped).toBe(true);
  });

  test("a last page shorter than the cap ends the walk (no infinite paging)", async () => {
    const calls: { offset: number; limit: number }[] = [];
    // total lies high but the pages run dry — must not loop to the cap.
    await collectSessionMatches(
      async (offset, limit) => {
        calls.push({ offset, limit });
        return { hits: offset === 0 ? [{ message_id: "m0", seq: 10 }] : [], total: 99 };
      },
      250,
      50,
    );
    expect(calls).toHaveLength(2);
  });

  test("the default cap is a bounded number of round-trips", () => {
    expect(MATCH_CAP).toBe(250);
  });
});
