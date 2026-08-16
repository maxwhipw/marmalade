// archive.test.ts — the Archived-section helper + the session.archive wire.
//
// Archived (session.archive, additive 2026-07-24) is daemon-backed shared
// list metadata. The daemon keeps listing archived rows; hiding them is
// purely the client's presentation job — partitionArchived splits the rail's
// rows, archiveSession pins the wire shape.

import { describe, expect, test } from "vitest";
import { partitionArchived } from "../src/components/archive.js";
import type { SessionSummary } from "../src/gateway/types.js";
import { GatewayClient } from "../src/gateway/client.js";
import { FakeGateway, type FakeGatewayScript } from "./fake-gateway.js";

const sess = (id: string, archived?: boolean): SessionSummary => ({
  session_id: id,
  lifecycle: "active",
  run_state: "idle",
  last_seq: 0,
  seen_seq: 0,
  ...(archived === undefined ? {} : { archived }),
});

describe("partitionArchived", () => {
  test("splits on the flag, preserving order within each half", () => {
    const rows = [sess("a", false), sess("b", true), sess("c"), sess("d", true)];
    const { active, archived } = partitionArchived(rows);
    expect(active.map((s) => s.session_id)).toEqual(["a", "c"]);
    expect(archived.map((s) => s.session_id)).toEqual(["b", "d"]);
  });

  test("absent flag (old daemon) reads as active — nothing vanishes", () => {
    const rows = [sess("a"), sess("b")];
    const { active, archived } = partitionArchived(rows);
    expect(active).toHaveLength(2);
    expect(archived).toHaveLength(0);
  });

  test("empty input yields two empty halves", () => {
    const { active, archived } = partitionArchived([]);
    expect(active).toEqual([]);
    expect(archived).toEqual([]);
  });
});

async function connected(script: FakeGatewayScript = {}) {
  const fake = new FakeGateway(script);
  const client = new GatewayClient({
    url: "ws://127.0.0.1:9130/api/ws",
    deviceId: "dev-test",
    deviceName: "webui-test",
    socketFactory: () => fake.socket,
    now: () => 1000,
    backoffBaseMs: 1,
    backoffMaxMs: 1,
  });
  client.connect();
  fake.fireOpen();
  await new Promise((r) => setTimeout(r, 0));
  return { fake, client };
}

describe("session.archive wire", () => {
  test("archiveSession sends {session_id, archived} and returns the stored flag", async () => {
    const { fake, client } = await connected({
      handlers: { "session.archive": () => ({ archived: true }) },
    });
    const r = await client.archiveSession("s1", true);
    expect(r).toBe(true);
    expect(fake.requests.find((q) => q.method === "session.archive")!.params).toEqual({
      session_id: "s1",
      archived: true,
    });
  });

  test("a daemon rejection (e.g. the main session) rejects the call", async () => {
    const { client } = await connected({
      handlers: {
        "session.archive": () => {
          throw new Error("the main session is daemon-managed and cannot be archived");
        },
      },
    });
    await expect(client.archiveSession("s_main", true)).rejects.toThrow(/cannot be archived/);
  });
});
