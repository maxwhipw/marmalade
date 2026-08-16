// daemon-settings.test.ts — the Models card's client half (settings.get/update)
// plus its pure label helpers.
//
// These settings are SERVER-owned (config.json on the daemon), unlike every
// other card on the Settings screen. The behaviors that matter:
//   - a daemon without the "settings" feature yields null, so the card renders
//     read-only instead of firing an RPC that 404s
//   - update patches only the keys given, and refreshes model.list so the
//     composer's "Default (…)" label follows immediately
//   - model.list's new `efforts` vocabulary rides the models event; a daemon
//     that predates it degrades to [] (card hides the effort control)

import { describe, expect, test } from "vitest";
import { GatewayClient } from "../src/gateway/client.js";
import { FakeGateway, type FakeGatewayScript } from "./fake-gateway.js";
import { effortLabel, modelSubtitle } from "../src/views/ModelsCard.js";
import type { ModelInfo } from "../src/gateway/types.js";

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

const SETTINGS = { default_model: "claude-opus-5", default_effort: "high", locked: [] };

describe("settings.get / settings.update", () => {
  test("getSettings returns the daemon's defaults when the feature is advertised", async () => {
    const { client } = await connected({
      features: ["stable-ids", "settings"],
      handlers: { "settings.get": () => SETTINGS },
    });
    expect(await client.getSettings()).toEqual(SETTINGS);
  });

  test("getSettings is null (no RPC) on a daemon without the feature", async () => {
    const { fake, client } = await connected({ features: ["stable-ids"] });
    expect(await client.getSettings()).toBeNull();
    expect(fake.requests.find((r) => r.method === "settings.get")).toBeUndefined();
  });

  test("updateSettings patches only the given key and refreshes model.list", async () => {
    const { fake, client } = await connected({
      features: ["stable-ids", "settings"],
      handlers: {
        "settings.update": (p) => ({ ...SETTINGS, ...p, locked: [] }),
        "model.list": () => ({
          models: [{ id: "claude-opus-5", label: "Opus 5" }],
          default_model: "claude-opus-5",
          efforts: ["low", "medium", "high", "xhigh", "max"],
        }),
      },
    });
    const after = await client.updateSettings({ default_effort: "max" });
    expect(after.default_effort).toBe("max");
    expect(after.default_model).toBe("claude-opus-5");
    expect(fake.requests.find((r) => r.method === "settings.update")!.params).toEqual({
      default_effort: "max",
    });
    // The composer's Default (…) label reads model.list, so a defaults change
    // must refetch it — otherwise the picker keeps naming the OLD default.
    expect(fake.requests.filter((r) => r.method === "model.list")).toHaveLength(1);
  });

  test("clearing an effort sends an explicit null (patch semantics)", async () => {
    const { fake, client } = await connected({
      features: ["stable-ids", "settings"],
      handlers: {
        "settings.update": () => ({ ...SETTINGS, default_effort: null }),
        "model.list": () => ({ models: [] }),
      },
    });
    const after = await client.updateSettings({ default_effort: null });
    expect(after.default_effort).toBeNull();
    expect(fake.requests.find((r) => r.method === "settings.update")!.params).toEqual({
      default_effort: null,
    });
  });
});

describe("model.list efforts vocabulary", () => {
  test("the models event carries the daemon's effort levels", async () => {
    const { client } = await connected({
      handlers: {
        "model.list": () => ({
          models: [{ id: "claude-opus-5", label: "Opus 5" }],
          default_model: "claude-opus-5",
          efforts: ["low", "high", "max"],
        }),
      },
    });
    const seen: string[][] = [];
    client.on("models", (_m, _d, efforts) => seen.push(efforts));
    await client.listModels();
    expect(seen).toEqual([["low", "high", "max"]]);
  });

  test("a daemon predating `efforts` degrades to an empty vocabulary", async () => {
    const { client } = await connected({
      handlers: { "model.list": () => ({ models: [{ id: "m", label: "M" }] }) },
    });
    const seen: string[][] = [];
    client.on("models", (_m, _d, efforts) => seen.push(efforts));
    await client.listModels();
    expect(seen).toEqual([[]]);
  });
});

describe("card labels", () => {
  const models: ModelInfo[] = [
    { id: "claude-opus-5", label: "Opus 5", description: "The standard" },
    { id: "claude-haiku-4-5", label: "Haiku 4.5" },
  ];

  test("effortLabel prettifies known levels and passes new ones through", () => {
    expect(effortLabel("xhigh")).toBe("Very high");
    expect(effortLabel("ludicrous")).toBe("ludicrous");
  });

  test("modelSubtitle prefers the description, falls back to the id", () => {
    expect(modelSubtitle(models, "claude-opus-5")).toBe("The standard");
    expect(modelSubtitle(models, "claude-haiku-4-5")).toBe("claude-haiku-4-5");
  });

  test("an unknown or absent default is stated plainly, never blank", () => {
    expect(modelSubtitle(models, "claude-mystery-9")).toBe("claude-mystery-9");
    expect(modelSubtitle(models, null)).toBe("The harness picks (unknown until a turn runs)");
  });
});
