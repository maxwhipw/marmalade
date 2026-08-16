// device-store.ts — the device roster (identity plan P3).
//
// One row per device identity the daemon has ever seen at hello. Fed by the
// gateway's hello handshake (the same authenticated binding origins are
// stamped from — sec-H3); read by the `list_devices` MCP tool so the agent
// can target "my phone" vs "my desktop". Registration is an upsert: the
// device id is the key, first_seen is preserved, everything else reflects
// the latest hello (a device that upgrades its client re-declares itself).
//
// v0.1: hello DECLARES identity; verified device identity (token→deviceId)
// lands with pairing (M2). paired_at then becomes meaningful — the column is
// first_seen until then.

import type { DatabaseSync } from "node:sqlite";

export interface DeviceRecord {
  deviceId: string;
  platform: string;
  capabilities: string[];
  firstSeen: number;
  lastSeen: number;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS devices (
  device_id    TEXT PRIMARY KEY,
  platform     TEXT NOT NULL,
  capabilities TEXT NOT NULL DEFAULT '[]',
  first_seen   INTEGER NOT NULL,
  last_seen    INTEGER NOT NULL
);
`;

export class DeviceStore {
  constructor(private db: DatabaseSync) {
    this.db.exec(SCHEMA);
  }

  /** Register/refresh a device at hello. Upsert keyed by device id:
   *  first_seen survives, platform/capabilities/last_seen track the latest
   *  declaration. */
  touch(deviceId: string, platform: string, capabilities: string[], now: number): void {
    this.db
      .prepare(
        `INSERT INTO devices (device_id, platform, capabilities, first_seen, last_seen)
         VALUES (?,?,?,?,?)
         ON CONFLICT(device_id) DO UPDATE SET
           platform = excluded.platform,
           capabilities = excluded.capabilities,
           last_seen = excluded.last_seen`,
      )
      .run(deviceId, platform, JSON.stringify(capabilities), now, now);
  }

  get(deviceId: string): DeviceRecord | undefined {
    const row = this.db
      .prepare(`SELECT * FROM devices WHERE device_id = ?`)
      .get(deviceId) as Record<string, unknown> | undefined;
    return row ? rowToRecord(row) : undefined;
  }

  /** Remove a device from the roster (M2 revocation). Returns true if a row
   *  was deleted. Token deletion is the PairingStore's half. */
  delete(deviceId: string): boolean {
    const res = this.db.prepare(`DELETE FROM devices WHERE device_id = ?`).run(deviceId);
    return Number(res.changes) > 0;
  }

  /** The full roster, most recently seen first. */
  list(): DeviceRecord[] {
    const rows = this.db
      .prepare(`SELECT * FROM devices ORDER BY last_seen DESC`)
      .all() as Record<string, unknown>[];
    return rows.map(rowToRecord);
  }
}

function rowToRecord(row: Record<string, unknown>): DeviceRecord {
  // A corrupt capabilities cell must not brick the whole roster — degrade to
  // "no declared capabilities" for that row (same posture as replay: one bad
  // record never poisons the rest).
  let capabilities: string[] = [];
  try {
    const parsed = JSON.parse(row.capabilities as string);
    if (Array.isArray(parsed)) capabilities = parsed.filter((c): c is string => typeof c === "string");
  } catch { /* degrade */ }
  return {
    deviceId: row.device_id as string,
    platform: row.platform as string,
    capabilities,
    firstSeen: row.first_seen as number,
    lastSeen: row.last_seen as number,
  };
}
