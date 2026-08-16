// archive.ts — pure helper behind the rail's Archived section.
//
// Archived (session.archive) is daemon-backed shared list metadata: the
// daemon keeps listing archived rows and never behaves differently for them
// — filtering is purely the client's presentation job. The rail hides
// archived rows from the main list/groups and shows them in one collapsed
// trailing "Archived" section. Pure and testable, like fork/origin/unread.

import type { SessionSummary } from "../gateway/types.js";

/** Split rows on the archived flag, preserving order. Absent (old daemon)
 *  reads as false — every row stays in the active list. */
export function partitionArchived(sessions: SessionSummary[]): {
  active: SessionSummary[];
  archived: SessionSummary[];
} {
  const active: SessionSummary[] = [];
  const archived: SessionSummary[] = [];
  for (const s of sessions) (s.archived === true ? archived : active).push(s);
  return { active, archived };
}
