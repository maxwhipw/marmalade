// attachments.ts — per-session file staging for the composer's attach UI (T1).
//
// Ported from hermes-agent (MIT) tui_gateway/server.py: image.attach_bytes
// (magic-byte sniff + 25 MB cap, @6520), file.attach (@6843), image.detach
// (@6890), and the PDF -> pdftoppm page-render trick (@6581) — vision models
// can't eat PDFs, so a PDF is rendered to page PNGs and staged as images.
//
// The daemon does NOT run a vision pipeline (hermes owns its agent loop; we
// delegate to Claude Code, which reads image/file content natively). So this
// module only STAGES bytes to a per-session dir and hands the router a queue
// it drains into the NEXT prompt.submit's harness-facing preamble — exactly
// how the router already prepends the origin preamble. See router.ts
// submitPrompt.
//
// SECURITY (this input is authenticated but UNTRUSTED — a paired device can
// send arbitrary bytes + names):
//   * Every write is confined to a per-session dir; the untrusted filename is
//     never trusted as a path (images get a daemon-generated name; files run
//     through safeAttachmentName, which rejects separators/traversal), and a
//     realpath containment assertion backs it up (cf. fs-browse.ts).
//   * The image TYPE is decided by magic bytes, never the declared
//     name/extension — a .png that isn't a PNG is rejected, not trusted.
//   * 25 MB decoded cap, checked before AND after decode.
//   * PDF rendering shells out to pdftoppm with a fixed argv (no shell),
//     bounded pages + timeout + stderr buffer, in a temp dir cleaned in a
//     finally.
//   * Display names reaching the model's context are stripped of newlines /
//     control chars so a hostile filename can't break out of the preamble.

import { spawnSync } from "node:child_process";
import {
  mkdirSync, writeFileSync, unlinkSync, rmSync, existsSync, realpathSync,
  mkdtempSync, readdirSync, readFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, sep } from "node:path";

/** 25 MB decoded — matches Anthropic's per-image limit and the client's stated
 *  server cap (MarmaladeRpc.kt imageAttachBytes docblock). */
export const MAX_DECODED_BYTES = 25 * 1024 * 1024;
const MAX_MB = MAX_DECODED_BYTES / (1024 * 1024);
/** Reject the base64 STRING before decoding once it can't fit the cap (base64
 *  inflates ~4/3), so a hostile payload can't force a giant allocation. */
const MAX_B64_INPUT = Math.ceil(MAX_DECODED_BYTES / 3) * 4 + 1024;

/** PDF render bounds (ported from hermes pdf.attach). */
const PDF_MAX_PAGES = 25;
const PDF_DPI = 150;
const PDF_TIMEOUT_MS = 120_000;

/** Leading magic bytes -> extension. The type is decided HERE, from the bytes,
 *  never from the declared filename (sec: a lying extension must not win). */
const IMAGE_MAGIC: ReadonlyArray<readonly [Buffer, string]> = [
  [Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), ".png"],
  [Buffer.from([0xff, 0xd8, 0xff]), ".jpg"],
  [Buffer.from("GIF87a", "latin1"), ".gif"],
  [Buffer.from("GIF89a", "latin1"), ".gif"],
  [Buffer.from("BM", "latin1"), ".bmp"],
];

const DATA_URL_RE = /^data:[a-zA-Z0-9.+/*-]*;base64,(.*)$/s;
const BASE64_BODY_RE = /^[A-Za-z0-9+/]+={0,2}$/;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS_RE = /[\x00-\x1f\x7f]/;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS_GLOBAL_RE = /[\x00-\x1f\x7f]+/g;

/** A staged attachment awaiting consumption by the next prompt.submit. */
export interface StagedAttachment {
  kind: "image" | "file";
  /** Absolute path of the staged file on the daemon host. */
  path: string;
  /** Injection-safe display name shown to the agent in the preamble. */
  name: string;
}

/** Decode a base64 (optionally `data:<mime>;base64,`-wrapped) payload with
 *  embedded whitespace. Returns null when the input isn't valid base64 (port
 *  of hermes _decode_attach_base64 with Python's `validate=True`), or an empty
 *  Buffer for an empty payload. */
export function decodeBase64Payload(raw: string): Buffer | null {
  let cleaned = String(raw ?? "").trim();
  const m = DATA_URL_RE.exec(cleaned);
  if (m) cleaned = m[1];
  cleaned = cleaned.replace(/\s+/g, "");
  if (cleaned.length === 0) return Buffer.alloc(0);
  // Strict base64: alphabet only, length %4==0, padding only at the end.
  if (cleaned.length % 4 !== 0 || !BASE64_BODY_RE.test(cleaned)) return null;
  return Buffer.from(cleaned, "base64");
}

/** Resolve an image extension from the decoded bytes' magic number, or null
 *  when the bytes aren't a recognized image. The declared filename is
 *  deliberately IGNORED — bytes are authoritative. */
export function sniffImageExt(bytes: Buffer): string | null {
  const head = bytes.subarray(0, 16);
  if (head.subarray(0, 4).toString("latin1") === "RIFF" && head.subarray(8, 12).toString("latin1") === "WEBP") {
    return ".webp";
  }
  for (const [sig, ext] of IMAGE_MAGIC) {
    if (head.subarray(0, sig.length).equals(sig)) return ext;
  }
  return null;
}

/** A filename usable as a direct child of the session dir. Rejects anything
 *  that isn't already its own basename — path separators, `..`, absolute
 *  paths, backslashes, and control chars all throw rather than being silently
 *  truncated. */
export function safeAttachmentName(requested: string): string {
  const raw = String(requested ?? "").trim();
  const base = basename(raw);
  if (
    !base || base === "." || base === ".." ||
    base !== raw ||             // had a separator / trailing slash / dir part
    base.includes("\\") ||      // backslash (Windows-style traversal) never valid
    CONTROL_CHARS_RE.test(base)
  ) {
    throw new Error(`unsafe attachment name: ${JSON.stringify(requested)}`);
  }
  return base;
}

/** Sanitize an arbitrary name for use in the harness-facing preamble: strip
 *  control chars / newlines (preamble-injection guard) and cap the length. */
function safeDisplayName(requested: string): string {
  return String(requested ?? "")
    .replace(CONTROL_CHARS_GLOBAL_RE, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 120);
}

/** True when pdftoppm (poppler-utils) is on PATH. */
export function hasPdftoppm(): boolean {
  const res = spawnSync("pdftoppm", ["-v"], { stdio: "ignore", timeout: 5_000, windowsHide: true });
  return !res.error;
}

/** Render a PDF's pages to PNG bytes via pdftoppm. Fixed argv (no shell),
 *  bounded to [1, maxPages] pages + a timeout + a stderr buffer cap, in a temp
 *  dir removed in a finally. Throws when pdftoppm is missing or fails. */
export function renderPdfToImages(
  pdfBytes: Buffer,
  opts: { maxPages?: number; dpi?: number; timeoutMs?: number } = {},
): Buffer[] {
  if (!hasPdftoppm()) {
    throw new Error("cannot render PDF: pdftoppm not installed (apt install poppler-utils)");
  }
  const maxPages = opts.maxPages ?? PDF_MAX_PAGES;
  const dir = mkdtempSync(join(tmpdir(), "marmalade-pdf-"));
  try {
    const pdfPath = join(dir, "input.pdf");
    writeFileSync(pdfPath, pdfBytes);
    const outPrefix = join(dir, "page");
    // -png: PNG output. -r: DPI. -f/-l: first/last page — the hard page bound.
    const argv = [
      "-png", "-r", String(opts.dpi ?? PDF_DPI),
      "-f", "1", "-l", String(maxPages),
      pdfPath, outPrefix,
    ];
    const res = spawnSync("pdftoppm", argv, {
      timeout: opts.timeoutMs ?? PDF_TIMEOUT_MS,
      stdio: ["ignore", "ignore", "pipe"],
      maxBuffer: 4 * 1024 * 1024,
      windowsHide: true,
    });
    if (res.error) {
      const why = (res.error as NodeJS.ErrnoException).code === "ETIMEDOUT" ? "timed out" : res.error.message;
      throw new Error(`pdftoppm failed: ${why}`);
    }
    if (res.status !== 0) {
      const tail = (res.stderr?.toString("utf8") || "").trim().split("\n").slice(-3).join(" | ");
      throw new Error(`pdftoppm failed (exit ${res.status})${tail ? `: ${tail}` : ""}`);
    }
    const pages = readdirSync(dir).filter((f) => f.startsWith("page-") && f.endsWith(".png")).sort();
    if (pages.length === 0) throw new Error("pdftoppm produced no pages (corrupt or empty PDF?)");
    return pages.map((f) => readFileSync(join(dir, f)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** The harness-only preamble that tells the agent about staged attachments,
 *  drained into the next prompt.submit (never stored in the transcript — the
 *  client renders its own attachment chips). */
export function renderAttachmentPreamble(staged: StagedAttachment[]): string {
  const one = staged.length === 1;
  const lines = staged.map((a) => `- ${a.path}${a.name ? `  (${a.name})` : ""}`);
  return (
    `[The user attached ${staged.length} file${one ? "" : "s"} to this message. ` +
    `Read ${one ? "it" : "them"} with your file tools if relevant:\n` +
    lines.join("\n") +
    `]`
  );
}

/** Per-session attachment staging + queue. One instance per daemon; keyed by
 *  the daemon session id. Files live under `<baseDir>/<sessionId>/` and are
 *  cleaned up on session.delete via clear(). */
export class AttachmentStore {
  private readonly baseDir: string;
  private readonly queues = new Map<string, StagedAttachment[]>();
  private readonly counters = new Map<string, number>();

  constructor(baseDir: string) {
    this.baseDir = baseDir;
  }

  /** Stage image bytes. The declared filename is used only as a display hint —
   *  the on-disk name is daemon-generated and the type comes from magic bytes. */
  attachImageBytes(sessionId: string, contentBase64: string, filename?: string): {
    attached: true; path: string; count: number;
  } {
    this.assertSessionId(sessionId);
    const bytes = this.decodeCapped(contentBase64);
    const ext = sniffImageExt(bytes);
    if (!ext) throw new Error("attachment is not a recognized image (PNG/JPEG/GIF/WebP/BMP)");
    const real = this.sessionDir(sessionId);
    const n = this.nextCounter(sessionId);
    const stored = join(real, `img-${n}${ext}`);
    this.assertContained(real, stored);
    writeFileSync(stored, bytes, { mode: 0o600 });
    const display = safeDisplayName(filename ?? "") || `img-${n}${ext}`;
    const q = this.enqueue(sessionId, { kind: "image", path: stored, name: display });
    return { attached: true, path: stored, count: q.length };
  }

  /** Stage a non-image file. A PDF (by magic bytes) is page-rendered to images
   *  instead; everything else is stored verbatim and gets an `@file:` ref the
   *  client prepends to the prompt. */
  attachFile(sessionId: string, name: string, dataUrl: string): {
    attached: true; name: string; path: string; ref_path: string | null; ref_text: string | null; uploaded: true;
  } {
    this.assertSessionId(sessionId);
    const bytes = this.decodeCapped(dataUrl);
    if (bytes.subarray(0, 5).toString("latin1") === "%PDF-") {
      return this.attachPdfPages(sessionId, name, bytes);
    }
    const safe = safeAttachmentName(name);
    const real = this.sessionDir(sessionId);
    let storedName = safe;
    let stored = join(real, storedName);
    if (existsSync(stored)) {
      storedName = `${this.nextCounter(sessionId)}-${safe}`;
      stored = join(real, storedName);
    }
    this.assertContained(real, stored);
    writeFileSync(stored, bytes, { mode: 0o600 });
    this.enqueue(sessionId, { kind: "file", path: stored, name: storedName });
    return {
      attached: true,
      name: storedName,
      path: stored,
      ref_path: storedName,
      ref_text: `@file:${storedName}`,
      uploaded: true,
    };
  }

  private attachPdfPages(sessionId: string, name: string, pdfBytes: Buffer): {
    attached: true; name: string; path: string; ref_path: null; ref_text: null; uploaded: true;
  } {
    const pages = renderPdfToImages(pdfBytes);
    const real = this.sessionDir(sessionId);
    const display = safeDisplayName(name) || "document.pdf";
    let firstPath = "";
    pages.forEach((png, i) => {
      const n = this.nextCounter(sessionId);
      const stored = join(real, `pdf-${n}-p${i + 1}.png`);
      this.assertContained(real, stored);
      writeFileSync(stored, png, { mode: 0o600 });
      this.enqueue(sessionId, { kind: "image", path: stored, name: `${display} (p${i + 1})` });
      if (!firstPath) firstPath = stored;
    });
    // PDF pages ride the queue preamble as images; no @file: text ref.
    return { attached: true, name: display, path: firstPath, ref_path: null, ref_text: null, uploaded: true };
  }

  /** Drop a staged attachment by the path returned from attach, before it's
   *  consumed. Only unlinks a file this session actually staged — the `path`
   *  param can never delete anything outside the queue. */
  detach(sessionId: string, path: string): { detached: boolean; count: number } {
    const q = this.queues.get(sessionId) ?? [];
    const kept = q.filter((a) => a.path !== path);
    for (const a of q) if (a.path === path) { try { unlinkSync(a.path); } catch { /* already gone */ } }
    this.queues.set(sessionId, kept);
    return { detached: kept.length !== q.length, count: kept.length };
  }

  /** Drain + clear the session's queue (called at prompt.submit). The files
   *  stay on disk so the agent can read them during the turn; clear() removes
   *  them at session.delete. */
  consume(sessionId: string): StagedAttachment[] {
    const q = this.queues.get(sessionId) ?? [];
    this.queues.delete(sessionId);
    return q;
  }

  /** Currently-staged attachments (inspection/tests). */
  pending(sessionId: string): readonly StagedAttachment[] {
    return this.queues.get(sessionId) ?? [];
  }

  /** Remove the queue + every staged file for a deleted session. */
  clear(sessionId: string): void {
    this.queues.delete(sessionId);
    this.counters.delete(sessionId);
    try { rmSync(join(this.baseDir, sessionId), { recursive: true, force: true }); } catch { /* nothing staged */ }
  }

  // ── internals ─────────────────────────────────────────────────────────────

  private decodeCapped(raw: string): Buffer {
    if (String(raw ?? "").length > MAX_B64_INPUT) throw new Error(`attachment too large (cap is ${MAX_MB} MB decoded)`);
    const bytes = decodeBase64Payload(raw);
    if (bytes === null) throw new Error("payload is not valid base64");
    if (bytes.length === 0) throw new Error("attachment is empty");
    if (bytes.length > MAX_DECODED_BYTES) {
      throw new Error(`attachment too large (${bytes.length} bytes; cap is ${MAX_MB} MB)`);
    }
    return bytes;
  }

  /** Daemon-minted session ids only ([A-Za-z0-9._-]); a hostile id must not
   *  reach a path segment. */
  private assertSessionId(id: string): void {
    if (!/^[A-Za-z0-9._-]+$/.test(id)) throw new Error("invalid session id");
  }

  /** mkdir the per-session dir and return its REAL (symlink-resolved) path,
   *  which every write is then confined to. */
  private sessionDir(sessionId: string): string {
    const d = join(this.baseDir, sessionId);
    mkdirSync(d, { recursive: true, mode: 0o700 });
    return realpathSync(d);
  }

  /** Defense-in-depth: the resolved write target must be a direct child of the
   *  resolved session dir. safeAttachmentName already blocks separators, so
   *  this only fires on a symlinked baseDir or a logic bug. */
  private assertContained(realDir: string, target: string): void {
    if (dirname(target) !== realDir || !target.startsWith(realDir + sep)) {
      throw new Error("attachment path escapes session dir");
    }
  }

  private nextCounter(sessionId: string): number {
    const n = (this.counters.get(sessionId) ?? 0) + 1;
    this.counters.set(sessionId, n);
    return n;
  }

  private enqueue(sessionId: string, a: StagedAttachment): StagedAttachment[] {
    const q = this.queues.get(sessionId) ?? [];
    q.push(a);
    this.queues.set(sessionId, q);
    return q;
  }
}
