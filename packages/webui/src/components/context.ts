// context.ts — pure helpers behind the composer's context chip.
//
// Two sources feed the same number and they must agree:
//
//   1. COLD SEED — the opened session's session.list row carries the daemon's
//      persisted occupancy (context_used/context_max/context_percent, additive
//      2026-07-25). That is what makes a session opened without running a turn
//      show context at all.
//   2. LIVE — every message.complete rides a `usage` block with the same
//      snake_case keys (daemon normalize.ts::wireUsage).
//
// Precedence: the live value wins once this session has seen one; before that
// (and after switching to a session with no live value yet) the row seed
// shows. Unknown on both sides → null → the chip renders NOTHING. We never
// fabricate a percentage: a harness that reports no window (ACP/OpenCode) and
// a daemon predating the fields both read as unknown, and that is honest.
//
// Pure and side-effect free like fork.ts / origin.ts / archive.ts, so the
// digital-twin test drives seeding, precedence and formatting directly.

import { fmtTokens } from "./usage-format.js";
import type { SessionSummary } from "../gateway/types.js";

/** A displayable context reading. All three are present together — percent is
 *  underivable without both halves, and the chip has nothing to show without
 *  percent, so a partial reading is simply "unknown". */
export interface ContextOccupancy {
  used: number;
  max: number;
  percent: number;
}

function make(used: unknown, max: unknown): ContextOccupancy | null {
  if (typeof used !== "number" || !Number.isFinite(used) || used <= 0) return null;
  if (typeof max !== "number" || !Number.isFinite(max) || max <= 0) return null;
  // Recompute rather than trust a supplied percent: one formula, one home
  // (the daemon derives the same way in router.ts::contextPercent).
  return { used, max, percent: Math.min(100, Math.round((used / max) * 100)) };
}

/** Read the occupancy off a message.complete `usage` block (the live source).
 *  Anything missing or non-numeric → null. */
export function contextFromUsage(usage: unknown): ContextOccupancy | null {
  if (!usage || typeof usage !== "object") return null;
  const u = usage as Record<string, unknown>;
  return make(u.context_used, u.context_max);
}

/** Read the occupancy off a session.list row (the cold seed). Absent fields
 *  (old daemon) and nulls (never ran / cleared) both read as unknown. */
export function contextFromRow(row: SessionSummary | undefined): ContextOccupancy | null {
  if (!row) return null;
  return make(row.context_used, row.context_max);
}

/** What the chip should show: the live reading for THIS session if one has
 *  arrived, else the row's persisted seed. Switching sessions passes that
 *  session's live value (null until it runs a turn) and its own row, so the
 *  reseed falls out of the precedence rule rather than needing its own step. */
export function resolveContext(
  live: ContextOccupancy | null | undefined,
  row: SessionSummary | undefined,
): ContextOccupancy | null {
  return live ?? contextFromRow(row);
}

/** Chip face: `42% context`. */
export function contextChipLabel(c: ContextOccupancy): string {
  return `${c.percent}% context`;
}

/** Chip tooltip: the token counts behind the percentage. */
export function contextChipTitle(c: ContextOccupancy): string {
  return `${fmtTokens(c.used)} / ${fmtTokens(c.max)} tokens in the context window`;
}
