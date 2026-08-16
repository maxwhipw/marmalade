// efforts.ts — pure helpers for the reasoning-effort vocabulary: labels, the
// per-model bounds editor's patch math, and the effort.clamped transcript line.
//
// The daemon PUBLISHES the vocabulary (model.list `efforts`, ordered cheapest →
// deepest) and owns the bounds (config `model_efforts`). Nothing here hardcodes
// a level list — every function takes the published order as an argument, so a
// daemon that adds "ludicrous" tomorrow renders without a webui change.

import type { ModelInfo } from "../gateway/types.js";

/** A per-model bound as it rides settings.get/update (protocol
 *  ModelEffortBounds). At least one edge is set; the daemon rejects an empty
 *  object and min > max. */
export interface EffortBounds {
  min?: string;
  max?: string;
}

/** Human label for an effort level. The daemon publishes the vocabulary; this
 *  only prettifies the ones we know, and passes anything new straight through
 *  so a daemon that adds a level still renders. */
export function effortLabel(effort: string): string {
  switch (effort) {
    case "low": return "Low";
    case "medium": return "Medium";
    case "high": return "High";
    case "xhigh": return "Very high";
    case "max": return "Max";
    default: return effort;
  }
}

/** Whether this daemon speaks per-model bounds at all. settings.get carries
 *  `model_efforts` ({} when unset) on a daemon that has the feature; an older
 *  one omits the key entirely and the whole control hides — offering an editor
 *  for a write the daemon would reject is worse than no editor. */
export function boundsSupported(settings: { model_efforts?: unknown } | null): boolean {
  return settings != null && settings.model_efforts != null;
}

/** The notch positions for a model, as indices into the published `efforts`
 *  order. An unset edge sits at the extreme (0 / last) — i.e. "unbounded" is
 *  the full-range position, which is exactly what makes the collapsed row
 *  identical to today. An edge naming a level this daemon no longer publishes
 *  falls back to its extreme rather than pinning the notch to a phantom. */
export function boundsToNotches(
  bounds: EffortBounds | null | undefined,
  efforts: string[],
): { min: number; max: number } {
  const last = Math.max(0, efforts.length - 1);
  const at = (level: string | undefined, fallback: number) => {
    if (!level) return fallback;
    const i = efforts.indexOf(level);
    return i < 0 ? fallback : i;
  };
  const min = at(bounds?.min, 0);
  const max = at(bounds?.max, last);
  // A stored pair the vocabulary reordered out from under us must never render
  // inverted — the daemon's own invariant is min <= max.
  return min <= max ? { min, max } : { min: max, max: min };
}

/** Build the settings.update patch for one model's notches.
 *
 *  settings.update takes a PER-MODEL patch: null deletes the entry, an object
 *  replaces it wholesale. So full range means "no bound at all" → null, and a
 *  one-sided bound omits the untouched edge rather than pinning it to the
 *  vocabulary extreme (which would silently break if the daemon ever adds a
 *  deeper level). */
export function boundsPatch(
  modelId: string,
  notches: { min: number; max: number },
  efforts: string[],
): Record<string, EffortBounds | null> {
  const last = efforts.length - 1;
  const atFloor = notches.min <= 0;
  const atCeiling = notches.max >= last;
  if (atFloor && atCeiling) return { [modelId]: null };
  const bounds: EffortBounds = {};
  if (!atFloor) bounds.min = efforts[notches.min];
  if (!atCeiling) bounds.max = efforts[notches.max];
  return { [modelId]: bounds };
}

/** One-line summary for a collapsed bounds row. */
export function boundsSummary(bounds: EffortBounds | null | undefined): string {
  if (!bounds || (!bounds.min && !bounds.max)) return "Any thinking level";
  if (bounds.min && bounds.max) {
    return bounds.min === bounds.max
      ? `Always ${effortLabel(bounds.min)}`
      : `${effortLabel(bounds.min)} – ${effortLabel(bounds.max)}`;
  }
  if (bounds.min) return `At least ${effortLabel(bounds.min)}`;
  return `At most ${effortLabel(bounds.max as string)}`;
}

/** The effort.clamped payload as it lands on the transcript (protocol
 *  EffortClampedPayload). */
export interface EffortClamp {
  requested: string;
  effective: string;
  model: string;
  bound: "min" | "max";
  limit: string;
}

/** Narrow a raw effort.clamped payload, or null if it isn't one we can render.
 *  Unknown/short payloads are dropped rather than rendered half-blank — the
 *  event still advances the seq watermark. */
export function readClamp(p: Record<string, unknown>): EffortClamp | null {
  const str = (v: unknown) => (typeof v === "string" && v.length > 0 ? v : null);
  const requested = str(p.requested);
  const effective = str(p.effective);
  const model = str(p.model);
  const bound = p.bound === "min" || p.bound === "max" ? p.bound : null;
  const limit = str(p.limit);
  if (!requested || !effective || !model || !bound || !limit) return null;
  return { requested, effective, model, bound, limit };
}

/** The quiet transcript line for a clamp: "Thinking adjusted to High — Opus 5
 *  minimum". A floor reads "minimum", a ceiling reads "limit". The model's
 *  display label comes from model.list when the catalog still lists it; a
 *  bound on a model the harness dropped shows the raw id rather than nothing. */
export function clampNoticeText(clamp: EffortClamp, models: ModelInfo[]): string {
  const label = models.find((m) => m.id === clamp.model)?.label ?? clamp.model;
  const kind = clamp.bound === "min" ? "minimum" : "limit";
  return `Thinking adjusted to ${effortLabel(clamp.effective)} — ${label} ${kind}`;
}
