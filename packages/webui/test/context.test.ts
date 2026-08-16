// context.test.ts — the context-chip helper + its live/seed precedence.
//
// Two sources, one number: the session.list row's persisted occupancy (the
// COLD seed, additive 2026-07-25) and the `usage` block on message.complete
// (live). Live wins once seen; switching sessions reseeds because both inputs
// are per-session. Unknown on both sides renders NOTHING — never a fabricated
// percentage.

import { describe, expect, test } from "vitest";
import {
  contextChipLabel,
  contextChipTitle,
  contextFromRow,
  contextFromUsage,
  resolveContext,
} from "../src/components/context.js";
import { applyEvent, emptySessionState } from "../src/gateway/session-state.js";
import type { SessionSummary } from "../src/gateway/types.js";

const sess = (id: string, ctx?: { used?: number | null; max?: number | null }): SessionSummary => ({
  session_id: id,
  lifecycle: "active",
  run_state: "idle",
  last_seq: 0,
  seen_seq: 0,
  ...(ctx === undefined ? {} : { context_used: ctx.used ?? null, context_max: ctx.max ?? null }),
});

describe("reading a context occupancy", () => {
  test("a row with both halves derives the percentage", () => {
    expect(contextFromRow(sess("a", { used: 42_300, max: 200_000 }))).toEqual({
      used: 42_300,
      max: 200_000,
      percent: 21,
    });
  });

  test("percent clamps at 100 when the turn overran the window", () => {
    expect(contextFromRow(sess("a", { used: 250_000, max: 200_000 }))!.percent).toBe(100);
  });

  test("unknown in every shape reads as null — no fabricated percentage", () => {
    expect(contextFromRow(undefined)).toBeNull();
    expect(contextFromRow(sess("a"))).toBeNull(); // old daemon: fields absent
    expect(contextFromRow(sess("a", { used: null, max: null }))).toBeNull(); // never ran
    expect(contextFromRow(sess("a", { used: 9_000, max: null }))).toBeNull(); // no window
    expect(contextFromRow(sess("a", { used: null, max: 200_000 }))).toBeNull();
  });

  test("a live usage block reads the same snake_case keys the daemon emits", () => {
    expect(contextFromUsage({ input_tokens: 5, context_used: 60_000, context_max: 200_000 })).toEqual({
      used: 60_000,
      max: 200_000,
      percent: 30,
    });
    expect(contextFromUsage({ input_tokens: 5 })).toBeNull();
    expect(contextFromUsage(undefined)).toBeNull();
    expect(contextFromUsage("nope")).toBeNull();
  });
});

describe("live-vs-seed precedence", () => {
  const row = sess("a", { used: 42_300, max: 200_000 });

  test("the row seeds a cold session — a number without running a turn", () => {
    expect(resolveContext(null, row)!.percent).toBe(21);
  });

  test("a live reading supersedes the seed", () => {
    const live = contextFromUsage({ context_used: 120_000, context_max: 200_000 })!;
    expect(resolveContext(live, row)).toEqual(live);
  });

  test("switching to a session with no live reading falls back to ITS row", () => {
    const other = sess("b", { used: 10_000, max: 200_000 });
    expect(resolveContext(null, other)!.percent).toBe(5);
    // …and to nothing at all when that session has never run.
    expect(resolveContext(null, sess("c"))).toBeNull();
  });

  test("no live reading and an unknown row renders nothing", () => {
    expect(resolveContext(undefined, sess("a"))).toBeNull();
    expect(resolveContext(undefined, undefined)).toBeNull();
  });
});

describe("session state folds the live usage block", () => {
  test("message.complete carries the reading onto the state", () => {
    const s = applyEvent(emptySessionState(), "message.complete", {
      seq: 2,
      message_id: "m1",
      usage: { context_used: 60_000, context_max: 200_000 },
    });
    expect(s.context).toEqual({ used: 60_000, max: 200_000, percent: 30 });
  });

  test("a turn reporting no occupancy leaves the previous reading standing", () => {
    let s = applyEvent(emptySessionState(), "message.complete", {
      seq: 2,
      usage: { context_used: 60_000, context_max: 200_000 },
    });
    s = applyEvent(s, "message.complete", { seq: 3 });
    expect(s.context!.used).toBe(60_000);
  });

  test("session.cleared resets to unknown — a pre-clear number would overstate", () => {
    let s = applyEvent(emptySessionState(), "message.complete", {
      seq: 2,
      usage: { context_used: 60_000, context_max: 200_000 },
    });
    s = applyEvent(s, "session.cleared", {});
    expect(s.context).toBeNull();
    expect(resolveContext(s.context, sess("a"))).toBeNull();
  });
});

describe("chip copy", () => {
  test("label is the percentage; the title carries the token counts", () => {
    const c = contextFromRow(sess("a", { used: 42_300, max: 200_000 }))!;
    expect(contextChipLabel(c)).toBe("21% context");
    expect(contextChipTitle(c)).toBe("42.3k / 200.0k tokens in the context window");
  });
});
