// transcription.ts — server-side STT fallback (audio.transcribe).
//
// The Android voice popup transcribes on-device (sherpa-onnx Whisper); this
// module is its degraded mode for when that fails (model load error, native
// inference crash). One finished utterance arrives as base64 audio; the daemon
// shells out to a local STT command and returns the transcript. Nothing here
// streams — a fallback round trip per utterance is the accepted cost.
//
// The command is configurable (config `transcribe_command`, argv array with
// `{file}` / `{dir}` placeholders) and defaults to faster-whisper via the
// whisper-ctranslate2 CLI (CTranslate2 runtime) — a faster, lower-memory engine
// than openai-whisper for the same Whisper family. Transcript retrieval covers
// both CLI conventions: a `{dir}/<audio-basename>.txt` output file wins (the
// --output_format txt convention, shared by openai-whisper and
// whisper-ctranslate2), else trimmed stdout.
//
// SECURITY (authenticated but untrusted input, same posture as attachments.ts):
//   * base64 is size-capped BEFORE decode (hostile payloads can't force a
//     giant allocation) and written to a mkdtemp dir removed in a finally.
//   * The command runs via execFile with a fixed argv — no shell, and the
//     audio only ever appears as file CONTENT, never in the argv.
//   * The declared mime only selects a temp-file extension from an allowlist;
//     it can't inject a path or reach the argv as free text.
//   * Bounded: exec timeout + stdout/stderr maxBuffer.

import { execFile } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, delimiter, join } from "node:path";
import { existsSync } from "node:fs";
import { decodeBase64Payload } from "./attachments.js";

/** 25 MB decoded — same cap as attachments; a voice utterance is far smaller. */
export const MAX_AUDIO_BYTES = 25 * 1024 * 1024;
const MAX_B64_INPUT = Math.ceil(MAX_AUDIO_BYTES / 3) * 4 + 1024;

/** Each CLI invocation loads the model fresh (no persistent process); from the
 *  local HF cache that's a few seconds on CPU. The FIRST invocation on an
 *  uncached host also downloads the distil-small.en weights (~150MB) —
 *  pre-warmed on marmalade at setup, so this bound covers steady state, not a
 *  cold pull on a slow link. Generous, but bounded. */
const EXEC_TIMEOUT_MS = 180_000;
const MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

/** Declared mime → temp-file extension. Allowlist — an unknown mime falls back
 *  to .wav rather than trusting the string near a path. */
const MIME_EXT: Record<string, string> = {
  "audio/wav": ".wav",
  "audio/x-wav": ".wav",
  "audio/webm": ".webm",
  "audio/ogg": ".ogg",
  "audio/mp4": ".m4a",
  "audio/m4a": ".m4a",
  "audio/mpeg": ".mp3",
};

/** Default STT argv. `{file}` = the staged audio, `{dir}` = its temp dir (also
 *  where the .txt lands). faster-whisper via whisper-ctranslate2 running
 *  distil-small.en — the "morally clear" distil variant (open training data),
 *  English-only, right for dictation. `--device auto` uses CUDA when the
 *  ctranslate2 wheel finds it, else CPU. This is the sad path (on-device STT
 *  failed), so a clean, present fallback matters more than latency; hosts
 *  without whisper-ctranslate2 override via config `transcribe_command` (e.g.
 *  back to the plain openai `whisper` CLI). */
export const DEFAULT_TRANSCRIBE_COMMAND = [
  "whisper-ctranslate2", "{file}",
  "--model", "distil-small.en",
  "--output_format", "txt",
  "--output_dir", "{dir}",
  "--device", "auto",
];

/** True when [command]'s binary resolves on PATH (or is an existing absolute/
 *  relative path). Decides whether the "transcription" feature is advertised. */
export function transcribeCommandAvailable(command: string[]): boolean {
  const bin = command[0];
  if (!bin) return false;
  if (bin.includes("/")) return existsSync(bin);
  return (process.env.PATH ?? "")
    .split(delimiter)
    .some((p) => p && existsSync(join(p, bin)));
}

export interface TranscribeResult {
  transcript: string;
  provider: string;
}

export class Transcriber {
  private readonly command: string[];
  private readonly provider: string;
  private readonly timeoutMs: number;

  constructor(command: string[] = DEFAULT_TRANSCRIBE_COMMAND, opts: { timeoutMs?: number } = {}) {
    if (command.length === 0) throw new Error("transcribe command must not be empty");
    this.command = command;
    this.provider = basename(command[0]);
    this.timeoutMs = opts.timeoutMs ?? EXEC_TIMEOUT_MS;
  }

  available(): boolean {
    return transcribeCommandAvailable(this.command);
  }

  /** Decode, stage to a temp file, run the STT command, return the transcript.
   *  Throws with a client-safe message on bad input or command failure. */
  async transcribe(audioBase64: string, mime?: string): Promise<TranscribeResult> {
    if (String(audioBase64 ?? "").length > MAX_B64_INPUT) {
      throw new Error(`audio too large (cap is ${MAX_AUDIO_BYTES / (1024 * 1024)} MB decoded)`);
    }
    const bytes = decodeBase64Payload(audioBase64);
    if (bytes === null) throw new Error("audio payload is not valid base64");
    if (bytes.length === 0) throw new Error("audio payload is empty");
    if (bytes.length > MAX_AUDIO_BYTES) {
      throw new Error(`audio too large (${bytes.length} bytes; cap is ${MAX_AUDIO_BYTES / (1024 * 1024)} MB)`);
    }

    const ext = MIME_EXT[String(mime ?? "").toLowerCase()] ?? ".wav";
    const dir = mkdtempSync(join(tmpdir(), "marmalade-stt-"));
    try {
      const audioPath = join(dir, `audio${ext}`);
      writeFileSync(audioPath, bytes, { mode: 0o600 });
      const argv = this.command.map((a) =>
        a.replaceAll("{file}", audioPath).replaceAll("{dir}", dir),
      );
      const stdout = await this.run(argv[0], argv.slice(1));
      // whisper --output_format txt writes <dir>/audio.txt; a file beats
      // stdout (whisper's stdout carries timestamped progress lines). A
      // command that takes {dir} has opted into the txt convention — if it
      // exits 0 without writing the file, that's a failure, not a transcript:
      // falling back to stdout would return progress noise as dictated text.
      const txtPath = join(dir, `audio.txt`);
      if (existsSync(txtPath)) {
        return { transcript: readFileSync(txtPath, "utf8").trim(), provider: this.provider };
      }
      if (this.command.some((a) => a.includes("{dir}"))) {
        throw new Error("transcription produced no output file");
      }
      return { transcript: stdout.trim(), provider: this.provider };
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }

  private run(bin: string, args: string[]): Promise<string> {
    return new Promise((resolve, reject) => {
      execFile(
        bin, args,
        { timeout: this.timeoutMs, maxBuffer: MAX_OUTPUT_BYTES, windowsHide: true },
        (err, stdout, stderr) => {
          if (err) {
            const killed = (err as NodeJS.ErrnoException & { killed?: boolean }).killed;
            const tail = String(stderr ?? "").trim().split("\n").slice(-3).join(" | ");
            reject(new Error(
              killed
                ? `transcription timed out after ${this.timeoutMs / 1000}s`
                : `transcription failed: ${tail || err.message}`,
            ));
            return;
          }
          resolve(String(stdout ?? ""));
        },
      );
    });
  }
}
