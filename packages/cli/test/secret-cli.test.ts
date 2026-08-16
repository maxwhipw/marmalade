// secret-cli.test.ts — `marmalade secret …`. The verbs run the REAL keyring
// module (packages/daemon/src/keyring.ts) against a /bin/sh fake store in a
// temp dir: no gopass, no daemon, no network — and the round-trip actually
// proves get/set agree instead of asserting against a mock.
//
// The invariants under test, in order of how much they'd hurt to lose:
//   * a secret value reaches STDOUT and nowhere else (prompts, status and
//     errors are stderr), so `marmalade secret get x | …` is the credential;
//   * a value never appears in an error, even when the backend echoes it back;
//   * a mismatch at the confirm prompt stores NOTHING;
//   * an unconfigured keyring is a clear failure, never a silent default.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { mkdirSync, rmSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { secretCommand, type SecretCliDeps } from "../dist/secret-cli.js";

const CONFIG_PATH = "/tmp/fake/marmalade/config.json";

/** A throwaway keyring backed by one file per entry under a temp dir. */
function fakeStore(): { dir: string; keyring: Record<string, string[]>; cleanup: () => void } {
  const dir = join(tmpdir(), `secretcli-${randomUUID()}`);
  mkdirSync(dir, { recursive: true });
  const sh = (script: string) => ["/bin/sh", "-c", script, "--", "{entry}"];
  return {
    dir,
    keyring: {
      command: sh(`cat "${dir}/$1"`),
      insertCommand: sh(`cat > "${dir}/$1"`),
      listCommand: sh(`ls -1 "${dir}"`),
      removeCommand: sh(`rm "${dir}/$1"`),
    },
    cleanup: () => rmSync(dir, { recursive: true, force: true }),
  };
}

interface Run {
  code: number;
  stdout: string;
  stderr: string;
  prompts: string[];
}

/** Run one verb. `piped` is stdin (null = a TTY, so the prompts are used);
 *  `typed` is what the user "types" at each echo-off prompt, in order. */
async function run(
  argv: string[],
  opts: { keyring?: Record<string, string[]>; piped?: string | null; typed?: string[] } = {},
): Promise<Run> {
  let stdout = "";
  let stderr = "";
  const prompts: string[] = [];
  const typed = [...(opts.typed ?? [])];
  const deps: SecretCliDeps = {
    ...(opts.keyring ? { keyring: opts.keyring } : {}),
    configPath: CONFIG_PATH,
    out: (t) => { stdout += t; },
    err: (t) => { stderr += t; },
    readStdin: async () => opts.piped ?? null,
    promptSecret: async (label) => { prompts.push(label); return typed.shift() ?? ""; },
  };
  const code = await secretCommand(argv, deps);
  return { code, stdout, stderr, prompts };
}

test("set then get round-trips through the store; the value is the ONLY stdout", async () => {
  const store = fakeStore();
  try {
    // The trailing newline a pipe always adds is stripped, so the value
    // round-trips to what was echoed.
    const set = await run(["set", "imap-password"], { keyring: store.keyring, piped: "hunter2 with space\n" });
    assert.equal(set.code, 0);
    assert.equal(set.stdout, "", "a successful set prints nothing to stdout");
    assert.match(set.stderr, /stored imap-password/);
    assert.equal(readFileSync(join(store.dir, "imap-password"), "utf8"), "hunter2 with space");

    const got = await run(["get", "imap-password"], { keyring: store.keyring });
    assert.equal(got.code, 0);
    assert.equal(got.stdout, "hunter2 with space\n", "stdout is exactly the secret plus one newline");
    assert.equal(got.stderr, "", "nothing else is written when a fetch succeeds");
  } finally { store.cleanup(); }
});

test("set from a TTY prompts twice; a mismatch stores nothing", async () => {
  const store = fakeStore();
  try {
    const ok = await run(["set", "matched"], {
      keyring: store.keyring, piped: null, typed: ["s3cret", "s3cret"],
    });
    assert.equal(ok.code, 0);
    assert.equal(ok.prompts.length, 2);
    assert.match(ok.prompts[0]!, /value for matched/);
    assert.match(ok.prompts[1]!, /confirm/);
    assert.equal(readFileSync(join(store.dir, "matched"), "utf8"), "s3cret");

    const bad = await run(["set", "mismatched"], {
      keyring: store.keyring, piped: null, typed: ["s3cret", "typo"],
    });
    assert.equal(bad.code, 1);
    assert.match(bad.stderr, /did not match/);
    assert.doesNotMatch(bad.stderr, /s3cret|typo/, "neither typed value may be echoed back");
    assert.equal(existsSync(join(store.dir, "mismatched")), false, "nothing was stored");
  } finally { store.cleanup(); }
});

test("set refuses an empty value (piped or typed) — a blank credential never lands", async () => {
  const store = fakeStore();
  try {
    const piped = await run(["set", "blank"], { keyring: store.keyring, piped: "\n" });
    assert.equal(piped.code, 1);
    assert.match(piped.stderr, /refusing to store an empty value in "blank"/);
    assert.equal(existsSync(join(store.dir, "blank")), false);

    const typedEmpty = await run(["set", "blank"], { keyring: store.keyring, piped: null, typed: ["", ""] });
    assert.equal(typedEmpty.code, 1);
    assert.match(typedEmpty.stderr, /refusing to store an empty value/);
  } finally { store.cleanup(); }
});

test("get of a missing entry fails on stderr with an empty stdout", async () => {
  const store = fakeStore();
  try {
    const got = await run(["get", "absent"], { keyring: store.keyring });
    assert.equal(got.code, 1);
    assert.equal(got.stdout, "", "a failed get must not put anything on stdout");
    assert.match(got.stderr, /absent/);
  } finally { store.cleanup(); }
});

test("a backend that echoes the value to stderr cannot leak it through an error", async () => {
  // The chatty-backend shape: `cat >&2` replays stdin (the secret) into the
  // diagnostic channel, then fails. keyring.ts redacts before we ever see it.
  const keyring = {
    insertCommand: ["/bin/sh", "-c", `cat >&2; echo " store is locked" >&2; exit 4`, "--", "{entry}"],
  };
  const r = await run(["set", "api/key"], { keyring, piped: "S3NT1NEL-hunter2\n" });
  assert.equal(r.code, 1);
  assert.match(r.stderr, /store is locked/);
  assert.match(r.stderr, /\[redacted\]/);
  assert.doesNotMatch(r.stderr + r.stdout, /S3NT1NEL|hunter2/, "the value must not reach any output stream");
});

test("ls prints names on stdout; --json wraps them; an empty store says so on stderr", async () => {
  const store = fakeStore();
  try {
    const empty = await run(["ls"], { keyring: store.keyring });
    assert.equal(empty.code, 0);
    assert.equal(empty.stdout, "");
    assert.match(empty.stderr, /empty/);

    await run(["set", "alpha"], { keyring: store.keyring, piped: "a" });
    await run(["set", "beta"], { keyring: store.keyring, piped: "b" });

    const plain = await run(["ls"], { keyring: store.keyring });
    assert.equal(plain.code, 0);
    assert.equal(plain.stdout, "alpha\nbeta\n");

    const json = await run(["ls", "--json"], { keyring: store.keyring });
    assert.deepEqual(JSON.parse(json.stdout), { ok: true, entries: ["alpha", "beta"] });
  } finally { store.cleanup(); }
});

test("rm deletes; a backend that rejects a missing entry surfaces as a failure", async () => {
  const store = fakeStore();
  try {
    await run(["set", "doomed"], { keyring: store.keyring, piped: "x" });
    const gone = await run(["rm", "doomed", "--json"], { keyring: store.keyring });
    assert.equal(gone.code, 0);
    assert.deepEqual(JSON.parse(gone.stdout), { ok: true, entry: "doomed" });
    assert.equal(existsSync(join(store.dir, "doomed")), false);

    const missing = await run(["rm", "doomed"], { keyring: store.keyring });
    assert.equal(missing.code, 1);
    assert.match(missing.stderr, /doomed/);
  } finally { store.cleanup(); }
});

test("check: passes on a live store, reports the backends, counts entries", async () => {
  const store = fakeStore();
  const passphrase = join(tmpdir(), `secretcli-pass-${randomUUID()}`);
  writeFileSync(passphrase, "hunter2-age\n", { mode: 0o600 });
  try {
    await run(["set", "alpha"], { keyring: store.keyring, piped: "a" });
    const keyring = { ...store.keyring, passphraseFile: passphrase, meteredKeyEntry: "anthropic/metered" };

    const human = await run(["check"], { keyring });
    assert.equal(human.code, 0);
    assert.match(human.stderr, new RegExp(CONFIG_PATH.replaceAll("/", "\\/")));
    assert.match(human.stderr, /1 entry/);
    assert.match(human.stderr, /ok\n$/);
    assert.equal(human.stdout, "");

    const json = await run(["check", "--json"], { keyring });
    const parsed = JSON.parse(json.stdout) as { ok: boolean; config_path: string; checks: { name: string; ok: boolean }[] };
    assert.equal(parsed.ok, true);
    assert.equal(parsed.config_path, CONFIG_PATH);
    assert.deepEqual(parsed.checks.map((c) => c.name), ["configured", "passphrase file", "list", "metered key"]);
  } finally {
    store.cleanup();
    rmSync(passphrase, { force: true });
  }
});

test("check: unreadable passphrase file fails, and the list probe is not attempted", async () => {
  const store = fakeStore();
  try {
    const r = await run(["check", "--json"], {
      keyring: { ...store.keyring, passphraseFile: join(tmpdir(), `absent-${randomUUID()}`) },
    });
    assert.equal(r.code, 1);
    const parsed = JSON.parse(r.stdout) as { ok: boolean; checks: { name: string; ok: boolean }[] };
    assert.equal(parsed.ok, false);
    assert.deepEqual(parsed.checks.map((c) => c.name), ["configured", "passphrase file"]);
    assert.equal(parsed.checks.at(-1)!.ok, false);
  } finally { store.cleanup(); }
});

test("check: a store that will not list fails with the backend's own diagnostic", async () => {
  const r = await run(["check"], {
    keyring: { listCommand: ["/bin/sh", "-c", `echo "store is locked" >&2; exit 2`, "--", "{entry}"] },
  });
  assert.equal(r.code, 1);
  assert.match(r.stderr, /store is locked/);
  assert.match(r.stderr, /FAILED/);
});

test("no keyring block: every verb fails and names the config file", async () => {
  for (const argv of [["get", "x"], ["set", "x"], ["ls"], ["rm", "x"], ["check"]]) {
    const r = await run(argv, { piped: "v" });
    assert.equal(r.code, 1, `${argv[0]} must fail without a keyring`);
    assert.match(r.stderr, new RegExp(CONFIG_PATH.replaceAll("/", "\\/")), `${argv[0]} names the config path`);
  }
  // --json keeps the machine contract even on the unconfigured path (get is
  // excluded by design — see the --json rejection test below).
  const json = await run(["ls", "--json"], {});
  assert.equal(JSON.parse(json.stdout).ok, false);
});

test("--json is positional-independent: it never swallows the entry name", async () => {
  const store = fakeStore();
  try {
    await run(["set", "alpha"], { keyring: store.keyring, piped: "a" });
    await run(["set", "doomed"], { keyring: store.keyring, piped: "x" });

    // The bug: cron-cli's parseFlags treats `--json` as value-taking, so
    // `rm --json doomed` bound the ENTRY as the flag's value and lost the
    // positional entirely — a usage error instead of a delete.
    const rmBefore = await run(["rm", "--json", "doomed"], { keyring: store.keyring });
    assert.equal(rmBefore.code, 0);
    assert.deepEqual(JSON.parse(rmBefore.stdout), { ok: true, entry: "doomed" });
    assert.equal(existsSync(join(store.dir, "doomed")), false);

    await run(["set", "doomed2"], { keyring: store.keyring, piped: "x" });
    const rmAfter = await run(["rm", "doomed2", "--json"], { keyring: store.keyring });
    assert.equal(rmAfter.code, 0);
    assert.deepEqual(JSON.parse(rmAfter.stdout), { ok: true, entry: "doomed2" });

    // ls and check take no positional, but the flag must still parse from
    // either side of the subcommand.
    for (const argv of [["ls", "--json"], ["--json", "ls"]]) {
      const r = await run(argv, { keyring: store.keyring });
      assert.equal(r.code, 0, `${argv.join(" ")} succeeds`);
      assert.deepEqual(JSON.parse(r.stdout), { ok: true, entries: ["alpha"] });
    }
    for (const argv of [["check", "--json"], ["--json", "check"]]) {
      const r = await run(argv, { keyring: store.keyring });
      assert.equal(r.code, 0, `${argv.join(" ")} succeeds`);
      assert.equal(JSON.parse(r.stdout).ok, true);
    }

    // set, both orders, both round-tripping into the store.
    const setBefore = await run(["set", "--json", "beta"], { keyring: store.keyring, piped: "b\n" });
    assert.equal(setBefore.code, 0);
    assert.deepEqual(JSON.parse(setBefore.stdout), { ok: true, entry: "beta" });
    assert.equal(readFileSync(join(store.dir, "beta"), "utf8"), "b");
  } finally { store.cleanup(); }
});

test("an unknown flag is a usage error, never an entry name", async () => {
  const store = fakeStore();
  try {
    // A typo must not fall through to the positional list — `rm --jsonn x`
    // deleting x while pretending the flag was fine is the failure mode.
    const typo = await run(["rm", "--jsonn", "doomed"], { keyring: store.keyring });
    assert.equal(typo.code, 1);
    assert.match(typo.stderr, /unknown flag "--jsonn"/);
    assert.equal(typo.stdout, "");

    // Not even in an assignment form: --json is boolean-only here.
    const assigned = await run(["ls", "--json=true"], { keyring: store.keyring });
    assert.equal(assigned.code, 1);
    assert.match(assigned.stderr, /unknown flag "--json=true"/);
  } finally { store.cleanup(); }
});

test("get rejects --json: its stdout is the raw value or nothing", async () => {
  const store = fakeStore();
  try {
    await run(["set", "alpha"], { keyring: store.keyring, piped: "a" });

    // The asymmetry this closes: --json used to produce an envelope on
    // FAILURE and the raw credential on SUCCESS, so neither consumer worked.
    for (const argv of [["get", "--json", "alpha"], ["get", "alpha", "--json"]]) {
      const r = await run(argv, { keyring: store.keyring });
      assert.equal(r.code, 1, `${argv.join(" ")} is refused`);
      assert.equal(r.stdout, "", "the refusal puts nothing on stdout — least of all the value");
      assert.match(r.stderr, /does not support --json/);
      assert.match(r.stderr, /raw secret value/);
    }

    // Without the flag the verb is unchanged.
    const plain = await run(["get", "alpha"], { keyring: store.keyring });
    assert.equal(plain.code, 0);
    assert.equal(plain.stdout, "a\n");
  } finally { store.cleanup(); }
});

test("missing entry argument and unknown subcommands print usage / a usage error", async () => {
  const store = fakeStore();
  try {
    const noEntry = await run(["get"], { keyring: store.keyring });
    assert.equal(noEntry.code, 1);
    assert.match(noEntry.stderr, /usage: marmalade secret get <entry>/);

    const bogus = await run(["bogus"], { keyring: store.keyring });
    assert.equal(bogus.code, 1);
    assert.match(bogus.stderr, /usage: marmalade secret/);

    assert.equal((await run(["help"], { keyring: store.keyring })).code, 0);
    assert.equal((await run([], { keyring: store.keyring })).code, 0);
  } finally { store.cleanup(); }
});
