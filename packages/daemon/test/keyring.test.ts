// keyring.test.ts — the secret broker (keyring.ts). The invariants: stdout is
// the secret and NEVER leaks into an error; a blank credential fails loudly
// instead of propagating; the child env is an allowlist built from empty; and
// {entry} reaches the argv as data, never as shell syntax.
//
// No gopass required — every "backend" here is a /bin/sh script, which also
// proves the configurable-command design works for a non-gopass tool.

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { writeFileSync, readFileSync, rmSync } from "node:fs";
import { fetchSecret, storeSecret, listSecrets, removeSecret, KeyringError } from "../dist/keyring.js";
import { loadConfigFile, defaultConfig } from "../dist/config.js";

/** A fake backend: `sh -c <script> -- {entry}` → $1 is the substituted entry. */
function sh(script: string): string[] {
  return ["/bin/sh", "-c", script, "--", "{entry}"];
}

function withFile(content: string, fn: (path: string) => Promise<void> | void) {
  const path = join(tmpdir(), `keyring-${randomUUID()}`);
  writeFileSync(path, content, { mode: 0o600 });
  return (async () => {
    try { await fn(path); } finally { rmSync(path, { force: true }); }
  })();
}

test("happy path: trailing newline stripped, inner/leading whitespace kept", async () => {
  const secret = await fetchSecret("any/entry", {
    command: sh(`printf '  sk-ant-with inner space\\n'`),
  });
  assert.equal(secret, "  sk-ant-with inner space");
  // Multiple trailing newlines go too; nothing else is trimmed.
  assert.equal(
    await fetchSecret("x", { command: sh(`printf 'v\\n\\n\\n'`) }),
    "v",
  );
});

test("{entry} is substituted into every argv slot", async () => {
  const secret = await fetchSecret("mail/imap", { command: sh(`printf '%s' "$1"`) });
  assert.equal(secret, "mail/imap");
});

test("non-zero exit → KeyringError that never contains stdout", async () => {
  const err = await fetchSecret("secrets/api", {
    // Prints a partial secret to stdout, THEN fails — the classic leak shape.
    command: sh(`printf 'sk-ant-LEAKED'; echo "entry not found" >&2; exit 3`),
  }).then(() => null, (e) => e as Error);

  assert.ok(err instanceof KeyringError, `expected KeyringError, got ${err}`);
  assert.match(err.message, /secrets\/api/);
  assert.match(err.message, /exit 3/);
  assert.match(err.message, /entry not found/, "stderr is the diagnostic channel");
  assert.doesNotMatch(err.message, /LEAKED/, "stdout must never reach an error message");
});

test("zero exit with empty stdout → KeyringError (a blank credential must not propagate)", async () => {
  await assert.rejects(
    () => fetchSecret("blank/entry", { command: sh(`exit 0`) }),
    (e: Error) => e instanceof KeyringError && /empty secret/.test(e.message) && /blank\/entry/.test(e.message),
  );
  // Newline-only output is empty too, not a one-character secret.
  await assert.rejects(
    () => fetchSecret("blank/entry", { command: sh(`printf '\\n'`) }),
    KeyringError,
  );
});

test("passphraseFile lands in the child as trimmed GOPASS_AGE_PASSWORD", async () => {
  await withFile("  hunter2-age  \n", async (path) => {
    const seen = await fetchSecret("x", {
      command: sh(`printf '[%s]' "$GOPASS_AGE_PASSWORD"`),
      passphraseFile: path,
    });
    assert.equal(seen, "[hunter2-age]");
  });
  // Absent config = the var isn't set at all (the fake prints an empty
  // bracket pair, which is a non-empty secret, so this reaches the assert).
  assert.equal(
    await fetchSecret("x", { command: sh(`printf '[%s]' "$GOPASS_AGE_PASSWORD"`) }),
    "[]",
  );
});

test("configured-but-missing passphrase file → KeyringError before spawning", async () => {
  await assert.rejects(
    () => fetchSecret("x", {
      command: sh(`printf 'never-runs'`),
      passphraseFile: join(tmpdir(), `absent-${randomUUID()}`),
    }),
    (e: Error) => e instanceof KeyringError && /passphrase file .* unreadable/.test(e.message),
  );
});

test("child env is an allowlist from empty — the daemon's env is not inherited", async () => {
  process.env.MARMALADE_KEYRING_LEAK_CANARY = "should-not-be-visible";
  try {
    const seen = await fetchSecret("x", {
      command: sh(`printf '[%s][%s]' "$MARMALADE_KEYRING_LEAK_CANARY" "$PATH"`),
    });
    assert.match(seen, /^\[\]\[/, "an unrelated process.env var must not reach the child");
    assert.ok(seen.length > 4, "PATH is allowlisted through (gopass needs it)");
  } finally {
    delete process.env.MARMALADE_KEYRING_LEAK_CANARY;
  }
});

test("a command that can't start fails as KeyringError, not an unhandled throw", async () => {
  await assert.rejects(
    () => fetchSecret("x", { command: [join(tmpdir(), `no-such-bin-${randomUUID()}`), "{entry}"] }),
    KeyringError,
  );
});

// --- storeSecret (the write side, secret-entry flow) ----------------------
//
// The invariant that matters: the VALUE travels on stdin. argv is world-
// readable via /proc and `ps`, so a value that shows up there is a leak even
// though the write "worked".

/** A fake insert backend. $1 is the substituted entry; stdin is the value.
 *  Both are recorded to `outPrefix.{argv,stdin}` so the test can inspect what
 *  the child actually saw. */
function shInsert(outPrefix: string, extra = ""): string[] {
  return [
    "/bin/sh", "-c",
    `printf '%s' "$*" > "${outPrefix}.argv"; cat > "${outPrefix}.stdin"; ${extra}`,
    "--", "{entry}",
  ];
}

function tmpPrefix(): string {
  return join(tmpdir(), `kstore-${randomUUID()}`);
}

test("storeSecret: the value arrives on STDIN and never appears in argv", async () => {
  const out = tmpPrefix();
  try {
    await storeSecret("marmalade/email/imap-password", "S3NT1NEL-hunter2", {
      insertCommand: shInsert(out),
    });
    assert.equal(readFileSync(`${out}.stdin`, "utf8"), "S3NT1NEL-hunter2",
      "value written raw to stdin, no added newline (fetchSecret strips trailing newlines, so adding one would not round-trip)");
    const argv = readFileSync(`${out}.argv`, "utf8");
    assert.match(argv, /marmalade\/email\/imap-password/, "{entry} is substituted into argv");
    assert.doesNotMatch(argv, /S3NT1NEL/, "the value must NEVER reach argv (/proc/<pid>/cmdline, ps)");
  } finally {
    rmSync(`${out}.argv`, { force: true });
    rmSync(`${out}.stdin`, { force: true });
  }
});

test("storeSecret: a value with newlines and shell metacharacters round-trips as data", async () => {
  const out = tmpPrefix();
  const nasty = "line1\n$(touch /tmp/pwned); `id`\nline3";
  try {
    await storeSecret("weird/entry", nasty, { insertCommand: shInsert(out) });
    assert.equal(readFileSync(`${out}.stdin`, "utf8"), nasty);
  } finally {
    rmSync(`${out}.argv`, { force: true });
    rmSync(`${out}.stdin`, { force: true });
  }
});

test("storeSecret: non-zero exit → KeyringError whose message never contains the value", async () => {
  const err = await storeSecret("secrets/api", "S3NT1NEL-hunter2", {
    // Echoes its own stdin to stderr, THEN fails — a chatty backend turning
    // the diagnostic channel into a leak. keyring.ts redacts it.
    insertCommand: ["/bin/sh", "-c", `cat >&2; echo " store is locked" >&2; exit 4`, "--", "{entry}"],
  }).then(() => null, (e) => e as Error);

  assert.ok(err instanceof KeyringError, `expected KeyringError, got ${err}`);
  assert.match(err.message, /secrets\/api/);
  assert.match(err.message, /exit 4/);
  assert.match(err.message, /store is locked/, "stderr is still the diagnostic channel");
  assert.doesNotMatch(err.message, /S3NT1NEL|hunter2/, "the value must never reach an error message");
  assert.match(err.message, /\[redacted\]/, "an echoed value is redacted, not dropped silently");
});

test("storeSecret: child env is an allowlist from empty; passphraseFile lands as GOPASS_AGE_PASSWORD", async () => {
  const out = tmpPrefix();
  process.env.MARMALADE_KEYRING_LEAK_CANARY = "should-not-be-visible";
  try {
    await withFile("  hunter2-age  \n", async (path) => {
      await storeSecret("x", "v", {
        insertCommand: ["/bin/sh", "-c",
          `printf '[%s][%s]' "$MARMALADE_KEYRING_LEAK_CANARY" "$GOPASS_AGE_PASSWORD" > "${out}.env"; cat > /dev/null`,
          "--", "{entry}"],
        passphraseFile: path,
      });
      assert.equal(readFileSync(`${out}.env`, "utf8"), "[][hunter2-age]");
    });
  } finally {
    delete process.env.MARMALADE_KEYRING_LEAK_CANARY;
    rmSync(`${out}.env`, { force: true });
  }
});

test("storeSecret: a command that can't start fails as KeyringError, not an unhandled EPIPE", async () => {
  await assert.rejects(
    () => storeSecret("x", "v", { insertCommand: [join(tmpdir(), `no-such-bin-${randomUUID()}`), "{entry}"] }),
    KeyringError,
  );
});

test("storeSecret: a backend that exits WITHOUT reading stdin does not throw EPIPE past us", async () => {
  // The classic write-to-a-dead-pipe shape: report the exit code, not EPIPE.
  await assert.rejects(
    () => storeSecret("x", "v".repeat(200_000), { insertCommand: ["/bin/sh", "-c", "exit 7", "--", "{entry}"] }),
    (e: Error) => e instanceof KeyringError && /exit 7/.test(e.message),
  );
});

// --- listSecrets / removeSecret (the `marmalade secret ls|rm` half) -------
//
// Entry NAMES are not secrets — the list is an index, and it is the one
// keyring output that may be printed. Everything else about the posture is
// unchanged: allowlist env, KeyringError on failure, stderr-only diagnostics.

test("listSecrets: one trimmed name per line, blanks dropped", async () => {
  const entries = await listSecrets({
    listCommand: ["/bin/sh", "-c", `printf 'alpha\\n  beta  \\n\\nnested/gamma\\n'`],
  });
  assert.deepEqual(entries, ["alpha", "beta", "nested/gamma"]);
});

test("listSecrets: an empty store is an empty list, not an error", async () => {
  assert.deepEqual(await listSecrets({ listCommand: ["/bin/sh", "-c", "exit 0"] }), []);
});

test("listSecrets: a non-zero exit is a KeyringError carrying the stderr", async () => {
  await assert.rejects(
    () => listSecrets({ listCommand: ["/bin/sh", "-c", `echo "store is locked" >&2; exit 2`] }),
    (e: Error) => e instanceof KeyringError && /keyring list failed \(exit 2\): store is locked/.test(e.message),
  );
});

test("listSecrets: passphrase file + allowlist env apply to the list path too", async () => {
  await withFile("hunter2-age\n", async (path) => {
    process.env.MARMALADE_KEYRING_LEAK_CANARY = "should-not-be-visible";
    try {
      assert.deepEqual(
        await listSecrets({
          listCommand: ["/bin/sh", "-c", `printf '[%s][%s]\\n' "$GOPASS_AGE_PASSWORD" "$MARMALADE_KEYRING_LEAK_CANARY"`],
          passphraseFile: path,
        }),
        ["[hunter2-age][]"],
      );
    } finally { delete process.env.MARMALADE_KEYRING_LEAK_CANARY; }
  });
});

test("removeSecret: {entry} reaches argv as data; a failure is a KeyringError", async () => {
  const out = tmpPrefix();
  try {
    await removeSecret("nested/doomed; rm -rf /", {
      removeCommand: ["/bin/sh", "-c", `printf '%s' "$1" > "${out}.argv"`, "--", "{entry}"],
    });
    assert.equal(readFileSync(`${out}.argv`, "utf8"), "nested/doomed; rm -rf /",
      "the entry is ONE argv element — there is no shell to re-split it");
  } finally { rmSync(`${out}.argv`, { force: true }); }

  await assert.rejects(
    () => removeSecret("absent", {
      removeCommand: ["/bin/sh", "-c", `echo "entry not found" >&2; exit 1`, "--", "{entry}"],
    }),
    (e: Error) => e instanceof KeyringError
      && /keyring remove of "absent" failed \(exit 1\): entry not found/.test(e.message),
  );
});

test("removeSecret: a backend that prints the deleted value does not buffer it here", async () => {
  // stdout is ignored on the delete path, so a chatty `rm` can't put a secret
  // on this process's heap or into an error message.
  await assert.rejects(
    () => removeSecret("x", {
      removeCommand: ["/bin/sh", "-c", `printf 'sk-ant-LEAKED'; echo "boom" >&2; exit 5`, "--", "{entry}"],
    }),
    (e: Error) => e instanceof KeyringError && !/LEAKED/.test(e.message) && /boom/.test(e.message),
  );
});

// --- config-file surface -------------------------------------------------

function withConfig(content: unknown, fn: (path: string) => void): void {
  const path = join(tmpdir(), `mcfg-${randomUUID()}.json`);
  writeFileSync(path, JSON.stringify(content));
  try { fn(path); } finally { rmSync(path, { force: true }); }
}

test("keyring config block parses and maps snake_case → camelCase", () => {
  withConfig({
    keyring: {
      command: ["bw", "get", "password", "{entry}"],
      insert_command: ["pass", "insert", "-f", "-m", "{entry}"],
      list_command: ["bw", "list", "items"],
      remove_command: ["pass", "rm", "-f", "{entry}"],
      passphrase_file: "/home/user/.marmalade/age-pass",
      metered_key_entry: "anthropic/metered",
    },
  }, (path) => {
    const cfg = defaultConfig(loadConfigFile(path));
    assert.deepEqual(cfg.keyring, {
      command: ["bw", "get", "password", "{entry}"],
      insertCommand: ["pass", "insert", "-f", "-m", "{entry}"],
      listCommand: ["bw", "list", "items"],
      removeCommand: ["pass", "rm", "-f", "{entry}"],
      passphraseFile: "/home/user/.marmalade/age-pass",
      meteredKeyEntry: "anthropic/metered",
    });
  });
  // list/remove are independently optional — a config that only reads still
  // parses, and the CLI's ls/rm fall back to the gopass defaults.
  withConfig({ keyring: { command: ["op", "read", "{entry}"] } }, (path) => {
    const kr = defaultConfig(loadConfigFile(path)).keyring!;
    assert.equal(kr.listCommand, undefined);
    assert.equal(kr.removeCommand, undefined);
  });
  // insert_command is independently optional: a read-only keyring config
  // still parses and simply falls back to the gopass insert default.
  withConfig({ keyring: { command: ["op", "read", "{entry}"] } }, (path) => {
    assert.equal(defaultConfig(loadConfigFile(path)).keyring!.insertCommand, undefined);
  });
  // Absent = no keyring at all, not an empty object to be defensive about.
  assert.equal(defaultConfig({}).keyring, undefined);
});

test("keyring block is strict: a typo'd key fails startup", () => {
  withConfig({ keyring: { metered_key_entrie: "x" } }, (path) => {
    assert.throws(() => loadConfigFile(path), /invalid|Unrecognized/i);
  });
  withConfig({ keyring: { command: [] } }, (path) => {
    assert.throws(() => loadConfigFile(path), /keyring\.command/);
  });
  withConfig({ keyring: { passphrase_file: "" } }, (path) => {
    assert.throws(() => loadConfigFile(path), /keyring\.passphrase_file/);
  });
  withConfig({ keyring: { insert_command: [] } }, (path) => {
    assert.throws(() => loadConfigFile(path), /keyring\.insert_command/);
  });
  withConfig({ keyring: { insert_command: ["gopass", ""] } }, (path) => {
    assert.throws(() => loadConfigFile(path), /keyring\.insert_command/);
  });
  withConfig({ keyring: { list_command: [] } }, (path) => {
    assert.throws(() => loadConfigFile(path), /keyring\.list_command/);
  });
  withConfig({ keyring: { remove_command: ["gopass", ""] } }, (path) => {
    assert.throws(() => loadConfigFile(path), /keyring\.remove_command/);
  });
});
