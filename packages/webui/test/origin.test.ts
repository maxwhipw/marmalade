// origin.test.ts — the daemon-minted-turn marker (source "cron"/"agent").

import { describe, expect, test } from "vitest";
import { originMarker } from "../src/components/origin.js";
import type { WireOrigin } from "../src/gateway/types.js";

const base = { user_id: "owner", platform: "daemon" };

describe("originMarker", () => {
  test('an "agent" turn shows the sending session id (device_id "session:<id>")', () => {
    const o: WireOrigin = { ...base, device_id: "session:s_abc123", source: "agent" };
    expect(originMarker(o)).toEqual({
      label: "from session s_abc123",
      title: "Sent by another session (s_abc123)",
    });
  });

  test('an "agent" turn with an unprefixed device_id falls back to it verbatim', () => {
    const o: WireOrigin = { ...base, device_id: "weird", source: "agent" };
    expect(originMarker(o)?.label).toBe("from session weird");
  });

  test('a "cron" turn shows the scheduled marker', () => {
    const o: WireOrigin = { ...base, device_id: "cron", source: "cron" };
    expect(originMarker(o)).toEqual({
      label: "scheduled",
      title: "Sent by a scheduled prompt (cron)",
    });
  });

  test("human turns (text/voice) and no origin get no marker", () => {
    expect(originMarker({ ...base, device_id: "web-1", source: "text" })).toBeNull();
    expect(originMarker({ ...base, device_id: "web-1", source: "voice" })).toBeNull();
    expect(originMarker(undefined)).toBeNull();
  });

  test("an unknown future source is treated as human (no marker), never a crash", () => {
    expect(originMarker({ ...base, device_id: "x", source: "telepathy" })).toBeNull();
  });
});
