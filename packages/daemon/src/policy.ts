// policy.ts — the session factory + structural security invariants (Decision 5).
//
// This is a FILE, not an "engine" (simp-M1): a factory function + a profile
// table + the enforcement helpers every spawn routes through. It is the
// single choke point where the day-one invariants are enforced structurally,
// because a record-field check is not enforcement (the round-2 meta-finding).
//
// Enforced here:
//   5.1  authClass selects a REAL auth context (per-authClass CLAUDE_CONFIG_DIR
//        /HOME), because the official binary discovers auth from the filesystem.
//   5.2  No guest execution in v0.1 (the factory refuses principal=guest).
//   5.3  No subscription/OAuth credential is fronted; metered keys come from
//        the keyring into a dedicated config dir, never an inherited env var.
//   5.5  Child env is an ALLOWLIST constructed from empty, not process.env
//        minus a denylist; a positive-shape assertion runs on every spawn.

import { homedir } from "node:os";
import { join } from "node:path";
import type { DaemonConfig } from "./config.js";

export type Principal = "owner" | "guest";
export type Purpose = "main" | "coding" | "cadence" | "voice" | "skill-improve";
export type AuthClass = "subscription" | "metered" | "local";

export interface SessionRequest {
  principal: Principal;
  purpose: Purpose;
  origin: "text" | "voice" | "cadence" | "cli";
  cwd?: string;
}

export interface SessionSpec {
  principal: Principal;
  purpose: Purpose;
  authClass: AuthClass;
  origin: SessionRequest["origin"];
  cwd: string;
  /** The dedicated auth context this session's harness child must run in. */
  authContext: AuthContext;
}

export interface AuthContext {
  /** HOME for the child. subscription = the user's real home (so the official
   *  binary finds ~/.claude); metered/local = a scratch home with no OAuth. */
  home: string;
  /** CLAUDE_CONFIG_DIR for the child — where Claude Code reads its config. */
  claudeConfigDir: string;
  authClass: AuthClass;
}

export class PolicyError extends Error {}

/** Purposes and their authClass in v0.1. All are principal=owner, so all ride
 *  the subscription via the sanctioned SDK path (pattern A2). `meteredFallback`
 *  is applied at the ambient layer by config, not here. */
const V01_PURPOSE_AUTHCLASS: Record<Purpose, AuthClass> = {
  main: "subscription",
  coding: "subscription",
  cadence: "subscription",
  voice: "subscription", // v0.1 voice is owner-only; shared voice is post-v0.1
  "skill-improve": "subscription",
};

/**
 * The session factory. The ONLY sanctioned way to turn a request into a
 * spawnable spec — every enforcement point lives here.
 */
export function createSessionSpec(req: SessionRequest, cfg: DaemonConfig): SessionSpec {
  // 5.2 — no guest execution in v0.1. The guest lane is closed until OS-level
  // confinement exists; the failure mode is "no answer", never "leak".
  if (req.principal === "guest") {
    throw new PolicyError(
      "guest execution is not available in v0.1 (requires OS-level confinement — Decision 5.2)",
    );
  }

  const authClass = V01_PURPOSE_AUTHCLASS[req.purpose];

  // 5.1 belt-and-braces — never a guest/shared surface on a subscription.
  if (req.principal !== "owner" && authClass === "subscription") {
    throw new PolicyError("non-owner principal may never use authClass=subscription (Decision 5.1)");
  }

  const authContext = resolveAuthContext(authClass, cfg);

  return {
    principal: req.principal,
    purpose: req.purpose,
    authClass,
    origin: req.origin,
    cwd: req.cwd ?? homedir(),
    authContext,
  };
}

/**
 * 5.1 — map an authClass to a dedicated filesystem auth context. This is the
 * structural fix for sec-H1: a "metered" child must not be able to reach the
 * subscription OAuth in ~/.claude just because it shares the user's HOME.
 */
export function resolveAuthContext(authClass: AuthClass, cfg: DaemonConfig): AuthContext {
  if (authClass === "subscription") {
    // The subscription context IS the user's real config — the official binary
    // finds ~/.claude and does its own auth (pattern A2). marmalade never reads
    // the token.
    const home = homedir();
    return { home, claudeConfigDir: join(home, ".claude"), authClass };
  }
  // metered / local: a dedicated scratch context with NO subscription OAuth.
  const home = join(cfg.authContextRoot, authClass);
  return { home, claudeConfigDir: join(home, ".claude"), authClass };
}

const OAUTH_TOKEN_PATTERN = /sk-ant-oat/;
const FORBIDDEN_ENV_NAMES = ["CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_AUTH_TOKEN"];

/**
 * 5.5 — construct the child env as an ALLOWLIST from empty. Never process.env
 * minus a denylist (a strip-list only catches names you predicted). Only what
 * the session needs is included.
 *
 * @param meteredKey  the metered API key from the OS keyring, injected ONLY
 *                    for authClass=metered, into a dedicated context (5.3).
 */
export function buildChildEnv(
  spec: SessionSpec,
  opts: { path: string; meteredKey?: string } = { path: "" },
): Record<string, string> {
  const env: Record<string, string> = {
    PATH: opts.path,
    HOME: spec.authContext.home,
    CLAUDE_CONFIG_DIR: spec.authContext.claudeConfigDir,
  };

  if (spec.authClass === "metered") {
    if (!opts.meteredKey) {
      throw new PolicyError("authClass=metered requires a metered key from the keyring (5.3)");
    }
    env.ANTHROPIC_API_KEY = opts.meteredKey;
  }

  assertNoSubscriptionLeak(env, spec.authClass);
  return env;
}

/**
 * The positive-shape assertion (5.5) — run on every constructed env. Throws if
 * any subscription-OAuth material is reachable via env in a non-subscription
 * context, or if a forbidden token name is present anywhere (pattern-B guard,
 * ported from the shim's sk-ant-oat safeguard).
 */
export function assertNoSubscriptionLeak(env: Record<string, string>, authClass: AuthClass): void {
  for (const name of FORBIDDEN_ENV_NAMES) {
    if (name in env) {
      throw new PolicyError(`forbidden env var ${name} present in child env (pattern-B guard, 5.5)`);
    }
  }
  for (const [name, value] of Object.entries(env)) {
    if (typeof value === "string" && OAUTH_TOKEN_PATTERN.test(value)) {
      throw new PolicyError(`subscription OAuth token (sk-ant-oat*) found in env var ${name} (5.5)`);
    }
  }
  // A metered/local context must additionally not point HOME at the user's real
  // home, or the binary would discover ~/.claude's subscription OAuth (sec-H1).
  if (authClass !== "subscription" && env.HOME === homedir()) {
    throw new PolicyError(
      `authClass=${authClass} must use a dedicated HOME, not the user's real home (sec-H1)`,
    );
  }
}
