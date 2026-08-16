// seen-store.ts — per-(device, session) read cursors (identity plan P4).
//
// The gateway fork's seen-at patch (4k) done right: instead of comparing
// wall-clock last_active vs seen_at with a fudge window, the cursor is the
// seq the device has rendered up to. Unread is arithmetic: last_seq >
// seen_seq. Monotonic by construction — a stale/late stamp can never move a
// cursor backward (max-merge), the same rule the Android client applies when
// merging stamps.

import type { DatabaseSync } from "node:sqlite";

const SCHEMA = `
CREATE TABLE IF NOT EXISTS seen (
  device_id  TEXT NOT NULL,
  session_id TEXT NOT NULL,
  seq        INTEGER NOT NULL,
  ts         INTEGER NOT NULL,
  PRIMARY KEY (device_id, session_id)
);
`;

export class SeenStore {
  constructor(private db: DatabaseSync) {
    this.db.exec(SCHEMA);
  }

  /** Monotonic stamp: the cursor only ever moves forward. Returns the stored
   *  cursor (>= seq — a stale stamp is absorbed, not an error). */
  stamp(deviceId: string, sessionId: string, seq: number, ts: number): number {
    this.db
      .prepare(
        `INSERT INTO seen (device_id, session_id, seq, ts) VALUES (?,?,?,?)
         ON CONFLICT(device_id, session_id) DO UPDATE SET
           seq = MAX(seen.seq, excluded.seq),
           ts  = CASE WHEN excluded.seq > seen.seq THEN excluded.ts ELSE seen.ts END`,
      )
      .run(deviceId, sessionId, seq, ts);
    return this.get(deviceId, sessionId);
  }

  get(deviceId: string, sessionId: string): number {
    const row = this.db
      .prepare(`SELECT seq FROM seen WHERE device_id = ? AND session_id = ?`)
      .get(deviceId, sessionId) as { seq: number } | undefined;
    return row?.seq ?? 0;
  }

  /** session.delete cascade: drop EVERY device's cursor for the session. */
  deleteSession(sessionId: string): void {
    this.db.prepare(`DELETE FROM seen WHERE session_id = ?`).run(sessionId);
  }

  /** All of a device's cursors in one query — session.list decoration. */
  forDevice(deviceId: string): Map<string, number> {
    const rows = this.db
      .prepare(`SELECT session_id, seq FROM seen WHERE device_id = ?`)
      .all(deviceId) as { session_id: string; seq: number }[];
    return new Map(rows.map((r) => [r.session_id, r.seq]));
  }
}
