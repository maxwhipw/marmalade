// markdown.ts — assistant-text rendering + fenced-code extraction.
//
// Dependency choices (spec: "a markdown renderer + a syntax highlighter chosen
// for size/maintenance"):
//   - marked: ~40KB, zero runtime deps, actively maintained — a full remark/
//     rehype stack is overkill for chat bubbles.
//   - highlight.js: imported from its /lib/core with only a handful of common
//     languages registered, so the bundle carries grammars we actually show,
//     not all ~190.
//
// Untrusted-content posture: marked output is model-authored. We disable raw
// HTML pass-through so a bubble can't inject markup (the artifact panel is the
// ONLY place model HTML renders, and only inside a sandboxed iframe — spec
// view 3). Code is highlighted; everything else is escaped by marked's own
// tokenizer.

import { marked } from "marked";
import { parseUiTree, UI_FENCE_LANG, type UiNode } from "@marmalade/ui-tree";
import hljs from "highlight.js/lib/core";
import bash from "highlight.js/lib/languages/bash";
import javascript from "highlight.js/lib/languages/javascript";
import typescript from "highlight.js/lib/languages/typescript";
import json from "highlight.js/lib/languages/json";
import python from "highlight.js/lib/languages/python";
import xml from "highlight.js/lib/languages/xml";
import css from "highlight.js/lib/languages/css";

hljs.registerLanguage("bash", bash);
hljs.registerLanguage("sh", bash);
hljs.registerLanguage("javascript", javascript);
hljs.registerLanguage("js", javascript);
hljs.registerLanguage("typescript", typescript);
hljs.registerLanguage("ts", typescript);
hljs.registerLanguage("json", json);
hljs.registerLanguage("python", python);
hljs.registerLanguage("py", python);
hljs.registerLanguage("xml", xml);
hljs.registerLanguage("html", xml);
hljs.registerLanguage("css", css);

marked.setOptions({
  gfm: true,
  breaks: true,
});

/** Highlight source in a known language, falling back to auto-detect, then to
 *  escaped plain text. Returns HTML for the <code> inner. */
export function highlight(code: string, lang?: string): string {
  try {
    if (lang && hljs.getLanguage(lang)) return hljs.highlight(code, { language: lang }).value;
    return hljs.highlightAuto(code).value;
  } catch {
    return escapeHtml(code);
  }
}

/** Render markdown to HTML with code blocks highlighted.
 *
 * Untrusted-content hardening (defense in depth — the input is model-authored):
 *   - `renderer.html` drops ALL raw HTML, so no `<script>`, `<img onerror>`, or
 *     event-handler attributes can reach the DOM (marked escapes everything
 *     else structurally through its tokenizer).
 *   - `safeUrl()` neutralizes `javascript:`/`data:`/`vbscript:` hrefs on links
 *     and images — the one residual XSS vector once raw HTML is gone.
 * With raw HTML removed and URL schemes constrained, the output is app-shaped
 * markup (paragraphs, lists, links, highlighted code), not model markup — so
 * the bubble's dangerouslySetInnerHTML renders trusted HTML, not the input. */
export function renderMarkdown(md: string): string {
  const renderer = new marked.Renderer();
  renderer.code = ({ text, lang }) => {
    const langLabel = (lang ?? "").toUpperCase();
    const body = highlight(text, lang);
    return `<pre class="mm-code"><div class="mm-code-head">${escapeHtml(langLabel)}</div><code class="hljs">${body}</code></pre>`;
  };
  renderer.html = () => ""; // drop raw HTML — bubbles never render model markup
  renderer.link = ({ href, title, text }) => {
    const url = safeUrl(href);
    if (!url) return escapeHtml(text);
    const t = title ? ` title="${escapeHtml(title)}"` : "";
    // Model-authored links open in a new tab with no window handle back.
    return `<a href="${escapeHtml(url)}"${t} target="_blank" rel="noopener noreferrer">${text}</a>`;
  };
  renderer.image = ({ href, title, text }) => {
    const url = safeUrl(href);
    if (!url) return escapeHtml(text);
    const t = title ? ` title="${escapeHtml(title)}"` : "";
    return `<img src="${escapeHtml(url)}" alt="${escapeHtml(text)}"${t} />`;
  };
  return marked.parse(md, { renderer, async: false });
}

/** Allow only http(s) and mailto URLs; reject javascript:/data:/vbscript: and
 *  anything unparseable. Relative and anchor links pass through. */
function safeUrl(href: string | null | undefined): string | null {
  if (!href) return null;
  const trimmed = href.trim();
  if (/^(?:#|\/|\.\/|\.\.\/)/.test(trimmed)) return trimmed; // relative / anchor
  if (/^(?:https?:|mailto:)/i.test(trimmed)) return trimmed;
  return null;
}

/** A fenced code block found in a message, offered to the artifact panel. */
export interface FencedBlock {
  lang: string;
  code: string;
}

/** Extract fenced code blocks from markdown (spec view 3: any fenced block gets
 *  an "open in artifacts" affordance). Uses marked's lexer, not a regex, so
 *  nested/indented fences resolve the same way they render. */
export function extractFencedBlocks(md: string): FencedBlock[] {
  const blocks: FencedBlock[] = [];
  for (const token of marked.lexer(md)) {
    if (token.type === "code") blocks.push({ lang: token.lang ?? "", code: token.text });
  }
  return blocks;
}

/** One renderable slice of an assistant message: markdown text, or a parsed
 *  Marmalade UI v1 tree (spec: daemon repo docs/dynamic-ui/marmalade-ui-v1.md). */
export type MessageSegment =
  | { kind: "markdown"; md: string }
  | { kind: "ui"; node: UiNode };

/** Split assistant text into markdown runs and ```marmalade-ui trees, in
 *  order. Uses marked's lexer (same fence resolution as rendering). An
 *  unparseable marmalade-ui fence stays in the markdown run and renders as a
 *  plain code block — degrade, never error (spec §Transport). */
export function splitUiSegments(md: string): MessageSegment[] {
  const segments: MessageSegment[] = [];
  let buf = "";
  for (const token of marked.lexer(md)) {
    if (token.type === "code" && (token.lang ?? "").trim() === UI_FENCE_LANG) {
      const node = parseUiTree(token.text);
      if (node) {
        if (buf) {
          segments.push({ kind: "markdown", md: buf });
          buf = "";
        }
        segments.push({ kind: "ui", node });
        continue;
      }
    }
    buf += token.raw;
  }
  if (buf) segments.push({ kind: "markdown", md: buf });
  return segments;
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
