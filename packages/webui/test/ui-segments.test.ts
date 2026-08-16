// splitUiSegments — the webui's ```marmalade-ui fence hook (dynamic-ui plan
// step 4). Shares docs/dynamic-ui/fixtures/ with @marmalade/ui-tree's suite
// and the Android UiTreeParserTest (same JSON, two renderers).

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { splitUiSegments } from "../src/components/markdown.js";

const fixture = (name: string): string =>
  readFileSync(fileURLToPath(new URL(`../../../docs/dynamic-ui/fixtures/${name}`, import.meta.url)), "utf8");

const fence = (body: string) => "```marmalade-ui\n" + body + "\n```";

describe("splitUiSegments", () => {
  it("splits markdown around a marmalade-ui fence into ordered segments", () => {
    const md = `Here is your trip form:\n\n${fence(fixture("full-vocabulary.json"))}\n\nFill it in.`;
    const segs = splitUiSegments(md);
    expect(segs.map((s) => s.kind)).toEqual(["markdown", "ui", "markdown"]);
    const ui = segs[1];
    if (ui.kind !== "ui") throw new Error("expected ui segment");
    expect(ui.node.kind).toBe("card");
  });

  it("plain markdown yields one markdown segment", () => {
    const segs = splitUiSegments("just **text** with `code`");
    expect(segs).toEqual([{ kind: "markdown", md: "just **text** with `code`" }]);
  });

  it("an unparseable marmalade-ui fence stays markdown (degrades to code block)", () => {
    const segs = splitUiSegments(fence("not json at all"));
    expect(segs.length).toBe(1);
    expect(segs[0].kind).toBe("markdown");
  });

  it("other fences are untouched", () => {
    const segs = splitUiSegments("```bash\necho hi\n```");
    expect(segs.length).toBe(1);
    expect(segs[0].kind).toBe("markdown");
  });

  it("a streaming-truncated fence renders the repaired prefix", () => {
    // No closing fence yet — marked lexes the unterminated fence as code.
    const md = "```marmalade-ui\n" + fixture("truncated-tree.txt");
    const segs = splitUiSegments(md);
    expect(segs[0].kind).toBe("ui");
  });
});
