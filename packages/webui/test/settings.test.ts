// settings.test.ts — the zod SettingsSchema is the single source of shape +
// defaults + migration (spec "Settings"). These assert the two properties the
// spec leans on: an empty/absent blob yields full defaults, and a corrupt field
// degrades to its default via .catch() rather than throwing.

import { describe, expect, test } from "vitest";
import { parseSettings, defaultSettings } from "../src/settings/schema.js";

describe("settings schema", () => {
  test("an empty blob parses to complete defaults", () => {
    const s = parseSettings({});
    expect(s.connection.url).toBe("ws://127.0.0.1:9130/api/ws");
    expect(s.appearance.theme).toBe("system");
    expect(s.appearance.fontScale).toBe(1);
    expect(s.chat.sendMode).toBe("enter");
    expect(s.chat.renderMarkdown).toBe(true);
    expect(s.advanced.showEventLog).toBe(false);
  });

  test("undefined parses to defaults (no throw)", () => {
    expect(() => parseSettings(undefined)).not.toThrow();
    expect(defaultSettings().connection.url).toContain("9130");
  });

  test("corrupt fields degrade to defaults via .catch(), keeping valid siblings", () => {
    const s = parseSettings({
      appearance: { theme: "chartreuse", fontScale: 999 },
      chat: { sendMode: "double-click", toolCardsExpanded: true, renderMarkdown: false },
      connection: { url: "ws://host:1/api/ws" },
    });
    // Invalid enum / out-of-range → default.
    expect(s.appearance.theme).toBe("system");
    expect(s.appearance.fontScale).toBe(1);
    expect(s.chat.sendMode).toBe("enter");
    // Valid siblings survive.
    expect(s.chat.toolCardsExpanded).toBe(true);
    expect(s.chat.renderMarkdown).toBe(false);
    expect(s.connection.url).toBe("ws://host:1/api/ws");
  });

  test("a later-added field is filled from default when absent (no migration code)", () => {
    // Simulate an old persisted blob that predates `advanced`.
    const s = parseSettings({ connection: { url: "ws://x/api/ws", token: "", deviceName: "old" } });
    expect(s.advanced).toEqual({ showEventLog: false });
  });
});
