import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import {
  Transcriber, transcribeCommandAvailable, DEFAULT_TRANSCRIBE_COMMAND, MAX_AUDIO_BYTES,
} from "../dist/transcription.js";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig } from "../dist/config.js";

const AUDIO_B64 = Buffer.from("fake-pcm-bytes").toString("base64");

// ── transcribeCommandAvailable ──────────────────────────────────────────────

test("availability: /bin/sh resolves, a nonsense binary doesn't", () => {
  assert.equal(transcribeCommandAvailable(["sh", "{file}"]), true);
  assert.equal(transcribeCommandAvailable(["/bin/sh", "{file}"]), true);
  assert.equal(transcribeCommandAvailable([`no-such-bin-${randomUUID()}`]), false);
  assert.equal(transcribeCommandAvailable([]), false);
});

test("the default command is the faster-whisper (whisper-ctranslate2) CLI shape", () => {
  assert.equal(DEFAULT_TRANSCRIBE_COMMAND[0], "whisper-ctranslate2");
  assert.ok(DEFAULT_TRANSCRIBE_COMMAND.includes("distil-small.en"));
  assert.ok(DEFAULT_TRANSCRIBE_COMMAND.includes("{file}"));
  assert.ok(DEFAULT_TRANSCRIBE_COMMAND.includes("{dir}"));
});

// ── Transcriber.transcribe ──────────────────────────────────────────────────

test("stdout path: command output becomes the transcript, provider = binary basename", async () => {
  const t = new Transcriber(["/bin/sh", "-c", "echo '  hello from stdout  '"]);
  const r = await t.transcribe(AUDIO_B64, "audio/wav");
  assert.equal(r.transcript, "hello from stdout");
  assert.equal(r.provider, "sh");
});

test("txt-file path beats stdout (whisper --output_format txt convention)", async () => {
  // $0 = the substituted {dir}; whisper writes <dir>/audio.txt for audio.wav.
  const t = new Transcriber([
    "/bin/sh", "-c", "echo noise-on-stdout; printf 'from the txt file' > \"$0/audio.txt\"", "{dir}",
  ]);
  const r = await t.transcribe(AUDIO_B64);
  assert.equal(r.transcript, "from the txt file");
});

test("the staged audio file carries the decoded bytes and the mime-mapped extension", async () => {
  const t = new Transcriber(["/bin/sh", "-c", "printf '%s' \"$0\"", "{file}"]);
  const r = await t.transcribe(AUDIO_B64, "audio/ogg");
  assert.ok(r.transcript.endsWith("/audio.ogg"), r.transcript);
  // Unknown mime falls back to .wav — the declared string never reaches a path.
  const r2 = await t.transcribe(AUDIO_B64, "audio/../../evil");
  assert.ok(r2.transcript.endsWith("/audio.wav"), r2.transcript);
});

test("a {dir} command that exits 0 without writing the txt errors instead of returning stdout noise", async () => {
  const t = new Transcriber(["/bin/sh", "-c", "echo 'Detected language: en'; true", "{dir}"]);
  await assert.rejects(() => t.transcribe(AUDIO_B64), /produced no output file/);
});

test("temp dir is removed after the run", async () => {
  const t = new Transcriber(["/bin/sh", "-c", "printf '%s' \"$0\" > \"$0/audio.txt\"", "{dir}"]);
  const r = await t.transcribe(AUDIO_B64);
  const { existsSync } = await import("node:fs");
  assert.ok(r.transcript.startsWith(join(tmpdir(), "marmalade-stt-")));
  assert.equal(existsSync(r.transcript), false);
});

test("bad input: invalid base64, empty payload, oversize all throw client-safe errors", async () => {
  const t = new Transcriber(["/bin/sh", "-c", "echo unused"]);
  await assert.rejects(() => t.transcribe("!!! not base64 !!!"), /not valid base64/);
  await assert.rejects(() => t.transcribe(""), /empty/);
  const oversize = "A".repeat(Math.ceil(MAX_AUDIO_BYTES / 3) * 4 + 2048);
  await assert.rejects(() => t.transcribe(oversize), /too large/);
});

test("a failing command surfaces its stderr tail, not a stack trace", async () => {
  const t = new Transcriber(["/bin/sh", "-c", "echo 'model exploded' >&2; exit 3"]);
  await assert.rejects(() => t.transcribe(AUDIO_B64), /transcription failed: model exploded/);
});

test("a hung command times out", async () => {
  const t = new Transcriber(["/bin/sh", "-c", "sleep 30"], { timeoutMs: 300 });
  await assert.rejects(() => t.transcribe(AUDIO_B64), /timed out/);
});

// ── Router wiring ───────────────────────────────────────────────────────────

function harness(transcriber?: { transcribe(a: string, m?: string): Promise<{ transcript: string; provider: string }> }) {
  const dir = join(tmpdir(), `tr-${randomUUID()}`);
  const router = createRouter({
    cfg: defaultConfig(),
    sessions: SessionManager.inMemory(),
    transcripts: new TranscriptCache(dir),
    usage: new UsageMeter(),
    adapter: { name: "fake", spawn() { throw new Error("unused"); } } as any,
    today: () => "2026-07-18",
    now: () => 1000,
    mintSessionId: () => "s_x",
    ...(transcriber ? { transcriber } : {}),
  });
  const conn = { ws: { send: () => {} }, principal: "owner", legacy: false, capabilities: [] } as any;
  return { router, conn };
}

test("router: audio.transcribe without a transcriber → method-not-found", async () => {
  const h = harness();
  await assert.rejects(
    () => h.router("audio.transcribe", { audio_base64: AUDIO_B64 }, h.conn),
    /transcription not configured/,
  );
});

test("router: audio.transcribe delegates and returns the transcript", async () => {
  const h = harness({
    transcribe: async (a, m) => ({ transcript: `heard:${Buffer.from(a, "base64").toString()}:${m}`, provider: "fake" }),
  });
  const r = (await h.router("audio.transcribe", { audio_base64: AUDIO_B64, mime: "audio/wav" }, h.conn)) as any;
  assert.equal(r.transcript, "heard:fake-pcm-bytes:audio/wav");
  assert.equal(r.provider, "fake");
});

test("router: a transcriber failure maps to invalid-params with the message", async () => {
  const h = harness({ transcribe: async () => { throw new Error("whisper fell over"); } });
  await assert.rejects(
    () => h.router("audio.transcribe", { audio_base64: AUDIO_B64 }, h.conn),
    /whisper fell over/,
  );
});
