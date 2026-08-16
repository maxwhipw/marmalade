// attachments.test.ts — the composer's staged-attachment helpers. Parity
// target: Android OutboxDrainer.buildSubmitText / desktop use-prompt-actions.

import { describe, expect, it } from "vitest";
import {
  buildSubmitText,
  IMAGE_ONLY_FALLBACK_PROMPT,
  stageFile,
} from "../src/components/attachments.js";

describe("stageFile", () => {
  it("classifies by mime type and mints unique ids", () => {
    const img = stageFile(new File(["x"], "a.png", { type: "image/png" }));
    const doc = stageFile(new File(["x"], "b.txt", { type: "text/plain" }));
    expect(img.kind).toBe("image");
    expect(doc.kind).toBe("file");
    expect(img.id).not.toBe(doc.id);
  });
});

describe("buildSubmitText", () => {
  it("prepends refs double-newline separated", () => {
    expect(buildSubmitText("read this", ["@file:notes/a.txt"], false)).toBe(
      "@file:notes/a.txt\n\nread this",
    );
  });
  it("does not re-prepend a ref already present (retry safety)", () => {
    const text = "@file:notes/a.txt\n\nread this";
    expect(buildSubmitText(text, ["@file:notes/a.txt"], false)).toBe(text);
  });
  it("image-only send falls back to the parity prompt", () => {
    expect(buildSubmitText("", [], true)).toBe(IMAGE_ONLY_FALLBACK_PROMPT);
    expect(buildSubmitText("  ", [], true)).toBe(IMAGE_ONLY_FALLBACK_PROMPT);
  });
  it("refs with no text still submit as refs alone", () => {
    expect(buildSubmitText("", ["@file:a"], false)).toBe("@file:a");
  });
  it("no refs, no images → text passes through", () => {
    expect(buildSubmitText("hi", [], false)).toBe("hi");
  });
});
