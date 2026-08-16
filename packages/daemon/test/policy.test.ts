import { test } from "node:test";
import assert from "node:assert/strict";
import { homedir } from "node:os";
import {
  createSessionSpec,
  resolveAuthContext,
  buildChildEnv,
  assertNoSubscriptionLeak,
  PolicyError,
} from "../dist/policy.js";
import { defaultConfig } from "../dist/config.js";

const cfg = defaultConfig();

test("factory refuses guest execution in v0.1 (sec-H2)", () => {
  assert.throws(
    () => createSessionSpec({ principal: "guest", purpose: "voice", origin: "voice" }, cfg),
    PolicyError,
  );
});

test("max/main → subscription authClass on the real ~/.claude context", () => {
  const spec = createSessionSpec({ principal: "owner", purpose: "main", origin: "text" }, cfg);
  assert.equal(spec.authClass, "subscription");
  assert.equal(spec.authContext.home, homedir());
  assert.ok(spec.authContext.claudeConfigDir.endsWith(".claude"));
});

test("metered/local contexts use a dedicated HOME, never the real one (sec-H1)", () => {
  const metered = resolveAuthContext("metered", cfg);
  const local = resolveAuthContext("local", cfg);
  assert.notEqual(metered.home, homedir());
  assert.notEqual(local.home, homedir());
  assert.ok(metered.home.startsWith(cfg.authContextRoot));
});

test("child env is an allowlist — no oauth token names, ever (pattern-B guard)", () => {
  const spec = createSessionSpec({ principal: "owner", purpose: "coding", origin: "cli" }, cfg);
  const env = buildChildEnv(spec, { path: "/usr/bin" });
  assert.ok(!("CLAUDE_CODE_OAUTH_TOKEN" in env));
  assert.ok(!("ANTHROPIC_AUTH_TOKEN" in env));
  assert.equal(env.HOME, homedir()); // subscription context = real home (intended)
  assert.equal(env.PATH, "/usr/bin");
});

test("assertNoSubscriptionLeak rejects a smuggled sk-ant-oat token value", () => {
  assert.throws(
    () => assertNoSubscriptionLeak({ HOME: "/tmp/x", SOMETHING: "sk-ant-oat-x1" }, "metered"),
    /sk-ant-oat/,
  );
});

test("assertNoSubscriptionLeak rejects a non-subscription context pointed at the real home (sec-H1)", () => {
  assert.throws(
    () => assertNoSubscriptionLeak({ HOME: homedir() }, "metered"),
    /dedicated HOME/,
  );
});

test("metered spec requires a keyring key, never an inherited env var (5.3)", () => {
  // Force a metered context via resolveAuthContext (no v0.1 purpose maps to it,
  // by design — this exercises the enforcement directly).
  const spec = {
    principal: "owner" as const,
    purpose: "cadence" as const,
    authClass: "metered" as const,
    origin: "cadence" as const,
    cwd: "/tmp",
    authContext: resolveAuthContext("metered", cfg),
  };
  assert.throws(() => buildChildEnv(spec, { path: "/usr/bin" }), /metered key/);
  const env = buildChildEnv(spec, { path: "/usr/bin", meteredKey: "sk-ant-api-legit" });
  assert.equal(env.ANTHROPIC_API_KEY, "sk-ant-api-legit");
});
