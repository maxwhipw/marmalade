// ModelPicker.tsx — model chip + picker with a "Default" row (spec view 1).
//
// For a NOT-yet-created session the pick is local intent that rides the deferred
// first-send session.create (Android semantics). For an EXISTING session the
// pick drives session.model live (the daemon restarts the idle child so it
// applies now) — so the picker stays editable after create, not a read-only
// chip. It's disabled while a turn is running (the daemon rejects a mid-turn
// model change) or when disconnected.
//
// "Default" (null) is only meaningful for a new session — session.model has no
// "unset" (its model param is required). onChange still emits null when Default
// is chosen; the ChatView ignores a null pick on an existing session.

import type { ReactNode } from "react";
import type { ModelInfo } from "../gateway/types.js";

interface Props {
  models: ModelInfo[];
  /** Selected model id, or null for "Default" (let the daemon choose). */
  value: string | null;
  onChange: (modelId: string | null) => void;
  disabled?: boolean;
  /** The daemon's new-session default model id (model.list default_model), or
   *  null when unknown. When set, the "Default" row names it — "Default (Opus
   *  4.8)" — so a model-less new session's fate is visible; null keeps the bare
   *  "Default" (today's exact behavior). */
  defaultModel?: string | null;
}

/** The "Default" row's text. Bare "Default" when the daemon advertises no
 *  default (null/undefined); else "Default (<label>)" with default_model
 *  resolved to its human label — falling back to the raw id when the models
 *  list doesn't carry it. */
export function defaultOptionLabel(
  models: ModelInfo[],
  defaultModel: string | null | undefined,
): string {
  if (!defaultModel) return "Default";
  const label = models.find((m) => m.id === defaultModel)?.label ?? defaultModel;
  return `Default (${label})`;
}

export function ModelPicker({ models, value, onChange, disabled, defaultModel }: Props): ReactNode {
  return (
    <select
      className="mm-chip"
      value={value ?? ""}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value === "" ? null : e.target.value)}
      aria-label="model"
    >
      <option value="">{defaultOptionLabel(models, defaultModel)}</option>
      {models.map((m) => (
        <option key={m.id} value={m.id}>
          {m.label}
        </option>
      ))}
    </select>
  );
}
