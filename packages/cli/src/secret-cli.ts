// secret-cli.ts — `marmalade secret …` : read, write, list, delete and
// health-check the entries in the keyring the daemon brokers through
// (packages/daemon/src/keyring.ts).
//
// WHY THIS DOES NOT GO THROUGH THE DAEMON (the one design decision worth
// stating up front): every other subcommand here is a thin WS RPC, but the
// secret verbs invoke the keyring IN THIS PROCESS, off the same config file
// the daemon reads. Two reasons, both load-bearing:
//
//   1. It works when marmaladed is down — which is exactly when you are most
//      likely to be fixing a credential.
//   2. A secret value never crosses a socket. `secret get` spawns the store,
//      reads stdout, prints it; `secret set` pipes the value straight into the
//      backend's stdin. There is no frame, no log line, no daemon memory in
//      between.
//
// The keyring module keeps its own discipline (argv arrays, allowlist env,
// stderr-only diagnostics redacted of the value). This layer adds one rule of
// its own: a secret value goes to STDOUT and nowhere else — every prompt,
// status line and error goes to stderr, so `marmalade secret get x | …` is
// always exactly the credential.

import { createInterface } from "node:readline";
import { access, constants } from "node:fs/promises";
import {
  fetchSecret, storeSecret, listSecrets, removeSecret, KeyringError,
  DEFAULT_KEYRING_COMMAND, DEFAULT_KEYRING_INSERT_COMMAND,
  DEFAULT_KEYRING_LIST_COMMAND, DEFAULT_KEYRING_REMOVE_COMMAND,
  type KeyringConfig,
} from "@marmalade/daemon/keyring";
import { defaultConfig, loadConfigFile, defaultConfigPath } from "@marmalade/daemon/config";

export const SECRET_USAGE = `usage: marmalade secret <command>

  get <entry>        print the secret to stdout (nothing else goes to stdout)
  set <entry>        store a secret: piped stdin, or typed twice   [--json]
  ls                 list entry names                              [--json]
  rm <entry>         delete an entry                               [--json]
  check              validate the keyring configuration            [--json]

--json may appear anywhere in the arguments. It is NOT accepted on get: that
verb's stdout is always exactly the raw secret value, so there is no room for
an envelope — a failing get reports on stderr and exits 1.

The keyring is the one configured for the daemon (config file, "keyring"
block); these verbs run it directly, so they work while marmaladed is down.`;

export interface SecretCliDeps {
  /** The daemon's resolved keyring config; undefined = no "keyring" block. */
  keyring?: KeyringConfig;
  /** Config file path — named in the "not configured" error so the fix is obvious. */
  configPath: string;
  /** stdout, written RAW (this is where a secret value goes). */
  out: (text: string) => void;
  /** stderr, written RAW (prompts, status, errors — never a secret value). */
  err: (text: string) => void;
  /** The piped stdin value, or null when stdin is a TTY (prompt instead). */
  readStdin: () => Promise<string | null>;
  /** Read one line with echo off. Only called when stdin is a TTY. */
  promptSecret: (label: string) => Promise<string>;
}

/** One `check` line: what was probed, whether it passed, and the human detail. */
export interface SecretCheck {
  name: string;
  ok: boolean;
  detail: string;
}

/** Trailing newlines are stripped from a piped value, because fetchSecret
 *  strips them on the way back out — `printf 'v\\n' | marmalade secret set x`
 *  must round-trip to "v", not "v\\n". A secret whose real last character is a
 *  newline can't survive a pipe either way; type it at the prompt. */
function normalizePiped(value: string): string {
  return value.replace(/\n+$/, "");
}

/** `secret` takes exactly one flag — `--json`, boolean — so it parses its own
 *  argv instead of borrowing cron-cli's parseFlags, which treats `--json` as
 *  value-taking and would swallow the entry name in `secret rm --json api/key`.
 *  Here flag POSITION never matters, and an unrecognised `--*` token is a usage
 *  error rather than silently becoming an entry name (a typo must not resolve
 *  to some other credential). */
function parseSecretArgv(argv: string[]): { json: boolean; pos: string[]; badFlag?: string } {
  let json = false;
  const pos: string[] = [];
  for (const a of argv) {
    if (a === "--json") json = true;
    else if (a.startsWith("--")) return { json, pos, badFlag: a };
    else pos.push(a);
  }
  return { json, pos };
}

export async function secretCommand(argv: string[], deps: SecretCliDeps): Promise<number> {
  const { json, pos, badFlag } = parseSecretArgv(argv);
  const sub = pos[0];
  const cfg = deps.keyring;

  const fail = (message: string): number => {
    if (json) deps.out(`${JSON.stringify({ ok: false, error: message })}\n`);
    else deps.err(`${message}\n`);
    return 1;
  };
  const unconfigured = () =>
    `no keyring configured — add a "keyring" block to ${deps.configPath} (see marmalade secret check)`;

  const requireEntry = (): string => {
    const entry = pos[1];
    if (!entry) throw new Error(`usage: marmalade secret ${sub} <entry>`);
    return entry;
  };

  // `get` has no --json form ON PURPOSE: its stdout is the raw credential and
  // nothing else, so there is nowhere to put an envelope. Emitting one on
  // failure only (the old behaviour) is worse than refusing — a consumer that
  // parses JSON breaks on success, and one that pipes the value gets JSON
  // where the credential should be. Refusing keeps the contract single-valued.
  if (sub === "get" && json) {
    deps.err("marmalade secret get does not support --json:"
      + " its stdout is always exactly the raw secret value."
      + " Errors go to stderr and the exit code is 1.\n");
    return 1;
  }
  if (badFlag !== undefined) {
    return fail(`unknown flag "${badFlag}" — the only flag marmalade secret accepts is --json`);
  }

  try {
    switch (sub) {
      case "get": {
        if (!cfg) return fail(unconfigured());
        const entry = requireEntry();
        // The ONLY thing this verb ever writes to stdout. The newline is
        // shell-friendly and matches what `gopass show -o` does; fetchSecret
        // already stripped the backend's own.
        deps.out(`${await fetchSecret(entry, cfg)}\n`);
        return 0;
      }

      case "set": {
        if (!cfg) return fail(unconfigured());
        const entry = requireEntry();
        const piped = await deps.readStdin();
        let value: string;
        if (piped !== null) {
          value = normalizePiped(piped);
        } else {
          value = await deps.promptSecret(`value for ${entry}: `);
          const again = await deps.promptSecret("confirm: ");
          if (value !== again) return fail("the two entries did not match — nothing was stored");
        }
        // An empty credential is the failure keyring.ts refuses to hand back;
        // refusing to WRITE one keeps the two halves consistent.
        if (value === "") return fail(`refusing to store an empty value in "${entry}"`);
        await storeSecret(entry, value, cfg);
        if (json) deps.out(`${JSON.stringify({ ok: true, entry })}\n`);
        else deps.err(`stored ${entry}\n`);
        return 0;
      }

      case "ls": {
        if (!cfg) return fail(unconfigured());
        const entries = await listSecrets(cfg);
        if (json) deps.out(`${JSON.stringify({ ok: true, entries })}\n`);
        else if (entries.length === 0) deps.err("(the keyring is empty)\n");
        else deps.out(`${entries.join("\n")}\n`);
        return 0;
      }

      case "rm": {
        if (!cfg) return fail(unconfigured());
        const entry = requireEntry();
        await removeSecret(entry, cfg);
        if (json) deps.out(`${JSON.stringify({ ok: true, entry })}\n`);
        else deps.err(`removed ${entry}\n`);
        return 0;
      }

      case "check": {
        const checks = await runChecks(cfg, deps.configPath);
        const ok = checks.every((c) => c.ok);
        if (json) {
          deps.out(`${JSON.stringify({ ok, config_path: deps.configPath, checks })}\n`);
        } else {
          deps.err(`keyring — ${deps.configPath}\n`);
          for (const c of checks) {
            deps.err(`  ${c.ok ? "✔" : "✖"} ${c.name.padEnd(16)} ${c.detail}\n`);
          }
          deps.err(ok ? "ok\n" : "FAILED\n");
        }
        return ok ? 0 : 1;
      }

      default:
        deps.err(`${SECRET_USAGE}\n`);
        return sub === undefined || sub === "help" ? 0 : 1;
    }
  } catch (e) {
    // KeyringError messages are already redacted of secret material by
    // keyring.ts; nothing here re-introduces the value.
    return fail((e as Error).message);
  }
}

/** The `check` probes, in dependency order — a failing one stops the rest,
 *  because "list failed" is noise when the passphrase file is missing. */
async function runChecks(cfg: KeyringConfig | undefined, configPath: string): Promise<SecretCheck[]> {
  if (!cfg) {
    return [{
      name: "configured",
      ok: false,
      detail: `no "keyring" block in ${configPath}`,
    }];
  }
  const bin = (argv: string[]) => argv[0];
  const checks: SecretCheck[] = [{
    name: "configured",
    ok: true,
    detail: `get=${bin(cfg.command ?? DEFAULT_KEYRING_COMMAND)}`
      + ` set=${bin(cfg.insertCommand ?? DEFAULT_KEYRING_INSERT_COMMAND)}`
      + ` ls=${bin(cfg.listCommand ?? DEFAULT_KEYRING_LIST_COMMAND)}`
      + ` rm=${bin(cfg.removeCommand ?? DEFAULT_KEYRING_REMOVE_COMMAND)}`,
  }];

  if (cfg.passphraseFile) {
    try {
      await access(cfg.passphraseFile, constants.R_OK);
      checks.push({ name: "passphrase file", ok: true, detail: `${cfg.passphraseFile} readable` });
    } catch (e) {
      checks.push({
        name: "passphrase file",
        ok: false,
        detail: `${cfg.passphraseFile} unreadable: ${(e as Error).message}`,
      });
      return checks;
    }
  }

  // The liveness probe: a store that lists is a store that unlocked. Entry
  // NAMES are not secrets, but the count is all this prints.
  try {
    const entries = await listSecrets(cfg);
    checks.push({ name: "list", ok: true, detail: `${entries.length} entr${entries.length === 1 ? "y" : "ies"}` });
  } catch (e) {
    checks.push({
      name: "list",
      ok: false,
      detail: e instanceof KeyringError ? e.message : String(e),
    });
    return checks;
  }

  if (cfg.meteredKeyEntry) {
    // Presence only — fetching it here would put a live API key on this
    // process's heap for no reason a health check needs.
    checks.push({ name: "metered key", ok: true, detail: `entry "${cfg.meteredKeyEntry}"` });
  }
  return checks;
}

/** Read all of stdin, or null when stdin is a TTY (nothing is piped in). */
function readStdin(): Promise<string | null> {
  if (process.stdin.isTTY) return Promise.resolve(null);
  return new Promise((resolve, reject) => {
    let data = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (chunk) => { data += chunk; });
    process.stdin.on("end", () => resolve(data));
    process.stdin.on("error", reject);
  });
}

/** Prompt on the TERMINAL with echo off. The prompt itself goes to stderr so
 *  stdout stays reserved for secret values. */
function promptSecret(label: string): Promise<string> {
  return new Promise((resolve) => {
    process.stderr.write(label);
    const rl = createInterface({ input: process.stdin, output: process.stderr, terminal: true });
    // readline echoes each keystroke through _writeToOutput; swallowing it is
    // the standard way to get a no-echo prompt without raw-mode bookkeeping.
    (rl as unknown as { _writeToOutput: (s: string) => void })._writeToOutput = () => {};
    rl.question("", (answer) => {
      rl.close();
      process.stderr.write("\n");
      resolve(answer);
    });
  });
}

/** `marmalade secret …` entry point: load the daemon's config (no gateway
 *  connection — see the header) and run the verb. */
export async function secretMain(argv: string[]): Promise<void> {
  const configPath = defaultConfigPath();
  let keyring: KeyringConfig | undefined;
  try {
    keyring = defaultConfig(loadConfigFile(configPath)).keyring;
  } catch (e) {
    // A malformed config file fails the daemon's startup loudly; it fails
    // here for the same reason, rather than degrading to "no keyring".
    process.stderr.write(`${(e as Error).message}\n`);
    process.exit(1);
  }
  const code = await secretCommand(argv, {
    ...(keyring ? { keyring } : {}),
    configPath,
    out: (t) => process.stdout.write(t),
    err: (t) => process.stderr.write(t),
    readStdin,
    promptSecret,
  });
  process.exit(code);
}
