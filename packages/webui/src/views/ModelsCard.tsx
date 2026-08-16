// ModelsCard.tsx — the "Models" settings card: the daemon-owned defaults every
// NEW session starts with (model + reasoning effort).
//
// These are NOT browser settings. Unlike everything else on the Settings
// screen (which lives in localStorage via useSettings), these write through
// settings.update to the daemon's config.json — so the Android client, the
// CLI, and any other browser see the same answer, and the value survives a
// marmaladed restart. Same precedent as seen_at and workspaces: cross-client
// state is server-owned.
//
// Degradation: a daemon without the "settings" feature (or with a key pinned
// by an env var) renders the controls disabled with the reason spelled out,
// rather than offering a write that would silently not stick.

import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import { useGateway } from "../app/gateway-context.js";
import type { DaemonSettings, ModelInfo } from "../gateway/types.js";
import {
  boundsPatch,
  boundsSummary,
  boundsSupported,
  boundsToNotches,
  effortLabel,
  type EffortBounds,
} from "../components/efforts.js";

// effortLabel lives with the other effort helpers (components/efforts.ts) now
// that the transcript's clamp line needs it too; re-exported here because the
// card is where callers expect to find it.
export { effortLabel };

/** Sub-line for the currently selected model row. */
export function modelSubtitle(models: ModelInfo[], id: string | null): string {
  if (!id) return "The harness picks (unknown until a turn runs)";
  const found = models.find((m) => m.id === id);
  if (!found) return id; // daemon default the catalog doesn't carry — show the raw id
  return found.description ?? found.id;
}

export function ModelsCard(): ReactNode {
  const { client, features, models, efforts } = useGateway();
  const supported = features.includes("settings");
  const [settings, setSettings] = useState<DaemonSettings | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    void client
      .getSettings()
      .then(setSettings)
      .catch((e: Error) => setError(e.message));
  }, [client]);

  useEffect(load, [load]);

  const write = (patch: {
    default_model?: string | null;
    default_effort?: string | null;
    model_efforts?: Record<string, EffortBounds | null>;
  }) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    client
      .updateSettings(patch)
      .then(setSettings)
      // The daemon rejects a bad write (unknown model, env-pinned key) — show
      // its message and re-read, so the controls never show a value the
      // daemon didn't accept.
      .catch((e: Error) => { setError(e.message); load(); })
      .finally(() => setBusy(false));
  };

  const locked = (key: string) => settings?.locked.includes(key) ?? false;

  return (
    <section className="mm-card">
      <h2>Models</h2>
      <p className="mm-rail-sub">
        What every NEW session starts on. Stored on the daemon, so all your
        devices agree; an open session keeps the model it was created with.
      </p>

      {!supported && (
        <p className="mm-rail-sub" style={{ color: "var(--error)" }}>
          This daemon predates settings.get/update — edit config.json and restart marmaladed.
        </p>
      )}
      {error && (
        <p className="mm-rail-sub" style={{ color: "var(--error)" }}>{error}</p>
      )}

      <div className="mm-field">
        <label htmlFor="default-model">Default model</label>
        <select
          id="default-model"
          value={settings?.default_model ?? ""}
          disabled={!supported || busy || locked("default_model") || models.length === 0}
          onChange={(e) => write({ default_model: e.target.value })}
        >
          {/* A daemon-advertised default the catalog doesn't list still needs a
              row to be the selected one, else the select silently shows entry 0. */}
          {settings?.default_model && !models.some((m) => m.id === settings.default_model) && (
            <option value={settings.default_model}>{settings.default_model}</option>
          )}
          {models.map((m) => (
            <option key={m.id} value={m.id}>{m.label}</option>
          ))}
        </select>
        <span className="mm-rail-sub">
          {locked("default_model")
            ? "Pinned by MARMALADE_DEFAULT_MODEL — unset the env var to edit here."
            : modelSubtitle(models, settings?.default_model ?? null)}
        </span>
      </div>

      {efforts.length > 0 && (
        <div className="mm-field">
          <label>Default thinking</label>
          <div className="mm-chips">
            {efforts.map((level) => (
              <button
                key={level}
                className="mm-chip"
                aria-pressed={settings?.default_effort === level}
                disabled={!supported || busy || locked("default_effort")}
                onClick={() => write({ default_effort: level })}
              >
                {effortLabel(level)}
              </button>
            ))}
            {/* Clearing is a real choice, not an absence: it hands the decision
                back to the harness rather than pinning a level. */}
            <button
              className="mm-chip"
              aria-pressed={settings?.default_effort == null}
              disabled={!supported || busy || locked("default_effort")}
              onClick={() => write({ default_effort: null })}
            >
              Harness default
            </button>
          </div>
          <span className="mm-rail-sub">
            {locked("default_effort")
              ? "Pinned by MARMALADE_DEFAULT_EFFORT — unset the env var to edit here."
              : "Higher effort thinks longer and costs more."}
          </span>
        </div>
      )}

      {/* Per-model bounds (design-lab option B). Hidden entirely on a daemon
          that predates model_efforts — an editor whose writes would bounce is
          worse than no editor. Needs at least two levels to have a range. */}
      {boundsSupported(settings) && efforts.length > 1 && models.length > 0 && (
        <div className="mm-field">
          <label>Thinking bounds</label>
          <span className="mm-rail-sub">
            Optional floor and ceiling per model. A session asking for a level
            outside the range is moved to the nearest allowed one — the daemon
            clamps rather than refusing, and says so in the transcript.
          </span>
          {models.map((m) => (
            <ModelBoundsRow
              key={m.id}
              model={m}
              efforts={efforts}
              bounds={settings?.model_efforts?.[m.id] ?? null}
              disabled={!supported || busy || locked("model_efforts")}
              onCommit={(patch) => write({ model_efforts: patch })}
            />
          ))}
          {locked("model_efforts") && (
            <span className="mm-rail-sub">Pinned by config — edit config.json to change.</span>
          )}
        </div>
      )}
    </section>
  );
}

/** One model's bounds row: a collapsed summary until opened, then a two-notch
 *  range over the daemon's published effort vocabulary. Collapsed and unbounded
 *  reads exactly like the card did before bounds existed.
 *
 *  The notches are LOCAL while dragging and commit on release, so a drag across
 *  the range is one settings.update, not one per step. The daemon's returned
 *  snapshot is the truth — `bounds` re-seeds the notches whenever it changes. */
function ModelBoundsRow({
  model,
  efforts,
  bounds,
  disabled,
  onCommit,
}: {
  model: ModelInfo;
  efforts: string[];
  bounds: EffortBounds | null;
  disabled: boolean;
  onCommit: (patch: Record<string, EffortBounds | null>) => void;
}): ReactNode {
  const [open, setOpen] = useState(false);
  const [notches, setNotches] = useState(() => boundsToNotches(bounds, efforts));
  const serialized = `${bounds?.min ?? ""}|${bounds?.max ?? ""}`;
  useEffect(() => {
    // Re-seed on the daemon's ANSWER, not on every render: the notches are
    // derived state, so a rejected write snaps them back to the truth. Keyed on
    // the serialized bound rather than the object identity, which changes on
    // every settings.get.
    setNotches(boundsToNotches(bounds, efforts));
  }, [serialized, efforts.join(",")]);

  const last = efforts.length - 1;
  // A notch never crosses its partner — that's the daemon's own min <= max
  // invariant, enforced here so an invalid patch is unreachable rather than
  // rejected after the fact.
  const moveMin = (v: number) => setNotches((n) => ({ min: v, max: Math.max(v, n.max) }));
  const moveMax = (v: number) => setNotches((n) => ({ min: Math.min(v, n.min), max: v }));
  const commit = () => onCommit(boundsPatch(model.id, notches, efforts));

  const live: EffortBounds | null = open
    ? (notches.min <= 0 && notches.max >= last
        ? null
        : {
            ...(notches.min > 0 ? { min: efforts[notches.min] } : {}),
            ...(notches.max < last ? { max: efforts[notches.max] } : {}),
          })
    : bounds;

  return (
    <div className="mm-bounds-row">
      <button
        className="mm-bounds-head"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span>{model.label}</span>
        <span className="mm-rail-sub">{boundsSummary(live)}</span>
        <span aria-hidden="true">{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <div className="mm-bounds-body">
          <label htmlFor={`bmin-${model.id}`}>At least {effortLabel(efforts[notches.min])}</label>
          <input
            id={`bmin-${model.id}`}
            type="range"
            min={0}
            max={last}
            step={1}
            value={notches.min}
            disabled={disabled}
            onChange={(e) => moveMin(Number(e.target.value))}
            onPointerUp={commit}
            onKeyUp={commit}
          />
          <label htmlFor={`bmax-${model.id}`}>At most {effortLabel(efforts[notches.max])}</label>
          <input
            id={`bmax-${model.id}`}
            type="range"
            min={0}
            max={last}
            step={1}
            value={notches.max}
            disabled={disabled}
            onChange={(e) => moveMax(Number(e.target.value))}
            onPointerUp={commit}
            onKeyUp={commit}
          />
          {/* Full range IS "no bound" — the patch sends null and the entry is
              deleted, so an unbounded model never carries a stale row. */}
          <span className="mm-rail-sub">
            {notches.min <= 0 && notches.max >= last
              ? "No bound — any level this daemon offers."
              : `Sessions on ${model.label} run between ${effortLabel(efforts[notches.min])} and ${effortLabel(efforts[notches.max])}.`}
          </span>
        </div>
      )}
    </div>
  );
}
