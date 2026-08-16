// provider.tsx — the useSettings() provider (spec "Settings").
//
// One provider, persisted to localStorage on every change, applied live (no
// "save" button). Reads once at mount through the zod schema (so a corrupt or
// old blob degrades to defaults), writes through on every update. The theme +
// font-scale side effects (write <html data-theme>, --font-scale) live here so
// appearance changes apply instantly across the whole app.

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { defaultSettings, parseSettings, type Settings } from "./schema.js";

const STORAGE_KEY = "marmalade.webui.settings";

interface SettingsContextValue {
  settings: Settings;
  /** Deep-merge a partial update and persist. Applied live. */
  update: (patch: DeepPartial<Settings>) => void;
  /** Replace wholesale from an imported JSON blob (validated through zod). */
  replace: (raw: unknown) => void;
  /** The current settings as a pretty JSON string, for export. */
  exportJson: () => string;
}

type DeepPartial<T> = { [K in keyof T]?: T[K] extends object ? DeepPartial<T[K]> : T[K] };

const SettingsContext = createContext<SettingsContextValue | null>(null);

function load(): Settings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? parseSettings(JSON.parse(raw)) : defaultSettings();
  } catch {
    return defaultSettings();
  }
}

/** Resolve system|light|dark to the concrete marmalade theme attribute. */
function resolveTheme(mode: Settings["appearance"]["theme"]): "marmalade" | "marmalade-dark" {
  if (mode === "light") return "marmalade";
  if (mode === "dark") return "marmalade-dark";
  const prefersDark = window.matchMedia?.("(prefers-color-scheme: dark)").matches;
  return prefersDark ? "marmalade-dark" : "marmalade";
}

export function SettingsProvider({ children }: { children: ReactNode }): ReactNode {
  const [settings, setSettings] = useState<Settings>(load);

  // Persist on every change (applied live — no save button).
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    } catch {
      /* private-mode / quota — settings still live in memory this session */
    }
  }, [settings]);

  // Appearance side effects: theme attribute + font-scale variable, applied to
  // <html> so they cascade everywhere instantly.
  useEffect(() => {
    const el = document.documentElement;
    el.setAttribute("data-theme", resolveTheme(settings.appearance.theme));
    el.style.setProperty("--font-scale", String(settings.appearance.fontScale));
  }, [settings.appearance.theme, settings.appearance.fontScale]);

  // Re-resolve on OS theme change while in "system" mode.
  const themeMode = settings.appearance.theme;
  useEffect(() => {
    if (themeMode !== "system" || !window.matchMedia) return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => document.documentElement.setAttribute("data-theme", resolveTheme("system"));
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [themeMode]);

  const update = useCallback((patch: DeepPartial<Settings>) => {
    setSettings((prev) => deepMerge(prev, patch));
  }, []);

  const replace = useCallback((raw: unknown) => {
    setSettings(parseSettings(raw));
  }, []);

  const settingsRef = useRef(settings);
  settingsRef.current = settings;
  const exportJson = useCallback(() => JSON.stringify(settingsRef.current, null, 2), []);

  const value = useMemo<SettingsContextValue>(
    () => ({ settings, update, replace, exportJson }),
    [settings, update, replace, exportJson],
  );

  return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>;
}

export function useSettings(): SettingsContextValue {
  const ctx = useContext(SettingsContext);
  if (!ctx) throw new Error("useSettings must be used within a SettingsProvider");
  return ctx;
}

/** Shallow-by-group deep merge: settings is two levels deep, so merge each
 *  group's fields over the previous group. Keeps updates atomic per group. */
function deepMerge(base: Settings, patch: DeepPartial<Settings>): Settings {
  const out = { ...base } as Settings;
  for (const key of Object.keys(patch) as (keyof Settings)[]) {
    const group = patch[key];
    if (group && typeof group === "object") {
      out[key] = { ...(base[key] as object), ...(group as object) } as never;
    }
  }
  return out;
}
