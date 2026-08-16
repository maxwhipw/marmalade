// schema.ts — the single source of settings shape, defaults, and migration.
//
// Spec "Settings": one zod SettingsSchema is the whole contract. Defaults live
// on the fields; migration is `.catch()` per field (a malformed or
// missing-in-an-old-blob value falls back to its default instead of throwing).
// Adding a setting later = add a field with a default — no migration code, no
// ceremony (spec).

import { z } from "zod";

export const ThemeMode = z.enum(["system", "light", "dark"]).catch("system");
export type ThemeMode = z.infer<typeof ThemeMode>;

export const SendMode = z.enum(["enter", "ctrl-enter"]).catch("enter");
export type SendMode = z.infer<typeof SendMode>;

export const SettingsSchema = z.object({
  connection: z
    .object({
      /** Daemon WS URL — default = the daemon's gatewayPort (config.ts). */
      url: z.string().catch("ws://127.0.0.1:9130/api/ws"),
      /** Bearer token, kept for when gateway auth lands. Masked in the UI. */
      token: z.string().catch(""),
      /** Human device name sent at hello. */
      deviceName: z.string().catch("marmalade webui"),
    })
    .catch({ url: "ws://127.0.0.1:9130/api/ws", token: "", deviceName: "marmalade webui" }),
  appearance: z
    .object({
      theme: ThemeMode,
      /** Body font scale multiplier (0.85–1.4). */
      fontScale: z.number().min(0.85).max(1.4).catch(1),
    })
    .catch({ theme: "system", fontScale: 1 }),
  chat: z
    .object({
      sendMode: SendMode,
      /** Render tool cards expanded by default (else collapsed). */
      toolCardsExpanded: z.boolean().catch(false),
      /** Render assistant text as markdown (else plain text). */
      renderMarkdown: z.boolean().catch(true),
    })
    .catch({ sendMode: "enter", toolCardsExpanded: false, renderMarkdown: true }),
  advanced: z
    .object({
      /** Show the raw event-log debug pane. */
      showEventLog: z.boolean().catch(false),
    })
    .catch({ showEventLog: false }),
});

export type Settings = z.infer<typeof SettingsSchema>;

/** Parse a possibly-partial/old blob into a complete Settings, filling every
 *  gap from defaults. `.catch()` on each field means this never throws — a
 *  corrupt localStorage value degrades to defaults rather than blanking the app. */
export function parseSettings(raw: unknown): Settings {
  return SettingsSchema.parse(raw ?? {});
}

export const defaultSettings = (): Settings => parseSettings({});
