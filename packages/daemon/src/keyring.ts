// keyring.ts — the secret broker (policy 5.3's "from the keyring" half).
//
// marmalade never STORES a credential. It shells out to a secret manager the
// user already trusts, reads the value off stdout, and hands it to exactly the
// consumer that needs it (today: the metered ANTHROPIC_API_KEY threaded into
// buildChildEnv; next: IMAP/CalDAV for the assistant services). Nothing is
// cached on disk, nothing is written to the daemon's own state.
//
// The fetch is a CONFIGURABLE COMMAND (product decision) rather than a gopass
// integration: gopass with the age backend is the default, but `bw get`,
// `op read`, `pass show` — anything that prints a secret to stdout and exits 0
// — works by pointing `keyring.command` at it. One code path, no per-backend
// adapters to keep alive. The same shape covers the whole verb set the
// `marmalade secret` CLI drives: read (command), write (insertCommand), list
// (listCommand), delete (removeCommand).
//
// SECURITY (the same posture as policy 5.5 — allowlist, not denylist):
//   * spawn with an argv ARRAY, never a shell: a `{entry}` value can't become
//     shell syntax no matter what it contains.
//   * The child env is built FROM EMPTY: PATH + HOME (gopass needs both to
//     find its binary path and its store) and, when configured,
//     GOPASS_AGE_PASSWORD so the age backend runs non-interactively. The
//     daemon's own environment — including any subscription material — is
//     never visible to the secret manager.
//   * stdout is the SECRET. It never appears in an error message or a log
//     line, not even truncated, not even on the failure paths where it is
//     probably a partial value. Diagnostics come from stderr only.
//   * Empty stdout on a zero exit is a FAILURE, not an empty credential —
//     a blank API key would otherwise propagate into a child env and fail
//     somewhere far away from the cause.

import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";

export interface KeyringConfig {
  /** argv with a {entry} placeholder; default ["gopass", "show", "-o", "{entry}"] */
  command?: string[];
  /** argv with a {entry} placeholder for WRITING a secret; default
   *  ["gopass", "insert", "-f", "{entry}"]. The value is fed on the child's
   *  STDIN — never as an argument (see storeSecret). */
  insertCommand?: string[];
  /** argv that prints one entry NAME per line; default ["gopass", "ls",
   *  "--flat"]. No {entry} placeholder is meaningful here (substitution still
   *  happens, with an empty entry) — this is the whole-store listing that
   *  `marmalade secret ls` and `secret check` use. */
  listCommand?: string[];
  /** argv with a {entry} placeholder for DELETING an entry; default
   *  ["gopass", "rm", "-f", "{entry}"]. */
  removeCommand?: string[];
  /** Optional path to a mode-600 file whose contents become GOPASS_AGE_PASSWORD
   *  in the child env, so gopass's age backend runs non-interactively. Only
   *  meaningful for the gopass default; other backends ignore it. */
  passphraseFile?: string;
  /** Keyring entry holding the metered ANTHROPIC_API_KEY (policy 5.3). Absent
   *  = no metered key available. */
  meteredKeyEntry?: string;
}

export class KeyringError extends Error {}

/** gopass with `-o` prints ONLY the secret line (no key/value preamble), which
 *  is the shape every other backend's read command also has. */
export const DEFAULT_KEYRING_COMMAND = ["gopass", "show", "-o", "{entry}"];

/** `gopass insert -f <entry>` overwrites without prompting and, with a
 *  non-tty stdin, takes the secret from stdin — which is exactly the shape
 *  storeSecret needs (`pass insert -f`, `op item edit`, `bw` via a wrapper all
 *  read stdin the same way). */
export const DEFAULT_KEYRING_INSERT_COMMAND = ["gopass", "insert", "-f", "{entry}"];

/** `gopass ls --flat` prints one full entry path per line and nothing else —
 *  the shape `pass ls` (tree) is NOT, which is why this is configurable. */
export const DEFAULT_KEYRING_LIST_COMMAND = ["gopass", "ls", "--flat"];

/** `gopass rm -f <entry>` deletes without a confirmation prompt (a prompt
 *  would hang against a closed stdin and hit the timeout below). */
export const DEFAULT_KEYRING_REMOVE_COMMAND = ["gopass", "rm", "-f", "{entry}"];

/** A secret manager that prompts (a locked store, a missing passphrase file)
 *  would otherwise hang the daemon forever — stdin is closed and this bound
 *  turns "hung" into a visible error. */
const FETCH_TIMEOUT_MS = 10_000;
/** Enough stderr to diagnose ("entry is not in the password store"), not
 *  enough for a chatty backend to flood the log. */
const MAX_STDERR_CHARS = 400;

/** The child env, built FROM EMPTY (never `...process.env`): PATH + HOME so a
 *  secret manager can find its binary and its store, plus GOPASS_AGE_PASSWORD
 *  when configured. Read the passphrase BEFORE spawning — a
 *  configured-but-unreadable file is a misconfiguration, and failing here beats
 *  a gopass that hangs on a prompt. Shared by every verb: the write, list and
 *  delete paths have exactly the same trust boundary as the read path. */
function buildChildEnv(cfg: KeyringConfig): Record<string, string> {
  const env: Record<string, string> = {
    PATH: process.env.PATH ?? "",
    HOME: process.env.HOME ?? "",
  };
  if (cfg.passphraseFile) {
    try {
      env.GOPASS_AGE_PASSWORD = readFileSync(cfg.passphraseFile, "utf8").trim();
    } catch (e) {
      throw new KeyringError(
        `keyring passphrase file ${cfg.passphraseFile} unreadable: ${(e as Error).message}`,
      );
    }
  }
  return env;
}

interface RunOptions {
  /** Fully substituted argv (no {entry} placeholders left). */
  argv: string[];
  env: Record<string, string>;
  /** Noun in the start-failure message: "command", "insert command", … */
  commandNoun: string;
  /** What this run is, for the timeout/exit messages: `fetch of "x"`, `list`. */
  what: string;
  /** Tail of the start-failure message, e.g. ` for "entry"` (empty for list). */
  subject: string;
  /** Fed to the child's stdin, then EOF. undefined = stdin is closed. */
  input?: string;
  /** Capture stdout (read/list paths). false = the child's stdout is ignored,
   *  so a chatty write backend can't buffer a secret in this process. */
  captureStdout: boolean;
  /** Scrub material out of stderr before it can reach an error message.
   *
   *  INVARIANT — only storeSecret can supply this, and that is not an
   *  oversight. Redaction needs the value to scrub for, and the store path is
   *  the ONLY one that holds it: the caller hands the credential in. On the
   *  fetch, list and remove paths the value is either not known to this
   *  process (list, remove) or arrives on the child's stdout AFTER stderr has
   *  already been captured (fetch), so there is nothing to redact against and
   *  their stderr is inherently unredactable. The mitigation lives elsewhere:
   *  stderr is bounded (MAX_STDERR_CHARS) and a backend that echoes a fetched
   *  secret into its own diagnostics is a broken backend, not a case this
   *  layer can defend against. */
  redact?: (text: string) => string;
}

/** Spawn one keyring child under the shared discipline — argv array (never a
 *  shell), allowlist env, bounded wait, KeyringError on every failure path,
 *  diagnostics from stderr only. Resolves the captured stdout ("" when
 *  captureStdout is false). */
function runKeyring(o: RunOptions): Promise<string> {
  const { argv, env } = o;
  return new Promise<string>((resolve, reject) => {
    const child = spawn(argv[0], argv.slice(1), {
      env,
      stdio: [o.input === undefined ? "ignore" : "pipe", o.captureStdout ? "pipe" : "ignore", "pipe"],
      windowsHide: true,
    });
    let stdout = "";
    let stderr = "";
    let timedOut = false;
    if (o.captureStdout && child.stdout) {
      child.stdout.setEncoding("utf8");
      child.stdout.on("data", (chunk: string) => { stdout += chunk; });
    }
    child.stderr!.setEncoding("utf8");
    child.stderr!.on("data", (chunk: string) => {
      if (stderr.length < MAX_STDERR_CHARS * 4) stderr += chunk;
    });

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, FETCH_TIMEOUT_MS);

    child.on("error", (e) => {
      clearTimeout(timer);
      reject(new KeyringError(
        `keyring ${o.commandNoun} ${argv[0]} failed to start${o.subject}: ${e.message}`,
      ));
    });

    if (o.input !== undefined) {
      // A child that exits before reading stdin makes the write EPIPE. That is
      // not the error worth reporting — the close handler below has the exit
      // code and the stderr, which is what actually says why.
      child.stdin!.on("error", () => {});
      child.stdin!.end(o.input);
    }

    child.on("close", (code) => {
      clearTimeout(timer);
      if (timedOut) {
        reject(new KeyringError(
          `keyring ${o.what} timed out after ${FETCH_TIMEOUT_MS / 1000}s (is the store locked?)`,
        ));
        return;
      }
      if (code !== 0) {
        const cleaned = o.redact ? o.redact(stderr.trim()) : stderr.trim();
        const tail = cleaned.slice(0, MAX_STDERR_CHARS);
        reject(new KeyringError(
          `keyring ${o.what} failed (exit ${code})${tail ? `: ${tail}` : ""}`,
        ));
        return;
      }
      resolve(stdout);
    });
  });
}

/** Substitute {entry} in every argv slot. The value lands as ONE argv element
 *  no matter what it contains — there is no shell to re-split it. */
function substitute(argv: string[], entry: string): string[] {
  return argv.map((a) => a.replaceAll("{entry}", entry));
}

/**
 * Fetch one secret. Resolves to the raw secret with ONLY trailing newlines
 * stripped — secrets may legitimately contain leading or inner whitespace, and
 * `gopass show -o` appends exactly one newline. Throws KeyringError on every
 * failure path; the message never contains secret material.
 */
export async function fetchSecret(entry: string, cfg: KeyringConfig = {}): Promise<string> {
  const argv = substitute(cfg.command ?? DEFAULT_KEYRING_COMMAND, entry);
  const stdout = await runKeyring({
    argv,
    env: buildChildEnv(cfg),
    commandNoun: "command",
    what: `fetch of "${entry}"`,
    subject: ` for "${entry}"`,
    captureStdout: true,
  });
  const secret = stdout.replace(/\n+$/, "");
  if (secret === "") throw new KeyringError(`keyring returned an empty secret for "${entry}"`);
  return secret;
}

/**
 * Write one secret into the keyring (the secret-entry flow: the user types a
 * credential in a client and it goes straight to the store, never through the
 * agent's context).
 *
 * Same posture as fetchSecret — argv array, no shell, allowlist env from
 * empty, bounded wait, KeyringError on every failure — plus the invariant that
 * makes this path different:
 *
 *   THE VALUE TRAVELS ON STDIN, NEVER IN ARGV OR ENV. `/proc/<pid>/cmdline`
 *   and `/proc/<pid>/environ` are readable by the user's other processes and
 *   argv shows up in `ps` for anyone on the box; a pipe does not. stdin is
 *   closed right after the write so a backend that reads to EOF (gopass does)
 *   isn't left waiting.
 *
 * The value is written RAW with no trailing newline: fetchSecret strips
 * trailing newlines, so writing none is the only shape that round-trips a
 * secret whose last character is itself a newline.
 *
 * Diagnostics come from stderr, redacted against the value before they can
 * reach an error message — a chatty backend that echoes what it was given must
 * not turn the audit trail into a leak.
 */
export async function storeSecret(entry: string, value: string, cfg: KeyringConfig = {}): Promise<void> {
  await runKeyring({
    argv: substitute(cfg.insertCommand ?? DEFAULT_KEYRING_INSERT_COMMAND, entry),
    env: buildChildEnv(cfg),
    commandNoun: "insert command",
    what: `insert of "${entry}"`,
    subject: ` for "${entry}"`,
    input: value,
    captureStdout: false,
    redact: (text) => (value === "" ? text : text.split(value).join("[redacted]")),
  });
}

/**
 * List the entry NAMES in the store (`marmalade secret ls`, and the liveness
 * probe in `secret check`). Unlike every other verb here, stdout is NOT a
 * secret — it is the index — so it may be printed. Blank lines are dropped and
 * names are trimmed; an EMPTY store is a legitimate empty list, not an error
 * (that distinction is the caller's to make).
 */
export async function listSecrets(cfg: KeyringConfig = {}): Promise<string[]> {
  // {entry} has no meaning for a whole-store listing; substituting it away
  // keeps a stray placeholder from reaching the backend as a literal.
  const stdout = await runKeyring({
    argv: substitute(cfg.listCommand ?? DEFAULT_KEYRING_LIST_COMMAND, ""),
    env: buildChildEnv(cfg),
    commandNoun: "list command",
    what: "list",
    subject: "",
    captureStdout: true,
  });
  return stdout.split("\n").map((l) => l.trim()).filter((l) => l !== "");
}

/**
 * Delete one entry. stdout is ignored (a backend that echoes the deleted value
 * on the way out must not buffer it here); diagnostics come from stderr.
 * A backend that treats "no such entry" as an error surfaces it as a
 * KeyringError — this function does not invent an idempotent success.
 */
export async function removeSecret(entry: string, cfg: KeyringConfig = {}): Promise<void> {
  await runKeyring({
    argv: substitute(cfg.removeCommand ?? DEFAULT_KEYRING_REMOVE_COMMAND, entry),
    env: buildChildEnv(cfg),
    commandNoun: "remove command",
    what: `remove of "${entry}"`,
    subject: ` for "${entry}"`,
    captureStdout: false,
  });
}
