import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync, rmSync, symlinkSync } from "node:fs";
import { listDirConfined } from "../dist/fs-browse.js";

// A fake "home" with content inside and a sibling OUTSIDE it.
function fixture() {
  const root = join(tmpdir(), `fsb-${randomUUID()}`);
  const home = join(root, "home");
  const outside = join(root, "outside");
  mkdirSync(join(home, "coding", "project"), { recursive: true });
  writeFileSync(join(home, "coding", "readme.txt"), "x");
  mkdirSync(join(home, ".secret"), { recursive: true });
  mkdirSync(outside, { recursive: true });
  writeFileSync(join(outside, "loot.txt"), "x");
  return { root, home, outside, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

test("lists a dir inside home: dirs flagged, dotfiles hidden", () => {
  const f = fixture();
  try {
    const r = listDirConfined(join(f.home, "coding"), f.home);
    assert.deepEqual(r.entries.toSorted((a, b) => a.name.localeCompare(b.name)), [
      { name: "project", dir: true },
      { name: "readme.txt", dir: false },
    ]);
    const top = listDirConfined(f.home, f.home);
    assert.ok(!top.entries.some((e) => e.name === ".secret"), "dotdirs hidden");
  } finally { f.cleanup(); }
});

test("showHidden surfaces dot-entries (picker 'Show hidden' toggle)", () => {
  const f = fixture();
  try {
    const top = listDirConfined(f.home, f.home, true);
    assert.ok(top.entries.some((e) => e.name === ".secret" && e.dir), "dotdir shown when showHidden");
  } finally { f.cleanup(); }
});

test("`..` traversal out of home is rejected (realpath confinement)", () => {
  const f = fixture();
  try {
    assert.throws(() => listDirConfined(join(f.home, "coding", "..", "..", "outside"), f.home), /outside home/);
    assert.throws(() => listDirConfined("../outside", f.home), /outside home/);
  } finally { f.cleanup(); }
});

test("a symlink pointing outside home is rejected", () => {
  const f = fixture();
  try {
    symlinkSync(f.outside, join(f.home, "sneaky"), "dir");
    assert.throws(() => listDirConfined(join(f.home, "sneaky"), f.home), /outside home/);
  } finally { f.cleanup(); }
});

test("a symlink to a dir INSIDE home resolves and lists fine", () => {
  const f = fixture();
  try {
    symlinkSync(join(f.home, "coding"), join(f.home, "workspaces"), "dir");
    const r = listDirConfined(join(f.home, "workspaces"), f.home);
    assert.ok(r.entries.some((e) => e.name === "project" && e.dir));
    // Symlinked dir entries are flagged dir:true (stat, not dirent).
    const top = listDirConfined(f.home, f.home);
    assert.ok(top.entries.some((e) => e.name === "workspaces" && e.dir));
  } finally { f.cleanup(); }
});

test("nonexistent path and file-not-dir produce clean errors", () => {
  const f = fixture();
  try {
    assert.throws(() => listDirConfined(join(f.home, "nope"), f.home));
    assert.throws(() => listDirConfined(join(f.home, "coding", "readme.txt"), f.home));
  } finally { f.cleanup(); }
});
