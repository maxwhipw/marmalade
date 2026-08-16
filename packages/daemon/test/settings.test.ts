// settings.test.ts — settings.get/update, the wire behind the clients'
// "Models" screen (2026-07-25).
//
// The daemon owns the new-session model/effort defaults. Before this slice
// changing them meant hand-editing ~/.marmalade/daemon/config.json and
// restarting marmaladed; now any paired client can write them, the change
// applies to the LIVE daemon (next session.create picks it up) and survives a
// restart (config.json is rewritten atomically). Invariants under test:
//   - the effective default falls back to the ADAPTER's own tier, so a
//     config-less daemon still names a real model instead of "Default"
//   - an unknown model / effort is rejected at set-time (a bad DEFAULT breaks
//     every future session, unlike a per-session pick that fails once)
//   - env-pinned keys are locked: writing one would persist a value the
//     daemon ignores (env outranks the file)
//   - the write lands on disk in a form defaultConfig() reads back

import { test } from "node:test";
import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { rmSync, readFileSync, existsSync } from "node:fs";
import { createRouter } from "../dist/router.js";
import { SessionManager } from "../dist/session-manager.js";
import { TranscriptCache } from "../dist/transcript-cache.js";
import { UsageMeter } from "../dist/usage.js";
import { defaultConfig, loadConfigFile, writeConfigFile, envLockedSettings } from "../dist/config.js";
import { EffortClampedPayload } from "@marmalade/protocol";

type Spawn = { sessionId: string; model?: string; effort?: string };

function harness(file: Record<string, unknown> = {}) {
  const dir = join(tmpdir(), `settings-${randomUUID()}`);
  const configPath = join(dir, "config.json");
  const sessions = SessionManager.inMemory();
  const transcripts = new TranscriptCache(dir);
  const spawns: Spawn[] = [];
  let n = 0;
  const router = createRouter({
    cfg: defaultConfig(file as never),
    sessions,
    transcripts,
    usage: new UsageMeter(),
    configPath,
    adapter: {
      name: "fake",
      listModels: () => [
        { id: "m-alpha", label: "Alpha", description: "the standard" },
        { id: "m-beta", label: "Beta" },
      ],
      defaultModel: () => "m-alpha",
      spawn(_spec: unknown, opts: { daemonSessionId: string; model?: string; effort?: string }, cb: { onHarnessSession(id: string): void }) {
        spawns.push({ sessionId: opts.daemonSessionId, model: opts.model, effort: opts.effort });
        cb.onHarnessSession(`h-${opts.daemonSessionId}`);
        return { async send() {}, async interrupt() {}, async stop() {} };
      },
    } as never,
    today: () => "2026-07-25",
    now: () => 1000 + n,
    mintSessionId: () => `s_${++n}`,
  });
  const sent: Record<string, unknown>[] = [];
  const conn = {
    ws: { send: (s: string) => sent.push(JSON.parse(s)) },
    principal: "owner", legacy: false, capabilities: [],
  } as never;
  return {
    router, sessions, spawns, conn, configPath, transcripts, sent, dir,
    cleanup: () => rmSync(dir, { recursive: true, force: true }),
  };
}

/** The durable clamp records in a session's transcript (E3). */
function clampEvents(transcripts: TranscriptCache, sessionId: string) {
  return transcripts.replay(sessionId)
    .filter((e) => e.params.type === "effort.clamped")
    .map((e) => e.params.payload as Record<string, unknown>);
}

test("settings.get reports the EFFECTIVE default — adapter tier when config is silent", async () => {
  const h = harness();
  try {
    const r = (await h.router("settings.get", {}, h.conn)) as Record<string, unknown>;
    assert.equal(r.default_model, "m-alpha"); // from the adapter, not config
    assert.equal(r.default_effort, null);     // no adapter fallback for effort
    assert.deepEqual(r.locked, []);
  } finally { h.cleanup(); }
});

test("config outranks the adapter default", async () => {
  const h = harness({ default_model: "m-beta", default_effort: "xhigh" });
  try {
    const r = (await h.router("settings.get", {}, h.conn)) as Record<string, unknown>;
    assert.equal(r.default_model, "m-beta");
    assert.equal(r.default_effort, "xhigh");
  } finally { h.cleanup(); }
});

test("update applies to the LIVE daemon (next create is stamped) and persists", async () => {
  const h = harness();
  try {
    const after = (await h.router(
      "settings.update", { default_model: "m-beta", default_effort: "high" }, h.conn,
    )) as Record<string, unknown>;
    assert.equal(after.default_model, "m-beta");
    assert.equal(after.default_effort, "high");

    // Live: a create made right now — no restart — gets the new values.
    const { session_id } = (await h.router("session.create", { cols: 80 }, h.conn)) as { session_id: string };
    assert.equal(h.spawns[0].model, "m-beta");
    assert.equal(h.spawns[0].effort, "high");
    assert.equal(h.sessions.get(session_id)!.model, "m-beta");

    // Durable: the file reads back through the strict schema.
    const onDisk = loadConfigFile(h.configPath);
    assert.equal(onDisk.default_model, "m-beta");
    assert.equal(onDisk.default_effort, "high");
    assert.equal(defaultConfig(onDisk).defaultModel, "m-beta");
  } finally { h.cleanup(); }
});

test("update is a PATCH — an omitted key is untouched, an explicit null clears", async () => {
  const h = harness({ default_model: "m-beta", default_effort: "high" });
  try {
    await h.router("settings.update", { default_effort: "low" }, h.conn);
    let r = (await h.router("settings.get", {}, h.conn)) as Record<string, unknown>;
    assert.equal(r.default_model, "m-beta", "omitted key survives");
    assert.equal(r.default_effort, "low");

    await h.router("settings.update", { default_model: null }, h.conn);
    r = (await h.router("settings.get", {}, h.conn)) as Record<string, unknown>;
    // Cleared → falls back to the adapter's own tier, not to nothing.
    assert.equal(r.default_model, "m-alpha");
    assert.equal(loadConfigFile(h.configPath).default_model, undefined);
  } finally { h.cleanup(); }
});

test("a bad default is rejected at set-time and nothing is written", async () => {
  const h = harness();
  try {
    await assert.rejects(
      () => h.router("settings.update", { default_model: "m-nope" }, h.conn),
      /unknown model "m-nope"/,
    );
    await assert.rejects(
      () => h.router("settings.update", { default_effort: "turbo" }, h.conn),
      /not one of low\/medium\/high\/xhigh\/max/,
    );
    assert.equal(existsSync(h.configPath), false, "a rejected update writes no file");
  } finally { h.cleanup(); }
});

test("env-pinned keys are locked: reported, and refused on write", async () => {
  const prev = process.env.MARMALADE_DEFAULT_MODEL;
  process.env.MARMALADE_DEFAULT_MODEL = "m-beta";
  const h = harness();
  try {
    assert.deepEqual(envLockedSettings(), ["default_model"]);
    const r = (await h.router("settings.get", {}, h.conn)) as Record<string, unknown>;
    assert.deepEqual(r.locked, ["default_model"]);
    assert.equal(r.default_model, "m-beta");
    await assert.rejects(
      () => h.router("settings.update", { default_model: "m-alpha" }, h.conn),
      /pinned by MARMALADE_DEFAULT_MODEL/,
    );
    // The un-pinned key on the same call surface still works.
    await h.router("settings.update", { default_effort: "medium" }, h.conn);
    assert.equal(loadConfigFile(h.configPath).default_effort, "medium");
  } finally {
    h.cleanup();
    if (prev === undefined) delete process.env.MARMALADE_DEFAULT_MODEL;
    else process.env.MARMALADE_DEFAULT_MODEL = prev;
  }
});

test("model.list publishes the effort vocabulary + model descriptions", async () => {
  const h = harness();
  try {
    const r = (await h.router("model.list", {}, h.conn)) as {
      models: Array<{ id: string; label: string; description?: string }>;
      efforts?: string[];
      default_model?: string;
    };
    assert.deepEqual(r.efforts, ["low", "medium", "high", "xhigh", "max"]);
    assert.equal(r.models[0].description, "the standard");
    // The advertised default is the same one a create resolves to (they used
    // to be able to disagree: model.list read config only).
    assert.equal(r.default_model, "m-alpha");
  } finally { h.cleanup(); }
});

// --- per-model effort bounds (2026-07-27) -------------------------------
//
// The daemon owns the truth and CLAMPS: switching models must never fail a
// create, it lands on the nearest allowed level, and the clamped value is
// what the row stores and the wire returns. Clients render the answer.

test("settings.get exposes model_efforts — {} when unset, the map when configured", async () => {
  const bare = harness();
  try {
    const r = (await bare.router("settings.get", {}, bare.conn)) as Record<string, unknown>;
    assert.deepEqual(r.model_efforts, {});
  } finally { bare.cleanup(); }

  const h = harness({ model_efforts: { "m-alpha": { min: "high" } } });
  try {
    const r = (await h.router("settings.get", {}, h.conn)) as Record<string, unknown>;
    assert.deepEqual(r.model_efforts, { "m-alpha": { min: "high" } });
  } finally { h.cleanup(); }
});

test("model_efforts update is a PER-MODEL patch: add, replace, remove", async () => {
  const h = harness({ model_efforts: { "m-alpha": { min: "high" } } });
  try {
    // Add a second model — the first is untouched.
    let r = (await h.router(
      "settings.update", { model_efforts: { "m-beta": { max: "medium" } } }, h.conn,
    )) as Record<string, unknown>;
    assert.deepEqual(r.model_efforts, {
      "m-alpha": { min: "high" },
      "m-beta": { max: "medium" },
    });

    // Replace one entry WHOLESALE — the old max is dropped, not merged.
    await h.router("settings.update", { model_efforts: { "m-beta": { min: "low" } } }, h.conn);
    r = (await h.router("settings.get", {}, h.conn)) as Record<string, unknown>;
    assert.deepEqual((r.model_efforts as Record<string, unknown>)["m-beta"], { min: "low" });

    // null removes just that model.
    await h.router("settings.update", { model_efforts: { "m-beta": null } }, h.conn);
    r = (await h.router("settings.get", {}, h.conn)) as Record<string, unknown>;
    assert.deepEqual(r.model_efforts, { "m-alpha": { min: "high" } });

    // Round-trips through the strict config file and back into a live config.
    assert.deepEqual(loadConfigFile(h.configPath).model_efforts, { "m-alpha": { min: "high" } });
    assert.deepEqual(defaultConfig(loadConfigFile(h.configPath)).modelEfforts,
      { "m-alpha": { min: "high" } });

    // Emptying the map clears the key rather than persisting {}.
    await h.router("settings.update", { model_efforts: { "m-alpha": null } }, h.conn);
    assert.equal(loadConfigFile(h.configPath).model_efforts, undefined);
    r = (await h.router("settings.get", {}, h.conn)) as Record<string, unknown>;
    assert.deepEqual(r.model_efforts, {});
  } finally { h.cleanup(); }
});

test("model_efforts rejects an unknown model and an invalid bound, writing nothing", async () => {
  const h = harness();
  try {
    await assert.rejects(
      () => h.router("settings.update", { model_efforts: { "m-nope": { min: "high" } } }, h.conn),
      /unknown model "m-nope"/,
    );
    await assert.rejects(
      () => h.router("settings.update", { model_efforts: { "m-alpha": { min: "max", max: "low" } } }, h.conn),
      /deeper than max/,
    );
    await assert.rejects(
      () => h.router("settings.update", { model_efforts: { "m-alpha": {} } }, h.conn),
      /at least one of min\/max/,
    );
    await assert.rejects(
      () => h.router("settings.update", { model_efforts: { "m-alpha": { min: "turbo" } } }, h.conn),
      /model_efforts\["m-alpha"\] invalid/,
    );
    assert.equal(existsSync(h.configPath), false, "a rejected update writes no file");
  } finally { h.cleanup(); }
});

test("model.list carries each model's bounds; unmatched ids simply don't render", async () => {
  const h = harness({
    model_efforts: { "m-alpha": { min: "high" }, "m-gone": { max: "low" } },
  });
  try {
    const r = (await h.router("model.list", {}, h.conn)) as {
      models: Array<{ id: string; effort_min?: string; effort_max?: string }>;
    };
    const alpha = r.models.find((m) => m.id === "m-alpha")!;
    assert.equal(alpha.effort_min, "high");
    assert.equal(alpha.effort_max, undefined, "an unset edge is omitted, not null");
    const beta = r.models.find((m) => m.id === "m-beta")!;
    assert.equal(beta.effort_min, undefined);
    // Bounds for a model the harness no longer lists don't invent a row.
    assert.equal(r.models.length, 2);
  } finally { h.cleanup(); }
});

test("session.create clamps the effort BEFORE stamping the row and spawning", async () => {
  const h = harness({ model_efforts: { "m-alpha": { min: "high" }, "m-beta": { max: "medium" } } });
  try {
    // Above the ceiling → down to max.
    const a = (await h.router(
      "session.create", { model: "m-beta", reasoning_effort: "max" }, h.conn,
    )) as { session_id: string };
    assert.equal(h.spawns[0].effort, "medium");
    assert.equal(h.sessions.get(a.session_id)!.reasoningEffort, "medium");

    // Below the floor → up to min.
    const b = (await h.router(
      "session.create", { model: "m-alpha", reasoning_effort: "low" }, h.conn,
    )) as { session_id: string };
    assert.equal(h.spawns[1].effort, "high");
    assert.equal(h.sessions.get(b.session_id)!.reasoningEffort, "high");

    // An unbounded edge leaves an in-range pick alone.
    const c = (await h.router(
      "session.create", { model: "m-beta", reasoning_effort: "low" }, h.conn,
    )) as { session_id: string };
    assert.equal(h.sessions.get(c.session_id)!.reasoningEffort, "low");
  } finally { h.cleanup(); }
});

test("the CONFIG default effort is clamped too — a bounded model can't inherit a bad one", async () => {
  const h = harness({ default_effort: "max", model_efforts: { "m-beta": { max: "medium" } } });
  try {
    const { session_id } = (await h.router("session.create", { model: "m-beta" }, h.conn)) as { session_id: string };
    assert.equal(h.sessions.get(session_id)!.reasoningEffort, "medium");
  } finally { h.cleanup(); }
});

test("a NULL effort stays null — bounds never manufacture one", async () => {
  // No default_effort configured: "defer to the harness" is a real answer and
  // a min bound must not turn it into an explicit floor.
  const h = harness({ model_efforts: { "m-alpha": { min: "high" } } });
  try {
    const { session_id } = (await h.router("session.create", { model: "m-alpha" }, h.conn)) as { session_id: string };
    assert.equal(h.sessions.get(session_id)!.reasoningEffort, null);
    assert.equal(h.spawns[0].effort, undefined, "no effort is passed to the spawn");
  } finally { h.cleanup(); }
});

test("session.effort clamps against the SESSION's model and returns the clamped value", async () => {
  const h = harness({ model_efforts: { "m-alpha": { min: "high" } } });
  try {
    const { session_id } = (await h.router(
      "session.create", { model: "m-alpha", reasoning_effort: "high" }, h.conn,
    )) as { session_id: string };
    const r = (await h.router(
      "session.effort", { session_id, reasoning_effort: "low" }, h.conn,
    )) as { reasoning_effort: string };
    // The result is the clamp — a client rendering the answer can't snap back.
    assert.equal(r.reasoning_effort, "high");
    assert.equal(h.sessions.get(session_id)!.reasoningEffort, "high");

    // An unbounded model is untouched by another model's bounds.
    const other = (await h.router("session.create", { model: "m-beta" }, h.conn)) as { session_id: string };
    const r2 = (await h.router(
      "session.effort", { session_id: other.session_id, reasoning_effort: "low" }, h.conn,
    )) as { reasoning_effort: string };
    assert.equal(r2.reasoning_effort, "low");
  } finally { h.cleanup(); }
});

// ── effort.clamped, the durable E3 record (2026-07-27) ──────────────────────
// Design-lab option E3 (signed off 2026-07-27): when a clamp actually CHANGES the
// requested effort, the record is a quiet, permanent transcript line — no
// toast, nothing to dismiss. So it must be PERSISTED (survives a cold load and
// replay), broadcast to live subscribers, and absent when nothing changed.

test("session.create records a durable effort.clamped when the clamp changes the value", async () => {
  const h = harness({ model_efforts: { "m-alpha": { min: "high" }, "m-beta": { max: "medium" } } });
  try {
    // Ceiling bit: clamped DOWN.
    const a = (await h.router(
      "session.create", { model: "m-beta", reasoning_effort: "max" }, h.conn,
    )) as { session_id: string };
    const [down] = clampEvents(h.transcripts, a.session_id);
    assert.deepEqual(
      { requested: down.requested, effective: down.effective, model: down.model, bound: down.bound, limit: down.limit },
      { requested: "max", effective: "medium", model: "m-beta", bound: "max", limit: "medium" },
    );
    // Stamped like every other durable event — ordered and timed.
    assert.equal(typeof down.seq, "number");
    assert.equal(typeof down.ts, "number");
    EffortClampedPayload.parse(down); // drift lock against the protocol schema

    // Floor bit: clamped UP.
    const b = (await h.router(
      "session.create", { model: "m-alpha", reasoning_effort: "low" }, h.conn,
    )) as { session_id: string };
    const [up] = clampEvents(h.transcripts, b.session_id);
    assert.equal(up.bound, "min");
    assert.equal(up.effective, "high");
    assert.equal(up.limit, "high");
  } finally { h.cleanup(); }
});

test("the clamped CONFIG default is recorded too — including on the main session", async () => {
  const h = harness({ default_effort: "max", model_efforts: { "m-alpha": { max: "medium" } } });
  try {
    // m-alpha is the adapter's default model, so the daemon-created main
    // session inherits default_effort and gets clamped without a client pick.
    const id = await (h.router as unknown as { ensureMain(): Promise<string> }).ensureMain();
    const [ev] = clampEvents(h.transcripts, id);
    assert.deepEqual(
      { requested: ev.requested, effective: ev.effective, model: ev.model, bound: ev.bound, limit: ev.limit },
      { requested: "max", effective: "medium", model: "m-alpha", bound: "max", limit: "medium" },
    );
  } finally { h.cleanup(); }
});

test("session.effort records the clamp at that point in the transcript, and broadcasts it", async () => {
  const h = harness({ model_efforts: { "m-alpha": { min: "high" } } });
  try {
    const { session_id } = (await h.router(
      "session.create", { model: "m-alpha", reasoning_effort: "high" }, h.conn,
    )) as { session_id: string };
    assert.deepEqual(clampEvents(h.transcripts, session_id), [], "an in-range create records nothing");

    h.sent.length = 0;
    await h.router("session.effort", { session_id, reasoning_effort: "low" }, h.conn);
    const [ev] = clampEvents(h.transcripts, session_id);
    assert.equal(ev.requested, "low");
    assert.equal(ev.effective, "high");
    assert.equal(ev.bound, "min");
    // Live subscribers see the same frame (the creating conn is auto-subscribed).
    const live = h.sent.filter((f) => (f.params as { type?: string }).type === "effort.clamped");
    assert.equal(live.length, 1);
    assert.deepEqual((live[0].params as { payload: unknown }).payload, ev);
  } finally { h.cleanup(); }
});

test("session.model re-clamps the stored effort against the NEW model's bounds", async () => {
  // THE scenario bounds exist for: Opus@xhigh → Fable must not keep xhigh.
  const h = harness({ model_efforts: { "m-beta": { max: "medium" } } });
  try {
    const { session_id } = (await h.router(
      "session.create", { model: "m-alpha", reasoning_effort: "xhigh" }, h.conn,
    )) as { session_id: string };
    assert.deepEqual(clampEvents(h.transcripts, session_id), [], "in range on the old model");

    const res = (await h.router(
      "session.model", { session_id, model: "m-beta" }, h.conn,
    )) as { model: string; reasoning_effort?: string };
    assert.equal(res.model, "m-beta");
    assert.equal(res.reasoning_effort, "medium", "result carries the post-clamp truth");
    const [ev] = clampEvents(h.transcripts, session_id);
    assert.deepEqual(
      { requested: ev.requested, effective: ev.effective, model: ev.model, bound: ev.bound, limit: ev.limit },
      { requested: "xhigh", effective: "medium", model: "m-beta", bound: "max", limit: "medium" },
    );
    // The row itself is healed, not just the result.
    const rows = (await h.router("session.list", {}, h.conn)) as
      { sessions: { session_id: string; reasoning_effort: string | null }[] };
    assert.equal(rows.sessions.find((s) => s.session_id === session_id)?.reasoning_effort, "medium");

    // Switching to an unbounded model leaves the effort alone (and stays silent).
    await h.router("session.model", { session_id, model: "m-alpha" }, h.conn);
    assert.equal(clampEvents(h.transcripts, session_id).length, 1, "no second event");
  } finally { h.cleanup(); }
});

test("no clamp, no event — an in-range pick and an unbounded model stay silent", async () => {
  const h = harness({ model_efforts: { "m-alpha": { min: "high" } } });
  try {
    // In range for a bounded model.
    const a = (await h.router(
      "session.create", { model: "m-alpha", reasoning_effort: "xhigh" }, h.conn,
    )) as { session_id: string };
    await h.router("session.effort", { session_id: a.session_id, reasoning_effort: "max" }, h.conn);
    assert.deepEqual(clampEvents(h.transcripts, a.session_id), []);

    // No bounds at all for this model.
    const b = (await h.router(
      "session.create", { model: "m-beta", reasoning_effort: "low" }, h.conn,
    )) as { session_id: string };
    await h.router("session.effort", { session_id: b.session_id, reasoning_effort: "low" }, h.conn);
    assert.deepEqual(clampEvents(h.transcripts, b.session_id), []);

    // A null effort can't be clamped, so it can't be recorded either.
    const c = (await h.router("session.create", { model: "m-alpha" }, h.conn)) as { session_id: string };
    assert.deepEqual(clampEvents(h.transcripts, c.session_id), []);
  } finally { h.cleanup(); }
});

test("the clamp record survives a cold load, in seq order, replayable by since_seq", async () => {
  const h = harness({ model_efforts: { "m-alpha": { min: "high" } } });
  try {
    const { session_id } = (await h.router(
      "session.create", { model: "m-alpha", reasoning_effort: "low" }, h.conn,
    )) as { session_id: string };
    // A SECOND cache over the same dir = the cold-load path (daemon restart).
    const cold = new TranscriptCache(h.dir);
    const events = cold.replay(session_id);
    const idx = events.findIndex((e) => e.params.type === "effort.clamped");
    assert.ok(idx >= 0, "the record is on disk, not just in memory");
    // seq is strictly increasing across the whole replay, so a since_seq
    // cursor sees the clamp exactly once and in the right place.
    const seqs = events.map((e) => (e.params.payload as { seq: number }).seq);
    assert.deepEqual(seqs, [...seqs].sort((x, y) => x - y));
    assert.equal(new Set(seqs).size, seqs.length, "no reused seq");
    assert.equal(cold.lastSeq(session_id), Math.max(...seqs));
  } finally { h.cleanup(); }
});

test("writeConfigFile preserves unrelated keys and rejects an invalid merge", () => {
  const dir = join(tmpdir(), `cfgwrite-${randomUUID()}`);
  const path = join(dir, "config.json");
  try {
    writeConfigFile({ context_reminder_percent: 45 }, path);
    writeConfigFile({ default_model: "claude-opus-5" }, path);
    const raw = JSON.parse(readFileSync(path, "utf8"));
    assert.equal(raw.context_reminder_percent, 45, "unrelated key survives a later write");
    assert.equal(raw.default_model, "claude-opus-5");
    assert.throws(() => writeConfigFile({ default_effort: "turbo" as never }, path), /Invalid/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
