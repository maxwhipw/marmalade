// effort-bounds.test.ts — the webui half of per-model reasoning-effort bounds:
// the ModelsCard bounds editor's patch math (design-lab option B) and the
// effort.clamped transcript line (option E3).
//
// The daemon owns the truth: it CLAMPS rather than rejects, and every write
// here renders the snapshot it returns. What the webui must get right is
//   - patch construction: per-model, one-sided edges omitted, full range = null
//   - degradation: a daemon without model_efforts hides the control entirely
//   - the clamp line's wording, derived from the live model catalog
//   - the clamp record arriving live AND replaying on a cold load

import { describe, expect, test } from "vitest";
import {
  boundsPatch,
  boundsSummary,
  boundsSupported,
  boundsToNotches,
  clampNoticeText,
  readClamp,
} from "../src/components/efforts.js";
import { applyEvent, emptySessionState } from "../src/gateway/session-state.js";
import type { ModelInfo } from "../src/gateway/types.js";

const EFFORTS = ["low", "medium", "high", "xhigh", "max"];
const MODELS: ModelInfo[] = [
  { id: "claude-opus-5", label: "Opus 5" },
  { id: "claude-fable-5", label: "Fable 5" },
];

describe("bounds ↔ notch positions", () => {
  test("unbounded sits at the extremes — the collapsed row reads as before", () => {
    expect(boundsToNotches(null, EFFORTS)).toEqual({ min: 0, max: 4 });
  });

  test("a one-sided bound leaves the other notch at its extreme", () => {
    expect(boundsToNotches({ min: "high" }, EFFORTS)).toEqual({ min: 2, max: 4 });
    expect(boundsToNotches({ max: "medium" }, EFFORTS)).toEqual({ min: 0, max: 1 });
  });

  test("a level this daemon no longer publishes falls back to its extreme", () => {
    expect(boundsToNotches({ min: "ludicrous" }, EFFORTS)).toEqual({ min: 0, max: 4 });
  });

  test("notches never render inverted", () => {
    // Only reachable if the vocabulary reorders under a stored pair; the
    // daemon's own invariant is min <= max.
    expect(boundsToNotches({ min: "max", max: "low" }, EFFORTS)).toEqual({ min: 0, max: 4 });
  });
});

describe("settings.update patch construction", () => {
  test("a two-sided range sends both edges, keyed by model id", () => {
    expect(boundsPatch("claude-opus-5", { min: 2, max: 3 }, EFFORTS)).toEqual({
      "claude-opus-5": { min: "high", max: "xhigh" },
    });
  });

  test("a floor omits max rather than pinning it to today's deepest level", () => {
    expect(boundsPatch("claude-opus-5", { min: 2, max: 4 }, EFFORTS)).toEqual({
      "claude-opus-5": { min: "high" },
    });
  });

  test("a ceiling omits min", () => {
    expect(boundsPatch("claude-fable-5", { min: 0, max: 1 }, EFFORTS)).toEqual({
      "claude-fable-5": { max: "medium" },
    });
  });

  test("full range sends null — delete the entry, don't store a no-op bound", () => {
    expect(boundsPatch("claude-opus-5", { min: 0, max: 4 }, EFFORTS)).toEqual({
      "claude-opus-5": null,
    });
  });

  test("a pinned single level is a legal both-edges bound", () => {
    expect(boundsPatch("claude-opus-5", { min: 3, max: 3 }, EFFORTS)).toEqual({
      "claude-opus-5": { min: "xhigh", max: "xhigh" },
    });
  });

  test("the patch touches exactly one model — others are untouched by omission", () => {
    expect(Object.keys(boundsPatch("claude-opus-5", { min: 1, max: 2 }, EFFORTS))).toEqual([
      "claude-opus-5",
    ]);
  });
});

describe("old-daemon degradation", () => {
  test("no model_efforts in the snapshot hides the control entirely", () => {
    expect(boundsSupported({ default_model: "m" } as never)).toBe(false);
    expect(boundsSupported(null)).toBe(false);
  });

  test("an empty map still means supported — {} is 'no bounds set', not 'no feature'", () => {
    expect(boundsSupported({ model_efforts: {} } as never)).toBe(true);
  });
});

describe("collapsed summary", () => {
  test("unbounded reads plainly, never blank", () => {
    expect(boundsSummary(null)).toBe("Any thinking level");
    expect(boundsSummary({})).toBe("Any thinking level");
  });

  test("one-sided and two-sided bounds each get their own phrasing", () => {
    expect(boundsSummary({ min: "high" })).toBe("At least High");
    expect(boundsSummary({ max: "medium" })).toBe("At most Medium");
    expect(boundsSummary({ min: "high", max: "max" })).toBe("High – Max");
    expect(boundsSummary({ min: "high", max: "high" })).toBe("Always High");
  });
});

describe("effort.clamped wording", () => {
  const clamp = { requested: "low", effective: "high", model: "claude-opus-5", bound: "min" as const, limit: "high" };

  test("a floor reads 'minimum' and uses the catalog label", () => {
    expect(clampNoticeText(clamp, MODELS)).toBe("Thinking adjusted to High — Opus 5 minimum");
  });

  test("a ceiling reads 'limit'", () => {
    expect(
      clampNoticeText(
        { requested: "max", effective: "medium", model: "claude-fable-5", bound: "max", limit: "medium" },
        MODELS,
      ),
    ).toBe("Thinking adjusted to Medium — Fable 5 limit");
  });

  test("a model the catalog no longer lists shows its raw id, not a blank", () => {
    expect(clampNoticeText({ ...clamp, model: "claude-gone-1" }, MODELS)).toBe(
      "Thinking adjusted to High — claude-gone-1 minimum",
    );
  });

  test("an unknown effort level passes through unprettified", () => {
    expect(clampNoticeText({ ...clamp, effective: "ludicrous" }, MODELS)).toBe(
      "Thinking adjusted to ludicrous — Opus 5 minimum",
    );
  });

  test("a payload missing a field is not rendered half-blank", () => {
    expect(readClamp({ requested: "low", effective: "high" })).toBeNull();
    expect(readClamp({ ...clamp, bound: "sideways" })).toBeNull();
    expect(readClamp({ ...clamp })).toEqual(clamp);
  });
});

describe("effort.clamped in the transcript", () => {
  const event = {
    requested: "low",
    effective: "high",
    model: "claude-opus-5",
    bound: "min",
    limit: "high",
    seq: 2,
  };

  test("live receipt appends a muted notice row and advances the cursor", () => {
    const s = applyEvent(emptySessionState(), "effort.clamped", event);
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0].role).toBe("notice");
    expect(s.messages[0].clamp).toEqual({
      requested: "low",
      effective: "high",
      model: "claude-opus-5",
      bound: "min",
      limit: "high",
    });
    expect(s.lastSeq).toBe(2);
  });

  test("cold-load replay lands it in seq order, before the turn it preceded", () => {
    let s = emptySessionState();
    s = applyEvent(s, "effort.clamped", { ...event, seq: 1 });
    s = applyEvent(s, "message.user", { message_id: "u1", text: "hi", seq: 2 });
    s = applyEvent(s, "message.start", { message_id: "a1", seq: 3 });
    s = applyEvent(s, "message.complete", { message_id: "a1", seq: 4 });
    expect(s.messages.map((m) => m.role)).toEqual(["notice", "user", "assistant"]);
  });

  test("a replayed duplicate at or below the watermark never doubles the line", () => {
    let s = applyEvent(emptySessionState(), "effort.clamped", event);
    s = applyEvent(s, "effort.clamped", event);
    expect(s.messages).toHaveLength(1);
  });

  test("an unreadable payload advances the cursor without inventing a line", () => {
    const s = applyEvent(emptySessionState(), "effort.clamped", { seq: 7, bound: "min" });
    expect(s.messages).toHaveLength(0);
    expect(s.lastSeq).toBe(7);
  });

  test("unknown events are still tolerated — the clamp case didn't narrow the default", () => {
    const s = applyEvent(emptySessionState(), "some.future.event", { seq: 9 });
    expect(s.messages).toHaveLength(0);
    expect(s.lastSeq).toBe(9);
  });
});
