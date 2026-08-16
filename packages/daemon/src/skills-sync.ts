// skills-sync.ts — M7, the manage-and-sync skills registry (Decision 4).
//
// Marmalade is the registry + sync + policy layer, NOT a walled garden
// (deliverables §manage-and-sync). The user's skills repo stays the
// backend; this formalizes the install.sh pattern: per-harness enable/disable
// manifest → symlink the enabled skills into each harness's skills dir. Skills
// stay usable by each harness standalone (no lock-in).
//
// Safety: we only ever create/remove SYMLINKS we manage. A real directory in a
// target is never touched (a skill authored directly in a harness dir is left
// alone), so sync can't destroy hand-made content.

import { existsSync, lstatSync, mkdirSync, readdirSync, readlinkSync, rmSync, symlinkSync } from "node:fs";
import { join } from "node:path";

export interface HarnessTarget {
  harness: string;
  /** Where this harness discovers skills (e.g. ~/.claude/skills). */
  skillsDir: string;
}

export interface SkillManifest {
  /** Per-harness enabled set: a list of skill names, or "all". Omitted harness
   *  = nothing synced for it. */
  harnesses: Record<string, { enabled: string[] | "all" }>;
}

export interface SyncResult {
  harness: string;
  linked: string[];
  removed: string[];
}

/** List skill names in the registry (immediate subdirs containing SKILL.md). */
export function listRegistrySkills(registryDir: string): string[] {
  if (!existsSync(registryDir)) return [];
  return readdirSync(registryDir, { withFileTypes: true })
    .filter((e) => e.isDirectory() && existsSync(join(registryDir, e.name, "SKILL.md")))
    .map((e) => e.name)
    .sort();
}

function isManagedSymlink(p: string, registryDir: string): boolean {
  try {
    if (!lstatSync(p).isSymbolicLink()) return false;
    // Only ours if it points inside the registry.
    return readlinkSync(p).startsWith(registryDir);
  } catch {
    return false;
  }
}

/** Reconcile one harness's skills dir against the manifest. Idempotent. */
export function syncHarness(
  registryDir: string,
  target: HarnessTarget,
  manifest: SkillManifest,
): SyncResult {
  const result: SyncResult = { harness: target.harness, linked: [], removed: [] };
  const rule = manifest.harnesses[target.harness];
  const available = listRegistrySkills(registryDir);
  const enabled = !rule ? [] : rule.enabled === "all" ? available : rule.enabled.filter((s) => available.includes(s));
  const enabledSet = new Set(enabled);

  mkdirSync(target.skillsDir, { recursive: true });

  // Remove stale symlinks WE manage that are no longer enabled.
  for (const name of readdirSync(target.skillsDir)) {
    const p = join(target.skillsDir, name);
    if (isManagedSymlink(p, registryDir) && !enabledSet.has(name)) {
      rmSync(p);
      result.removed.push(name);
    }
  }

  // Ensure a symlink for each enabled skill (skip if a real dir already holds it).
  for (const name of enabled) {
    const link = join(target.skillsDir, name);
    if (existsSync(link)) {
      if (isManagedSymlink(link, registryDir)) continue; // already ours
      // A real dir / foreign link occupies the name — leave it, don't clobber.
      continue;
    }
    symlinkSync(join(registryDir, name), link, "dir");
    result.linked.push(name);
  }

  return result;
}

/** Sync all configured harnesses. */
export function syncAll(
  registryDir: string,
  targets: HarnessTarget[],
  manifest: SkillManifest,
): SyncResult[] {
  return targets.map((t) => syncHarness(registryDir, t, manifest));
}
