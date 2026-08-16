// The fixture-corpus contract: every checked-in wire fixture validates against
// the v1 zod schemas, and every schema has at least one fixture.
//
// Both directions matter. "Every fixture validates" catches a fixture that
// drifted from the contract; "every schema has a fixture" catches a NEW method
// or event shipped with nothing pinning its shape — which is how the client
// silently falls behind the host. Anything genuinely unfixturable is named in
// an explicit skip list below, with the reason, rather than being quietly
// absent.
//
// The same corpus is consumed by the Android client's ProtocolFixtureTest
// (apps/android/shared/src/desktopTest/.../ProtocolFixtureTest.kt), so a shape
// change fails on both sides of the wire in the same CI run.

import { test } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import { JsonRpcRequest, JsonRpcResponse, JsonRpcEvent } from "../dist/index.js";
import * as P from "../dist/index.js";
import {
  REQUEST_FIXTURES,
  RESPONSE_FIXTURES,
  EVENT_FIXTURES,
  ERROR_FIXTURES,
  type Fixture,
} from "./fixtures.ts";

// ── request params: method → schema ─────────────────────────────────────────
// MethodParamSchemas is the registry the daemon validates with; `hello` is
// handled at the handshake layer (gateway.ts), so it is added here.

const REQUEST_SCHEMAS: Record<string, z.ZodTypeAny> = {
  ...P.MethodParamSchemas,
  hello: P.HelloParams,
};

// ── results: method → schema ────────────────────────────────────────────────
// Most results have an exported schema in methods.ts. The rest are ack-shaped
// (`{}`) or tiny, and are transcribed here from their single construction site
// in packages/daemon/src/router.ts — the citation is the comment beside each.

/** An ack: the daemon returns a bare `{}` and clients ignore the body. */
const Ack = z.object({}).strict();

const RESULT_SCHEMAS: Record<string, z.ZodTypeAny> = {
  hello: P.HelloResult,
  "session.create": P.SessionCreateResult,
  "prompt.submit": P.PromptSubmitResult,
  // router.ts resumeSession(): `return { session_id: rec.id }` — resume never
  // re-mints, so the id it echoes is the id that was asked for.
  "session.resume": z.object({ session_id: z.string() }).strict(),
  "session.subscribe": P.SessionSubscribeResult,
  "session.unsubscribe": Ack,
  "session.seen": P.SessionSeenResult,
  "session.delete": Ack,
  "session.title": P.SessionTitleResult,
  "session.archive": P.SessionArchiveResult,
  "session.interrupt": Ack,
  "session.steer": P.SessionSteerResult,
  "session.compact": Ack,
  "session.stop": Ack,
  "session.undo": P.SessionUndoResult,
  "session.main": P.SessionMainResult,
  "session.clear": P.SessionClearResult,
  "session.model": P.SessionModelResult,
  "session.effort": P.SessionEffortResult,
  "session.approvals": P.SessionApprovalsResult,
  "session.fork": P.SessionForkResult,
  "model.list": P.ModelListResult,
  "settings.get": P.SettingsResult,
  "settings.update": P.SettingsResult,
  "pairing.start": P.PairingStartResult,
  "pairing.claim": P.PairingClaimResult,
  "device.list": P.DeviceListResult,
  "device.revoke": P.DeviceRevokeResult,
  "skills.list": P.SkillsListResult,
  "skills.toggle": P.SkillsToggleResult,
  "fs.defaults": P.FsDefaultsResult,
  "fs.list": P.FsListResult,
  "workspace.create": P.WorkspaceCreateResult,
  "workspace.list": P.WorkspaceListResult,
  "workspace.update": P.WorkspaceUpdateResult,
  "workspace.delete": P.WorkspaceDeleteResult,
  "workspace.context": P.WorkspaceContextResult,
  "mcp.list": P.McpListResult,
  "mcp.toggle": P.McpToggleResult,
  "plugins.list": P.PluginsListResult,
  "plugins.toggle": P.PluginsToggleResult,
  "approval.respond": P.ApprovalRespondResult,
  "clarify.respond": P.ClarifyRespondResult,
  "secret.respond": P.SecretRespondResult,
  "cron.create": P.CronCreateResult,
  "cron.list": P.CronListResult,
  "cron.update": P.CronUpdateResult,
  "cron.delete": P.CronDeleteResult,
  "cron.run_now": P.CronRunNowResult,
  "image.attach_bytes": P.ImageAttachResult,
  "file.attach": P.FileAttachResult,
  "image.detach": P.ImageDetachResult,
  "audio.transcribe": P.AudioTranscribeResult,
  "usage.summary": P.UsageSummaryResult,
  "search.messages": P.SearchMessagesResult,
  "search.archive": P.SearchArchiveResult,
  "terminal.create": P.TerminalCreateResult,
  "terminal.attach": P.TerminalAttachResult,
  "terminal.detach": Ack,
  "terminal.input": Ack,
  // router.ts terminal.resize: `return t.resize(cols, rows)` → { cols, rows }.
  "terminal.resize": z.object({ cols: z.number().int(), rows: z.number().int() }).strict(),
  "terminal.close": P.TerminalCloseResult,
  "terminal.list": P.TerminalListResult,
};

// ── event payloads: event → schema ──────────────────────────────────────────
// events.ts schematizes the payloads the DAEMON itself constructs. Everything
// else is deliberately payload-open on the wire (frames.ts: "we validate the
// ENVELOPE, not each payload") — those fixtures are envelope-checked here and
// field-checked on the client side, where the consumption contract lives.
//
// The stamping seam (identity.ts) adds seq/ts (and message_id/origin) to every
// session-scoped payload, so the schemas are matched non-strictly.

const EVENT_PAYLOAD_SCHEMAS: Record<string, z.ZodTypeAny> = {
  "status.update": P.StatusUpdatePayload,
  error: P.ErrorPayload,
  "session.compaction": P.SessionCompactionPayload,
  "effort.clamped": P.EffortClampedPayload,
  "session.undone": P.SessionUndonePayload,
  "session.cleared": P.SessionClearedPayload,
  "session.deleted": P.SessionDeletedPayload,
  "secret.request": P.SecretRequestPayload,
  "secret.resolved": P.SecretResolvedPayload,
  "terminal.data": P.TerminalDataPayload,
  "terminal.exit": P.TerminalExitPayload,
};

// ── skip lists ──────────────────────────────────────────────────────────────

/**
 * Methods the daemon ROUTES but the protocol package does not schematize.
 * Their fixtures are envelope-validated only. Listed rather than silently
 * skipped, and each entry says what it would take to remove it.
 *
 * Both are real client surfaces (session.list drives every session drawer), so
 * their fixtures still exist and are still round-tripped by the Kotlin side —
 * this list only records that the TS side cannot type-check them yet.
 */
const UNSCHEMATIZED = new Map<string, string>([
  [
    "session.list",
    "router.ts case \"session.list\" builds the row inline; there is no " +
      "SessionListParams/Result in methods.ts. Adding them means transcribing " +
      "~20 fields and keeping them in step with the router by hand — do it " +
      "when the router is changed to return a schema-parsed value.",
  ],
  [
    "session.summary",
    "SessionSummaryParams exists but no SessionSummaryResult; the router " +
      "returns the rollup columns inline (topic/summary/summary_updated_at/" +
      "lifecycle/run_state).",
  ],
]);

/**
 * KnownGatewayEventName members with NO emission site in packages/daemon/src —
 * fork-era vocabulary kept in the enum for wire compatibility. Inventing a
 * payload for them would pin a shape nothing produces, so they have no
 * fixture. Delete the entry (and add a fixture) the day a daemon adapter emits
 * one.
 */
const EVENTS_NOT_EMITTED = new Map<string, string>([
  ["tool.generating", "no emission site; the client treats it as a tool.start alias"],
  ["sudo.request", "fork-era privilege prompt; the daemon has no sudo bridge"],
  ["background.complete", "fork-era background-task vocabulary"],
  ["skin.changed", "fork-era theming push"],
  ["subagent.spawn_requested", "normalize.ts emits subagent.start on the tool_use, not this"],
]);

/**
 * Events the daemon emits that are NOT in KnownGatewayEventName. The enum is
 * documentation-only and explicitly open (frames.ts), so this is legal — it is
 * listed so the gap is visible rather than accidental.
 */
const EVENTS_UNENUMERATED = new Set([
  "approval.resolved", // router.ts approvals settle()
  "clarify.resolved", // router.ts clarifies settle()
  "secret.resolved", // router.ts secrets finish()
]);

/** Events with no daemon emission site that a fixture pins anyway, because the
 *  stamping seam (identity.ts) and the client both handle them and the payload
 *  shape is not in doubt. */
const EVENTS_ADAPTER_VOCABULARY = new Set([
  "reasoning.delta", // identity.ts stamps it beside message.delta/thinking.delta
  "reasoning.available", // MessageStream consumes it as a finalized reasoning block
]);

// ── helpers ─────────────────────────────────────────────────────────────────

function describe(f: Fixture): string {
  return f.path;
}

function expect(f: Fixture, schema: z.ZodTypeAny, value: unknown, what: string): void {
  const r = schema.safeParse(value);
  if (!r.success) {
    assert.fail(
      `${describe(f)}: ${what} does not validate against the v1 contract\n` +
        r.error.issues.map((i) => `  - ${i.path.join(".") || "(root)"}: ${i.message}`).join("\n"),
    );
  }
}

// ── envelope ────────────────────────────────────────────────────────────────

test("every request fixture is a valid JSON-RPC request envelope", () => {
  assert.ok(REQUEST_FIXTURES.length > 0, "no request fixtures found");
  for (const f of REQUEST_FIXTURES) {
    expect(f, JsonRpcRequest, f.frame, "envelope");
    assert.equal(
      (f.frame as { method: string }).method,
      f.name,
      `${describe(f)}: filename says "${f.name}" but the frame's method is ` +
        `"${(f.frame as { method: string }).method}"`,
    );
  }
});

test("every response fixture is a valid result frame (never an error frame)", () => {
  assert.ok(RESPONSE_FIXTURES.length > 0, "no response fixtures found");
  for (const f of RESPONSE_FIXTURES) {
    expect(f, JsonRpcResponse, f.frame, "envelope");
    assert.ok("result" in f.frame, `${describe(f)}: a response fixture must carry \`result\``);
    assert.ok(!("error" in f.frame), `${describe(f)}: error frames belong in fixtures/errors/`);
  }
});

test("every event fixture is a valid event envelope", () => {
  assert.ok(EVENT_FIXTURES.length > 0, "no event fixtures found");
  for (const f of EVENT_FIXTURES) {
    expect(f, JsonRpcEvent, f.frame, "envelope");
    const type = (f.frame as { params: { type: string } }).params.type;
    assert.equal(type, f.name, `${describe(f)}: filename says "${f.name}" but the event type is "${type}"`);
  }
});

test("every error fixture is a valid error frame with a known code", () => {
  assert.ok(ERROR_FIXTURES.length > 0, "no error fixtures found");
  const codes = new Set<number>(Object.values(P.ErrorCode));
  for (const f of ERROR_FIXTURES) {
    expect(f, JsonRpcResponse, f.frame, "envelope");
    const frame = f.frame as { error?: { code: number } };
    assert.ok(frame.error, `${describe(f)}: an error fixture must carry \`error\``);
    assert.ok(
      codes.has(frame.error!.code),
      `${describe(f)}: code ${frame.error!.code} is not in protocol ErrorCode`,
    );
  }
});

// ── params / results / payloads ─────────────────────────────────────────────

test("every request fixture's params validate against its method schema", () => {
  for (const f of REQUEST_FIXTURES) {
    const schema = REQUEST_SCHEMAS[f.name];
    if (!schema) {
      assert.ok(
        UNSCHEMATIZED.has(f.name),
        `${describe(f)}: no param schema for "${f.name}". Add it to ` +
          `MethodParamSchemas (methods.ts), or record it in UNSCHEMATIZED ` +
          `with the reason — an unmapped fixture pins nothing.`,
      );
      continue;
    }
    expect(f, schema, (f.frame as { params?: unknown }).params ?? {}, "params");
  }
});

test("every response fixture's result validates against its method schema", () => {
  for (const f of RESPONSE_FIXTURES) {
    const schema = RESULT_SCHEMAS[f.name];
    if (!schema) {
      assert.ok(
        UNSCHEMATIZED.has(f.name),
        `${describe(f)}: no result schema for "${f.name}". Add one to ` +
          `RESULT_SCHEMAS, or record it in UNSCHEMATIZED with the reason.`,
      );
      continue;
    }
    expect(f, schema, (f.frame as { result: unknown }).result, "result");
  }
});

test("every event fixture's payload validates against its schema, where one exists", () => {
  for (const f of EVENT_FIXTURES) {
    const schema = EVENT_PAYLOAD_SCHEMAS[f.name];
    if (!schema) continue; // deliberately payload-open (frames.ts)
    expect(f, schema, (f.frame as { params: { payload?: unknown } }).params.payload, "payload");
  }
});

test("every event fixture names an event the daemon can emit", () => {
  const known = new Set<string>(P.KnownGatewayEventName.options);
  for (const f of EVENT_FIXTURES) {
    assert.ok(
      known.has(f.name) || EVENTS_UNENUMERATED.has(f.name),
      `${describe(f)}: "${f.name}" is neither in KnownGatewayEventName nor in ` +
        `EVENTS_UNENUMERATED. Add it to frames.ts, or record why it lives outside the enum.`,
    );
    assert.ok(
      !EVENTS_NOT_EMITTED.has(f.name),
      `${describe(f)}: "${f.name}" is listed as never emitted, but a fixture ` +
        `pins a payload for it. Remove the EVENTS_NOT_EMITTED entry if the daemon now emits it.`,
    );
  }
});

// ── completeness (the other direction) ──────────────────────────────────────

test("every method in the protocol registry has a request AND a response fixture", () => {
  const requested = new Set(REQUEST_FIXTURES.map((f) => f.name));
  const answered = new Set(RESPONSE_FIXTURES.map((f) => f.name));
  const missing: string[] = [];
  for (const method of Object.keys(REQUEST_SCHEMAS)) {
    if (!requested.has(method)) missing.push(`${method} (no requests/${method}.json)`);
    if (!answered.has(method)) missing.push(`${method} (no responses/${method}.json)`);
  }
  assert.deepEqual(
    missing,
    [],
    `methods with no fixture pinning their shape — a client cannot be checked ` +
      `against them:\n  ${missing.join("\n  ")}`,
  );
});

test("every emittable gateway event has a fixture", () => {
  const covered = new Set(EVENT_FIXTURES.map((f) => f.name));
  const missing = P.KnownGatewayEventName.options.filter(
    (name) => !covered.has(name) && !EVENTS_NOT_EMITTED.has(name),
  );
  assert.deepEqual(
    missing,
    [],
    `events with no fixture:\n  ${missing.join("\n  ")}\n` +
      `Add one, or record the event in EVENTS_NOT_EMITTED with the reason.`,
  );
  for (const name of EVENTS_UNENUMERATED) {
    assert.ok(covered.has(name), `EVENTS_UNENUMERATED names "${name}" but there is no fixture for it`);
  }
  for (const name of EVENTS_ADAPTER_VOCABULARY) {
    assert.ok(covered.has(name), `EVENTS_ADAPTER_VOCABULARY names "${name}" but there is no fixture for it`);
  }
});

test("the skip lists have no stale entries", () => {
  const known = new Set<string>(P.KnownGatewayEventName.options);
  for (const [method, reason] of UNSCHEMATIZED) {
    assert.ok(reason.length > 20, `UNSCHEMATIZED["${method}"] needs a real reason`);
    assert.ok(
      RESPONSE_FIXTURES.some((f) => f.name === method),
      `UNSCHEMATIZED names "${method}" but there is no fixture for it — drop the entry`,
    );
    assert.equal(
      RESULT_SCHEMAS[method],
      undefined,
      `"${method}" now HAS a result schema — remove it from UNSCHEMATIZED`,
    );
  }
  for (const [name, reason] of EVENTS_NOT_EMITTED) {
    assert.ok(reason.length > 10, `EVENTS_NOT_EMITTED["${name}"] needs a real reason`);
    assert.ok(known.has(name), `EVENTS_NOT_EMITTED names "${name}", which is not in KnownGatewayEventName`);
  }
});

// ── the corpus itself stays neutral ─────────────────────────────────────────

test("fixture content stays synthetic (no absolute paths outside /home/user, no real hosts)", () => {
  const all = [...REQUEST_FIXTURES, ...RESPONSE_FIXTURES, ...EVENT_FIXTURES, ...ERROR_FIXTURES];
  for (const f of all) {
    const text = JSON.stringify(f.frame);
    for (const m of text.match(/\/home\/[a-z0-9_-]+/gi) ?? []) {
      assert.equal(m, "/home/user", `${describe(f)}: non-placeholder home path ${m}`);
    }
    for (const m of text.match(/\b(?:[a-z0-9-]+\.)+(?:com|net|org|io|dev|ts\.net)\b/gi) ?? []) {
      assert.fail(`${describe(f)}: real-looking hostname "${m}" — use host.example (RFC 2606)`);
    }
  }
});
