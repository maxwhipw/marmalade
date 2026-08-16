// jump.ts — the open-at-seq deep link and the match navigator (design-lab
// labs/session-search, lab 3 frame 1).
//
// A search hit names a message (session_id + message_id + seq); the transcript
// arrives asynchronously by event replay, so "open at this message" is not a
// scroll, it's a WAIT-then-scroll. Everything timing-sensitive about that is
// here as pure functions so it can be tested without a DOM that can't scroll:
//
//   - resolveJumpAnchor: is the target on screen yet, and which bubble is it?
//   - collectSessionMatches / collectMatchRefs: this session's matches in
//     transcript order, which is what ↑/↓ walk (NOT the daemon's rank order).
//   - stepMatchIndex: clamped ends — the navigator never wraps, so "↓ at the
//     last match" can't teleport you back to the top without you asking.
//   - shouldAutoscroll: a pending jump (or an open navigator) suppresses the
//     stick-to-bottom autoscroll, which would otherwise fight the jump during
//     the very replay that delivers the target.

/** A message the transcript can be opened at. `messageId` is the preferred
 *  anchor (stable, server-minted); `seq` is the fallback for a message the
 *  transcript renders under a different id (or not at all). */
export interface JumpTarget {
  messageId?: string;
  seq: number;
}

/** Where a chat should open, threaded from a search hit through the shell. */
export interface ChatOpenTarget extends JumpTarget {
  sessionId: string;
  messageId: string;
  /** Kept alive after the jump so the navigator can say what you searched. */
  query: string;
}

/** The deep link a clicked search hit carries into the chat. The hit already
 *  names everything needed (session_id + message_id + seq — the wire shape the
 *  archive/peek work locked in); the query comes from the searcher, since the
 *  hit doesn't carry what found it. */
export function openTargetFromHit(
  hit: { session_id: string; message_id: string; seq: number },
  query: string,
): ChatOpenTarget {
  return { sessionId: hit.session_id, messageId: hit.message_id, seq: hit.seq, query };
}

/** One navigable match: the navigator walks these in transcript order. */
export interface MatchRef {
  messageId: string;
  seq: number;
}

/** Just enough of a rendered message to anchor against. */
interface AnchorableMessage {
  id: string;
  seq: number;
}

/**
 * The id of the bubble to scroll to, or null when the target hasn't replayed
 * yet (cold open: the transcript streams in frame by frame, so a jump request
 * usually lands BEFORE its message exists).
 *
 * Exact message_id wins. Otherwise the nearest message at or past the target
 * seq: replay is seq-ordered, so such a message existing means the target seq
 * has already gone by — it either rendered under another id or isn't a bubble
 * at all (a notice, a compacted-away turn), and stopping at the right place in
 * the transcript beats waiting forever for a message that will never arrive.
 */
export function resolveJumpAnchor(
  messages: readonly AnchorableMessage[],
  target: JumpTarget,
): string | null {
  if (target.messageId) {
    const exact = messages.find((m) => m.id === target.messageId);
    if (exact) return exact.id;
  }
  let best: AnchorableMessage | null = null;
  for (const m of messages) {
    if (m.seq >= target.seq && (!best || m.seq < best.seq)) best = m;
  }
  return best?.id ?? null;
}

/** Transcript order (seq ascending), de-duped by message. The daemon answers in
 *  RANK order; ↑/↓ must walk the conversation, not the ranking. */
export function collectMatchRefs(
  hits: readonly { message_id: string; seq: number }[],
): MatchRef[] {
  const byId = new Map<string, MatchRef>();
  for (const h of hits) {
    if (!byId.has(h.message_id)) byId.set(h.message_id, { messageId: h.message_id, seq: h.seq });
  }
  return [...byId.values()].sort((a, b) => a.seq - b.seq);
}

/** Which match the navigator starts on: the one you clicked. Falls back to the
 *  first match at or past its seq, then to 0 — never -1, because the navigator
 *  always opens somewhere. */
export function matchIndexOf(matches: readonly MatchRef[], target: JumpTarget): number {
  if (target.messageId) {
    const exact = matches.findIndex((m) => m.messageId === target.messageId);
    if (exact >= 0) return exact;
  }
  const near = matches.findIndex((m) => m.seq >= target.seq);
  return near >= 0 ? near : 0;
}

/** Clamped step: the ends are dead stops (the arrows disable there) rather than
 *  a wrap, so walking off the last match can't silently restart the list. */
export function stepMatchIndex(index: number, count: number, dir: 1 | -1): number {
  if (count <= 0) return 0;
  return Math.min(count - 1, Math.max(0, index + dir));
}

/** Stick-to-bottom is right for a live conversation and wrong for a jump: it
 *  fires on every replayed frame and would drag you off the match you asked
 *  for. Suppressed while a jump is pending AND while the navigator is open
 *  (new streaming content must not yank you away from a match you're reading);
 *  dismissing the navigator restores it. */
export function shouldAutoscroll(o: { pendingJump: boolean; navigatorOpen: boolean }): boolean {
  return !o.pendingJump && !o.navigatorOpen;
}

/** The lab's honesty touch: "jumped N messages back", so a teleport doesn't
 *  read as a scroll bug. Distance is message positions from the bottom; a short
 *  hop says nothing (null) because you can see where you went. */
export function jumpPillText(
  messages: readonly AnchorableMessage[],
  anchorId: string,
  minDistance = 5,
): string | null {
  const idx = messages.findIndex((m) => m.id === anchorId);
  if (idx < 0) return null;
  const back = messages.length - 1 - idx;
  return back >= minDistance ? `jumped ${back} messages back` : null;
}

// ── Collecting this session's matches ───────────────────────────────────────

/** One search.messages page. */
export const MATCH_PAGE = 50;
/** How many matches the navigator will actually hold. Past this the count stays
 *  honest (the daemon's `total`) but only the collected ones are navigable, and
 *  the bar says so — a 900-match query must not become 18 round-trips. */
export const MATCH_CAP = 250;

export interface MatchCollection {
  matches: MatchRef[];
  /** The daemon's true match count for the session, capped or not. */
  total: number;
  /** True when `total` exceeds what was collected. */
  capped: boolean;
}

type SearchPage = (
  offset: number,
  limit: number,
) => Promise<{ hits: { message_id: string; seq: number }[]; total: number }>;

/** Page through this session's matches up to the cap. Takes the page fetcher as
 *  an argument so the paging/cap behavior is testable without a gateway. */
export async function collectSessionMatches(
  search: SearchPage,
  cap = MATCH_CAP,
  page = MATCH_PAGE,
): Promise<MatchCollection> {
  const raw: { message_id: string; seq: number }[] = [];
  let total = 0;
  for (let offset = 0; offset < cap; offset += page) {
    const r = await search(offset, Math.min(page, cap - offset));
    total = r.total;
    raw.push(...r.hits);
    if (r.hits.length === 0 || raw.length >= total) break;
  }
  return { matches: collectMatchRefs(raw), total, capped: raw.length < total };
}
