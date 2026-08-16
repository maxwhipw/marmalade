import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync, rmSync, existsSync, readFileSync, symlinkSync, lstatSync } from "node:fs";
import { SkillsStore } from "../dist/skills-store.js";

// Fixture: a registry with skills a/b/c (a has a frontmatter description) and
// one harness target dir.
function fixture() {
  const root = join(tmpdir(), `skills-${randomUUID()}`);
  const registry = join(root, "registry");
  const target = join(root, "claude", "skills");
  for (const name of ["alpha", "beta", "gamma"]) {
    mkdirSync(join(registry, name), { recursive: true });
    writeFileSync(
      join(registry, name, "SKILL.md"),
      name === "alpha"
        ? `---\nname: alpha\ndescription: The first skill\n---\nbody`
        : `# ${name}`,
    );
  }
  mkdirSync(target, { recursive: true });
  const manifestPath = join(root, "skills-manifest.json");
  const store = new SkillsStore(manifestPath, registry, [{ harness: "claude-code", skillsDir: target }]);
  return { root, registry, target, manifestPath, store, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

test("list: registry skills with description; nothing enabled on a fresh empty target", () => {
  const f = fixture();
  try {
    const rows = f.store.list();
    assert.deepEqual(rows.map((r) => r.name), ["alpha", "beta", "gamma"]);
    assert.equal(rows[0].description, "The first skill");
    assert.ok(rows.every((r) => !r.enabled));
  } finally { f.cleanup(); }
});

test("missing manifest SEEDS from reality — pre-existing managed symlinks stay enabled and survive a toggle", () => {
  const f = fixture();
  try {
    // Simulate install.sh reality: alpha + beta already symlinked.
    symlinkSync(join(f.registry, "alpha"), join(f.target, "alpha"), "dir");
    symlinkSync(join(f.registry, "beta"), join(f.target, "beta"), "dir");
    const rows = f.store.list();
    assert.deepEqual(rows.filter((r) => r.enabled).map((r) => r.name), ["alpha", "beta"]);
    // Toggling gamma ON must not uninstall alpha/beta.
    f.store.toggle("gamma", true);
    assert.ok(existsSync(join(f.target, "alpha")), "alpha survived");
    assert.ok(existsSync(join(f.target, "beta")), "beta survived");
    assert.ok(lstatSync(join(f.target, "gamma")).isSymbolicLink(), "gamma linked");
    const manifest = JSON.parse(readFileSync(f.manifestPath, "utf8"));
    assert.deepEqual(manifest.harnesses["claude-code"].enabled, ["alpha", "beta", "gamma"]);
  } finally { f.cleanup(); }
});

test("toggle round-trip: enable links, disable unlinks, manifest persists", () => {
  const f = fixture();
  try {
    assert.equal(f.store.toggle("alpha", true).applied, true);
    assert.ok(lstatSync(join(f.target, "alpha")).isSymbolicLink());
    assert.equal(f.store.list().find((r) => r.name === "alpha")!.enabled, true);
    f.store.toggle("alpha", false);
    assert.equal(existsSync(join(f.target, "alpha")), false);
    assert.equal(f.store.list().find((r) => r.name === "alpha")!.enabled, false);
  } finally { f.cleanup(); }
});

test("corrupt manifest reseeds from reality instead of crashing or uninstalling", () => {
  const f = fixture();
  try {
    symlinkSync(join(f.registry, "beta"), join(f.target, "beta"), "dir");
    writeFileSync(f.manifestPath, "{ not json !!!");
    const rows = f.store.list();
    assert.deepEqual(rows.filter((r) => r.enabled).map((r) => r.name), ["beta"]);
  } finally { f.cleanup(); }
});

test("unknown skill toggle throws a clean error", () => {
  const f = fixture();
  try {
    assert.throws(() => f.store.toggle("nope", true), /unknown skill/);
  } finally { f.cleanup(); }
});

test("a real directory occupying a skill name is never clobbered", () => {
  const f = fixture();
  try {
    // Hand-authored skill dir (not a symlink) with the same name.
    mkdirSync(join(f.target, "alpha"));
    writeFileSync(join(f.target, "alpha", "precious.md"), "handmade");
    f.store.toggle("alpha", true);
    f.store.toggle("alpha", false);
    assert.ok(existsSync(join(f.target, "alpha", "precious.md")), "real dir untouched");
  } finally { f.cleanup(); }
});
