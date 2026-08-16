import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync, rmSync, existsSync, lstatSync, symlinkSync } from "node:fs";
import { listRegistrySkills, syncHarness, syncAll } from "../dist/skills-sync.js";

function scratch(): { root: string; registry: string; skillsDir: (h: string) => string } {
  const root = join(tmpdir(), `msk-${randomUUID()}`);
  const registry = join(root, "registry");
  mkdirSync(registry, { recursive: true });
  for (const s of ["forgejo", "agent-wiki", "voice"]) {
    mkdirSync(join(registry, s), { recursive: true });
    writeFileSync(join(registry, s, "SKILL.md"), `# ${s}`);
  }
  // A non-skill dir (no SKILL.md) — must be ignored.
  mkdirSync(join(registry, "not-a-skill"), { recursive: true });
  return { root, registry, skillsDir: (h) => join(root, h, "skills") };
}

test("listRegistrySkills finds only dirs with SKILL.md", () => {
  const s = scratch();
  try {
    assert.deepEqual(listRegistrySkills(s.registry), ["agent-wiki", "forgejo", "voice"]);
  } finally { rmSync(s.root, { recursive: true, force: true }); }
});

test("syncHarness links the enabled subset and is idempotent", () => {
  const s = scratch();
  try {
    const target = { harness: "claude-code", skillsDir: s.skillsDir("claude-code") };
    const manifest = { harnesses: { "claude-code": { enabled: ["forgejo", "voice"] } } };
    const r1 = syncHarness(s.registry, target, manifest);
    assert.deepEqual(r1.linked.sort(), ["forgejo", "voice"]);
    assert.ok(existsSync(join(target.skillsDir, "forgejo")));
    assert.ok(!existsSync(join(target.skillsDir, "agent-wiki"))); // not enabled
    // Idempotent: second run links nothing new.
    const r2 = syncHarness(s.registry, target, manifest);
    assert.deepEqual(r2.linked, []);
  } finally { rmSync(s.root, { recursive: true, force: true }); }
});

test("'all' links every registry skill; disabling removes the managed symlink", () => {
  const s = scratch();
  try {
    const target = { harness: "opencode", skillsDir: s.skillsDir("opencode") };
    syncHarness(s.registry, target, { harnesses: { opencode: { enabled: "all" } } });
    assert.equal(listRegistrySkills(s.registry).length, 3);
    assert.ok(existsSync(join(target.skillsDir, "agent-wiki")));
    // Now disable agent-wiki → its managed symlink is removed.
    const r = syncHarness(s.registry, target, { harnesses: { opencode: { enabled: ["forgejo"] } } });
    assert.ok(r.removed.includes("agent-wiki"));
    assert.ok(!existsSync(join(target.skillsDir, "agent-wiki")));
    assert.ok(existsSync(join(target.skillsDir, "forgejo")));
  } finally { rmSync(s.root, { recursive: true, force: true }); }
});

test("a real (hand-made) skill dir in the target is never clobbered", () => {
  const s = scratch();
  try {
    const target = { harness: "claude-code", skillsDir: s.skillsDir("claude-code") };
    mkdirSync(join(target.skillsDir, "forgejo"), { recursive: true });
    writeFileSync(join(target.skillsDir, "forgejo", "SKILL.md"), "# hand-made, do not touch");
    const r = syncHarness(s.registry, target, { harnesses: { "claude-code": { enabled: "all" } } });
    // forgejo already occupied by a real dir → not linked, not removed.
    assert.ok(!r.linked.includes("forgejo"));
    assert.ok(lstatSync(join(target.skillsDir, "forgejo")).isDirectory());
    assert.ok(!lstatSync(join(target.skillsDir, "forgejo")).isSymbolicLink());
  } finally { rmSync(s.root, { recursive: true, force: true }); }
});

test("syncAll handles multiple harnesses with different manifests", () => {
  const s = scratch();
  try {
    const targets = [
      { harness: "claude-code", skillsDir: s.skillsDir("claude-code") },
      { harness: "opencode", skillsDir: s.skillsDir("opencode") },
    ];
    const results = syncAll(s.registry, targets, {
      harnesses: { "claude-code": { enabled: "all" }, opencode: { enabled: ["voice"] } },
    });
    assert.equal(results.find((r) => r.harness === "claude-code")!.linked.length, 3);
    assert.deepEqual(results.find((r) => r.harness === "opencode")!.linked, ["voice"]);
  } finally { rmSync(s.root, { recursive: true, force: true }); }
});
