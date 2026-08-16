// skills-store.ts — manifest persistence + the skills.list/skills.toggle
// surface over skills-sync.ts (fork-rest-triage Part A).
//
// The manifest is a JSON file next to sessions.db. Load-on-demand,
// write-through on toggle. Degrade posture: a corrupt or missing manifest is
// SEEDED from current reality (the managed symlinks already in each harness's
// skills dir) rather than treated as empty — an empty manifest would make the
// first sync REMOVE every skill the user installed via install.sh. Never crash,
// never uninstall by accident.

import { lstatSync, readFileSync, readdirSync, readlinkSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  listRegistrySkills, syncHarness,
  type HarnessTarget, type SkillManifest, type SyncResult,
} from "./skills-sync.js";

export interface SkillRow {
  name: string;
  description?: string;
  /** Enabled in at least one configured harness. */
  enabled: boolean;
  /** The harnesses it is enabled for. */
  harnesses: string[];
}

/** First `description:` line of a SKILL.md YAML frontmatter, capped. Cheap
 *  line-scan, not a YAML parser — good enough for picker subtitles. */
function skillDescription(registryDir: string, name: string): string | undefined {
  try {
    const text = readFileSync(join(registryDir, name, "SKILL.md"), "utf8").slice(0, 4000);
    const lines = text.split("\n");
    const idx = lines.findIndex((l) => /^description:/.test(l));
    if (idx < 0) return undefined;
    let value = lines[idx].replace(/^description:\s*/, "").trim();
    if (value === ">" || value === "|" || value === ">-" || value === "|-") {
      // YAML block scalar: the value is the following indented lines.
      const block: string[] = [];
      for (let i = idx + 1; i < lines.length && /^\s+\S/.test(lines[i]); i++) block.push(lines[i].trim());
      value = block.join(" ");
    }
    value = value.replace(/^["']|["']$/g, "").trim();
    return value ? value.slice(0, 300) : undefined;
  } catch {
    return undefined;
  }
}

export class SkillsStore {
  constructor(
    private manifestPath: string,
    private registryDir: string,
    private targets: HarnessTarget[],
    private log: (line: string) => void = () => {},
  ) {}

  /** Load the manifest; seed from on-disk reality when missing/corrupt. */
  load(): SkillManifest {
    try {
      const parsed = JSON.parse(readFileSync(this.manifestPath, "utf8")) as SkillManifest;
      if (parsed && typeof parsed === "object" && parsed.harnesses && typeof parsed.harnesses === "object") return parsed;
      this.log(`[skills] manifest malformed — reseeding from harness dirs`);
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== "ENOENT") this.log(`[skills] manifest unreadable (${(e as Error).message}) — reseeding from harness dirs`);
    }
    return this.seedFromReality();
  }

  /** Enabled set per harness = the managed symlinks currently in its dir. */
  private seedFromReality(): SkillManifest {
    const manifest: SkillManifest = { harnesses: {} };
    for (const t of this.targets) {
      const enabled: string[] = [];
      try {
        for (const name of readdirSync(t.skillsDir)) {
          const p = join(t.skillsDir, name);
          try {
            if (lstatSync(p).isSymbolicLink() && readlinkSync(p).startsWith(this.registryDir)) enabled.push(name);
          } catch { /* race — skip entry */ }
        }
      } catch { /* dir missing — nothing enabled */ }
      manifest.harnesses[t.harness] = { enabled: enabled.sort() };
    }
    return manifest;
  }

  private save(manifest: SkillManifest): void {
    // Write-through, atomically (tmp + rename) so a crash mid-write can't
    // leave a truncated manifest that would then "reseed" surprisingly.
    const tmp = `${this.manifestPath}.tmp`;
    writeFileSync(tmp, JSON.stringify(manifest, null, 2));
    renameSync(tmp, this.manifestPath);
  }

  list(): SkillRow[] {
    const manifest = this.load();
    const enabledFor = (name: string): string[] =>
      this.targets
        .filter((t) => {
          const rule = manifest.harnesses[t.harness];
          return rule ? rule.enabled === "all" || rule.enabled.includes(name) : false;
        })
        .map((t) => t.harness);
    return listRegistrySkills(this.registryDir).map((name) => {
      const harnesses = enabledFor(name);
      const description = skillDescription(this.registryDir, name);
      return { name, ...(description ? { description } : {}), enabled: harnesses.length > 0, harnesses };
    });
  }

  /** Toggle a skill across ALL configured harnesses (v1: one toggle, every
   *  harness), persist, and reconcile the symlinks immediately. */
  toggle(name: string, enabled: boolean): { applied: boolean; results: SyncResult[] } {
    if (!listRegistrySkills(this.registryDir).includes(name)) {
      throw new Error(`unknown skill: ${name}`);
    }
    const manifest = this.load();
    for (const t of this.targets) {
      const rule = manifest.harnesses[t.harness] ?? { enabled: [] };
      // "all" is materialized before an individual toggle can make sense.
      const current = rule.enabled === "all" ? listRegistrySkills(this.registryDir) : [...rule.enabled];
      const next = enabled ? [...new Set([...current, name])].sort() : current.filter((s) => s !== name);
      manifest.harnesses[t.harness] = { enabled: next };
    }
    this.save(manifest);
    const results = this.targets.map((t) => syncHarness(this.registryDir, t, manifest));
    return { applied: true, results };
  }
}
