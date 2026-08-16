// model-picker.test.ts — the pure "Default" row label resolution (spec view 1).
//
// The picker's Default row names the daemon's new-session default_model when
// one is advertised ("Default (Opus 4.8)"), resolving the id to its human
// label; an unknown default degrades to a bare "Default" — today's exact
// behavior. This mirrors the Android client's placeholder resolution.

import { describe, expect, test } from "vitest";
import { defaultOptionLabel } from "../src/components/ModelPicker.js";
import type { ModelInfo } from "../src/gateway/types.js";

const models: ModelInfo[] = [
  { id: "claude-opus-4-8", label: "Opus 4.8" },
  { id: "claude-sonnet-4-8", label: "Sonnet 4.8" },
];

describe("defaultOptionLabel", () => {
  test("names the default with its human label when the id resolves", () => {
    expect(defaultOptionLabel(models, "claude-opus-4-8")).toBe("Default (Opus 4.8)");
  });

  test("falls back to the raw id when the models list lacks the default", () => {
    // The daemon can advertise a default the picker's list doesn't carry (a
    // model gated off the list); show the id rather than dropping the annotation.
    expect(defaultOptionLabel(models, "claude-haiku-4-8")).toBe("Default (claude-haiku-4-8)");
  });

  test("bare Default when the default is null (daemon advertises none)", () => {
    expect(defaultOptionLabel(models, null)).toBe("Default");
  });

  test("bare Default when the default is undefined (pre-field daemon)", () => {
    expect(defaultOptionLabel(models, undefined)).toBe("Default");
  });
});
