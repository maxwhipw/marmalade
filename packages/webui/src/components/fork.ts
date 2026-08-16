// fork.ts — pure helpers for the session-fork UI (T2 #3). Side-effect free so
// the digital-twin test pins the no-fork detection + warning wording without a
// socket, mirroring cron-format.ts / usage-format.ts.

import { FORK_UNSUPPORTED_REASON, type SessionForkResult } from "@marmalade/protocol";

/** A no-fork harness (OpenCode) rejects session.fork with
 *  `error.data.reason === FORK_UNSUPPORTED_REASON` — the structured contract
 *  clients branch on (2026-07-18 review: substring-matching the human message
 *  was a shadow contract). The substring check remains as a fallback for a
 *  daemon predating the structured reason; the other fork rejections (turn in
 *  flight, no harness state, bad cut message) carry neither. */
export function isNoForkError(err: unknown): boolean {
  const e = err as { message?: string; data?: { reason?: string } } | null;
  if (e?.data?.reason === FORK_UNSUPPORTED_REASON) return true;
  return typeof e?.message === "string" && /cannot fork/i.test(e.message);
}

/** The heavier context-loss warning for the no-fork path. The Android client
 *  falls back to its seed-create branch here (text kept, tool/reasoning lost);
 *  the webui has no seed-create branch, so it surfaces this and stops. The
 *  marmalade daemon's Claude harness always forks, so this is OpenCode-only. */
export const NO_FORK_WARNING =
  "This harness can't branch with full context — tool calls and reasoning can't carry over, so branching is unavailable here.";

/** Non-blocking success toast for a completed fork: the daemon's soft warning
 *  if any (e.g. Claude forks don't copy file-history/undo), else a plain
 *  confirmation. Show it, don't block on it (spec). */
export function forkSuccessToast(result: SessionForkResult): string {
  const where = result.forked_from.message_id ? "from this message" : "from the end of the chat";
  return result.warning
    ? `Branched ${where} into a new chat. ${result.warning}`
    : `Branched ${where} into a new chat.`;
}
