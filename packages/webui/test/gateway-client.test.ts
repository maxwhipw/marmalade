// gateway-client.test.ts — the webui gateway client's digital-twin suite.
//
// Mirrors packages/daemon/test/subscribe.test.ts in spirit: scripted frame
// sequences over a fake socket, asserting the locked session-ids invariants
// (client repo .claude/rules/session-ids.md) hold on the CLIENT side:
//   - ack binding (session.create result → server session_id)
//   - watermark dedup (a re-sent seq applies once)
//   - replay-then-live gaplessness (subscribe replay + live share one dispatch)
//   - seen stamping (arithmetic unread; submitting is seeing)
//   - reconnect-resubscribe (a drop re-hellos and re-subscribes from the
//     watermark without losing the run)

import { describe, expect, test } from "vitest";
import { GatewayClient } from "../src/gateway/client.js";
import { FakeGateway, type FakeGatewayScript } from "./fake-gateway.js";
import { applyEvent, emptySessionState, isUnread } from "../src/gateway/session-state.js";

/** Wire a client to a fresh fake, open it (which runs hello), return both. */
async function connected(script: FakeGatewayScript = {}) {
  const fake = new FakeGateway(script);
  const client = new GatewayClient({
    url: "ws://127.0.0.1:9130/api/ws",
    deviceId: "dev-test",
    deviceName: "webui-test",
    socketFactory: () => fake.socket,
    now: () => 1000,
    backoffBaseMs: 1,
    backoffMaxMs: 1,
  });
  client.connect();
  fake.fireOpen(); // delivers gateway.ready, client sends hello, fake acks it
  await flush();
  return { fake, client };
}

/** Let the microtask queue drain (the client's RPC promises resolve on the
 *  synchronous fake, but the .then chains need a tick). */
function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 0));
}

describe("hello + features", () => {
  test("hello runs on connect and negotiated features are stored", async () => {
    const { fake, client } = await connected({ features: ["stable-ids", "subscribe"] });
    expect(client.getStatus()).toBe("connected");
    expect(client.getFeatures()).toEqual(["stable-ids", "subscribe"]);
    expect(client.hasFeature("subscribe")).toBe(true);
    // Feature-gated UI derives from this list: "fs" is absent → editor stays off.
    expect(client.hasFeature("state-reads")).toBe(false);
    expect(fake.requests[0].method).toBe("hello");
  });

  test("the token rides hello auth.token only — never the socket URL", async () => {
    // A URL copy leaks the credential into anything that logs request lines
    // (reverse proxies, ts serve debugging); the daemon authenticates hello
    // auth.token on its own, so the URL stays clean.
    const urls: string[] = [];
    const fake = new FakeGateway({});
    const client = new GatewayClient({
      url: "ws://100.64.1.2:9130/api/ws",
      token: "dtok-secret",
      deviceId: "dev-test",
      deviceName: "webui-test",
      socketFactory: (url) => {
        urls.push(url);
        return fake.socket;
      },
      now: () => 1000,
      backoffBaseMs: 1,
      backoffMaxMs: 1,
    });
    client.connect();
    fake.fireOpen();
    await flush();
    expect(urls).toEqual(["ws://100.64.1.2:9130/api/ws"]);
    const hello = fake.requests.find((r) => r.method === "hello")!;
    expect((hello.params as { auth?: { token?: string } }).auth).toEqual({ token: "dtok-secret" });
  });
});

describe("ack binding (session-ids rule 1/4)", () => {
  test("session.create adopts the server-minted session_id verbatim", async () => {
    const { fake, client } = await connected({
      handlers: { "session.create": () => ({ session_id: "s_server_minted" }) },
    });
    const id = await client.createSession({ model: "claude-opus-4-8" });
    expect(id).toBe("s_server_minted");
    const create = fake.requests.find((r) => r.method === "session.create")!;
    expect(create.params.model).toBe("claude-opus-4-8");
    // The client tracks the session under the server id (never a synth key).
    expect(client.getSessionState("s_server_minted")).toBeDefined();
  });
});

describe("replay-then-live gaplessness (rules 2/5)", () => {
  test("subscribe replay and live events flow through one dispatch, deduped by watermark", async () => {
    const { fake, client } = await connected();
    // openSession = resume + subscribe(since_seq = watermark = 0).
    await client.openSession("s1");
    // Replay: the daemon would re-send the cached tail. Push a user + assistant
    // turn as if replayed.
    fake.pushEvent("message.user", { message_id: "mu", seq: 1, ts: 1, text: "hi" }, "s1");
    fake.pushEvent("message.start", { message_id: "ma", seq: 2, ts: 2 }, "s1");
    fake.pushEvent("message.delta", { message_id: "ma", seq: 3, ts: 3, text: "hel" }, "s1");
    fake.pushEvent("message.delta", { message_id: "ma", seq: 4, ts: 4, text: "lo" }, "s1");
    fake.pushEvent("message.complete", { message_id: "ma", seq: 5, ts: 5 }, "s1");

    let state = client.getSessionState("s1")!;
    expect(state.lastSeq).toBe(5);
    expect(state.messages.map((m) => m.role)).toEqual(["user", "assistant"]);
    expect(state.messages[1].text).toBe("hello");
    expect(state.messages[1].streaming).toBe(false);

    // Boundary overlap: a reconnect/replay re-sends seq 4 and 5 (already applied).
    // The watermark drops them — no double text, no dup message.
    fake.pushEvent("message.delta", { message_id: "ma", seq: 4, ts: 4, text: "lo" }, "s1");
    fake.pushEvent("message.complete", { message_id: "ma", seq: 5, ts: 5 }, "s1");
    state = client.getSessionState("s1")!;
    expect(state.messages[1].text).toBe("hello"); // not "hellolo"
    expect(state.messages).toHaveLength(2);

    // Live continues seamlessly past the watermark.
    fake.pushEvent("message.start", { message_id: "mb", seq: 6, ts: 6 }, "s1");
    fake.pushEvent("message.delta", { message_id: "mb", seq: 7, ts: 7, text: "next" }, "s1");
    state = client.getSessionState("s1")!;
    expect(state.messages).toHaveLength(3);
    expect(state.lastSeq).toBe(7);
  });

  test("openSession subscribes from the current watermark, not from zero, on re-attach", async () => {
    const captured: number[] = [];
    const { fake, client } = await connected({
      handlers: {
        "session.subscribe": (p) => {
          captured.push(p.since_seq as number);
          return { session_id: p.session_id, replayed: 0, last_seq: 0, lifecycle: "active", run_state: "idle" };
        },
      },
    });
    await client.openSession("s1");
    fake.pushEvent("message.user", { message_id: "mu", seq: 3, ts: 1, text: "x" }, "s1");
    await client.openSession("s1"); // re-attach after advancing the watermark
    expect(captured).toEqual([0, 3]); // second subscribe carries since_seq=3
  });
});

describe("seen stamping (rule 2 — arithmetic unread)", () => {
  test("markSeen stamps the watermark and clears unread; submitting is seeing", async () => {
    const { fake, client } = await connected();
    await client.openSession("s1");
    fake.pushEvent("message.user", { message_id: "mu", seq: 1, ts: 1, text: "hi" }, "s1");
    fake.pushEvent("message.complete", { message_id: "ma", seq: 2, ts: 2 }, "s1");

    expect(isUnread(client.getSessionState("s1")!.lastSeq, client.getSeenSeq("s1"))).toBe(true);
    await client.markSeen("s1");
    expect(client.getSeenSeq("s1")).toBe(2);
    expect(isUnread(client.getSessionState("s1")!.lastSeq, client.getSeenSeq("s1"))).toBe(false);
    const seen = fake.requests.find((r) => r.method === "session.seen")!;
    expect(seen.params.seq).toBe(2);

    // Submitting IS seeing: after a prompt.submit the submitting device's cursor
    // catches up, so it never badges its own message.
    fake.pushEvent("message.user", { message_id: "mu2", seq: 3, ts: 3, text: "more" }, "s1");
    await client.submitPrompt("s1", "more");
    expect(client.getSeenSeq("s1")).toBeGreaterThanOrEqual(3);
  });
});

describe("optimistic own-message (the submitter renders its own bubble)", () => {
  test("submitPrompt renders the user's message from the ack — the daemon withholds message.user from the sender", async () => {
    const { fake, client } = await connected();
    await client.openSession("s1");
    // The fake acks {message_id:'m_user', seq:1} and — like the real daemon —
    // sends NO message.user back to the submitting client.
    await client.submitPrompt("s1", "hello there");
    const msgs = client.getSessionState("s1")!.messages;
    expect(msgs.length).toBe(1);
    expect(msgs[0]).toMatchObject({ role: "user", text: "hello there", id: "m_user" });

    // A later subscribe replay of the same message.user must NOT double it.
    fake.pushEvent("message.user", { message_id: "m_user", seq: 1, ts: 1000, text: "hello there" }, "s1");
    expect(client.getSessionState("s1")!.messages.length).toBe(1);
  });

  test("a partial ack renders nothing — no synthesized id, no seq-0 pin", async () => {
    // message_id AND seq must both be present (&&). A message_id-less ack
    // would synthesize `user-<seq>`, which can't dedup against the replayed
    // message.user (double bubble); a seq-less ack would pin the bubble at
    // seq 0, the transcript top. Either way the subscribe replay delivers the
    // message under its real identity — rendering nothing now is correct.
    const acks: Array<Record<string, unknown>> = [
      { seq: 7, ts: 1000 }, // message_id missing
      { message_id: "m_user", ts: 1000 }, // seq missing
      {}, // legacy empty ack
    ];
    const { fake, client } = await connected({
      handlers: { "prompt.submit": () => acks.shift()! },
    });
    await client.openSession("s1");
    await client.submitPrompt("s1", "one");
    await client.submitPrompt("s1", "two");
    await client.submitPrompt("s1", "three");
    expect(client.getSessionState("s1")!.messages.length).toBe(0);

    // The replayed message.user then renders it exactly once, real identity.
    fake.pushEvent("message.user", { message_id: "mu-real", seq: 7, ts: 1000, text: "one" }, "s1");
    const msgs = client.getSessionState("s1")!.messages;
    expect(msgs.length).toBe(1);
    expect(msgs[0]).toMatchObject({ role: "user", id: "mu-real", text: "one" });
  });

  test("the optimistic user bubble stays before a racing assistant stream", async () => {
    const { fake, client } = await connected();
    await client.openSession("s1");
    // Assistant stream lands BEFORE we apply our own message (ack seq 1 < the
    // assistant's seqs) — the bubble must still sort before its reply.
    fake.pushEvent("message.start", { message_id: "ma", seq: 2, ts: 2 }, "s1");
    fake.pushEvent("message.delta", { message_id: "ma", seq: 3, ts: 3, text: "hi" }, "s1");
    await client.submitPrompt("s1", "my question");
    const msgs = client.getSessionState("s1")!.messages;
    expect(msgs.map((m) => m.role)).toEqual(["user", "assistant"]);
    expect(msgs[0].text).toBe("my question");
  });
});

describe("reconnect-resubscribe (a drop never kills a run)", () => {
  test("a dropped socket re-hellos then re-subscribes every attached session from its watermark", async () => {
    const subscribes: Array<{ id: string; since: number }> = [];
    const { fake, client } = await connected({
      handlers: {
        "session.subscribe": (p) => {
          subscribes.push({ id: p.session_id as string, since: p.since_seq as number });
          return { session_id: p.session_id, replayed: 0, last_seq: 0, lifecycle: "active", run_state: "running" };
        },
      },
    });
    await client.openSession("s1");
    fake.pushEvent("message.start", { message_id: "ma", seq: 4, ts: 1 }, "s1");
    fake.pushEvent("message.delta", { message_id: "ma", seq: 5, ts: 2, text: "part" }, "s1");
    expect(subscribes).toEqual([{ id: "s1", since: 0 }]);

    // Drop mid-run. The client backs off (1ms in this test) and reconnects.
    fake.drop();
    expect(client.getStatus()).toBe("reconnecting");
    await flush(); // let the reconnect timer fire (backoff = 1ms)
    fake.fireOpen(); // new connection: gateway.ready → hello → re-subscribe
    await flush();

    expect(client.getStatus()).toBe("connected");
    // Re-subscribed from the watermark (seq 5 seen so far), not from zero — the
    // in-flight run resumes gaplessly.
    expect(subscribes[1]).toEqual({ id: "s1", since: 5 });
    // The run's partial survived the reconnect (no teardown).
    expect(client.getSessionState("s1")!.messages[0].text).toBe("part");
    expect(client.getSessionState("s1")!.messages[0].streaming).toBe(true);
  });
});

describe("live session.deleted", () => {
  test("a session.deleted event drops the session and emits deleted", async () => {
    const { fake, client } = await connected();
    await client.openSession("s1");
    const deleted: string[] = [];
    client.on("deleted", (id) => deleted.push(id));
    fake.pushEvent("session.deleted", {}, "s1");
    expect(deleted).toEqual(["s1"]);
    expect(client.getSessionState("s1")).toBeUndefined();
  });
});

describe("session-state unit (pure reducer)", () => {
  test("tool.start/complete attach a card to the open assistant message with duration", () => {
    let s = emptySessionState();
    s = applyEvent(s, "message.start", { message_id: "ma", seq: 1 });
    s = applyEvent(s, "tool.start", { message_id: "ma", id: "t1", name: "Bash", seq: 2 });
    s = applyEvent(s, "tool.complete", { tool_use_id: "t1", duration_ms: 42, seq: 3 });
    const tools = s.messages[0].tools;
    expect(tools).toHaveLength(1);
    expect(tools[0].name).toBe("Bash");
    expect(tools[0].running).toBe(false);
    expect(tools[0].durationMs).toBe(42);
  });

  test("a delta with no prior message.start synthesizes the assistant message (ACP path)", () => {
    let s = emptySessionState();
    s = applyEvent(s, "message.delta", { message_id: "ma", seq: 1, text: "hi" });
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0].text).toBe("hi");
    expect(s.messages[0].role).toBe("assistant");
  });

  test("message.complete carries has_cut_point tri-state onto the row (absent stays undefined)", () => {
    let s = emptySessionState();
    // Fork-copied shape: the daemon flips the flag to false on copied events.
    s = applyEvent(s, "message.start", { message_id: "ma", seq: 1 });
    s = applyEvent(s, "message.complete", { message_id: "ma", seq: 2, has_cut_point: false });
    // Pre-flag transcript: no flag on the wire — the legacy offer must stay.
    s = applyEvent(s, "message.start", { message_id: "mb", seq: 3 });
    s = applyEvent(s, "message.complete", { message_id: "mb", seq: 4 });
    expect(s.messages[0].hasCutPoint).toBe(false);
    expect(s.messages[1].hasCutPoint).toBeUndefined();
  });

  test("message.user steered:true marks the row; a plain user row stays unmarked", () => {
    let s = emptySessionState();
    s = applyEvent(s, "message.user", { message_id: "mu", seq: 1, text: "use TS", steered: true });
    s = applyEvent(s, "message.user", { message_id: "mu2", seq: 2, text: "normal" });
    expect(s.messages[0].steered).toBe(true);
    expect(s.messages[1].steered).toBeUndefined();
  });

  test("session.compaction toggles the transient compacting chip; terminals + ended clear it", () => {
    let s = emptySessionState();
    expect(s.compacting).toBe(false);
    s = applyEvent(s, "session.compaction", { status: "started", seq: 1 });
    expect(s.compacting).toBe(true);
    s = applyEvent(s, "session.compaction", { status: "completed", seq: 2 });
    expect(s.compacting).toBe(false);
    // failed is also a terminal
    s = applyEvent(s, "session.compaction", { status: "started", seq: 3 });
    s = applyEvent(s, "session.compaction", { status: "failed", seq: 4 });
    expect(s.compacting).toBe(false);
    // a missed terminal is backstopped by an ended lifecycle
    s = applyEvent(s, "session.compaction", { status: "started", seq: 5 });
    expect(s.compacting).toBe(true);
    s = applyEvent(s, "status.update", { run_state: "idle", lifecycle: "ended", seq: 6 });
    expect(s.compacting).toBe(false);
  });

  test("session.undone drops the popped bubbles from the render list", () => {
    let s = emptySessionState();
    s = applyEvent(s, "message.user", { message_id: "m_u1", seq: 1, text: "a" });
    s = applyEvent(s, "message.start", { message_id: "m_a1", seq: 2 });
    s = applyEvent(s, "message.complete", { message_id: "m_a1", seq: 3 });
    s = applyEvent(s, "message.user", { message_id: "m_u2", seq: 4, text: "b" });
    s = applyEvent(s, "message.start", { message_id: "m_a2", seq: 5 });
    s = applyEvent(s, "message.complete", { message_id: "m_a2", seq: 6 });
    expect(s.messages.map((m) => m.id)).toEqual(["m_u1", "m_a1", "m_u2", "m_a2"]);
    // Transient: no seq on the payload — it must NOT be dropped by the watermark.
    s = applyEvent(s, "session.undone", { last_message_id: "m_a1", popped_message_ids: ["m_u2", "m_a2"] });
    expect(s.messages.map((m) => m.id)).toEqual(["m_u1", "m_a1"]);
  });

  test("session.cleared empties the render list in place, preserving the watermark", () => {
    let s = emptySessionState();
    s = applyEvent(s, "message.user", { message_id: "mu", seq: 1, text: "hi" });
    s = applyEvent(s, "message.start", { message_id: "ma", seq: 2 });
    s = applyEvent(s, "message.complete", { message_id: "ma", seq: 3 });
    expect(s.messages).toHaveLength(2);
    const before = s.lastSeq;
    // Transient (no seq) — must not be dropped by the watermark.
    s = applyEvent(s, "session.cleared", { session_id: "s1" });
    expect(s.messages).toHaveLength(0);
    // The daemon preserves the seq high-water on clear, so the watermark stays
    // (a cleared seq is never reissued — next turn's events stay above it).
    expect(s.lastSeq).toBe(before);
  });
});

describe("session.undo (T2 #6 — pop the last turn)", () => {
  test("undoSession sends session.undo and returns the daemon result", async () => {
    const { fake, client } = await connected({
      handlers: {
        "session.undo": () => ({
          last_message_id: "m_a1",
          popped_message_ids: ["m_u2", "m_a2"],
          files_rewound: false,
        }),
      },
    });
    await client.openSession("s1");
    const r = await client.undoSession("s1");
    expect(r.files_rewound).toBe(false);
    expect(r.popped_message_ids).toEqual(["m_u2", "m_a2"]);
    expect(fake.requests.find((q) => q.method === "session.undo")!.params).toEqual({ session_id: "s1" });
  });

  test("a live session.undone event drops the popped bubbles through the client dispatch", async () => {
    const { fake, client } = await connected();
    await client.openSession("s1");
    fake.pushEvent("message.user", { message_id: "m_u1", seq: 1, ts: 1, text: "first" }, "s1");
    fake.pushEvent("message.start", { message_id: "m_a1", seq: 2, ts: 2 }, "s1");
    fake.pushEvent("message.complete", { message_id: "m_a1", seq: 3, ts: 3 }, "s1");
    fake.pushEvent("message.user", { message_id: "m_u2", seq: 4, ts: 4, text: "second" }, "s1");
    fake.pushEvent("message.start", { message_id: "m_a2", seq: 5, ts: 5 }, "s1");
    fake.pushEvent("message.complete", { message_id: "m_a2", seq: 6, ts: 6 }, "s1");
    expect(client.getSessionState("s1")!.messages.map((m) => m.id)).toEqual(["m_u1", "m_a1", "m_u2", "m_a2"]);
    fake.pushEvent(
      "session.undone",
      { session_id: "s1", last_message_id: "m_a1", popped_message_ids: ["m_u2", "m_a2"] },
      "s1",
    );
    expect(client.getSessionState("s1")!.messages.map((m) => m.id)).toEqual(["m_u1", "m_a1"]);
  });

  test('the Undo affordance is feature-gated on hasFeature("undo")', async () => {
    const withUndo = await connected({ features: ["undo"] });
    expect(withUndo.client.hasFeature("undo")).toBe(true);
    const without = await connected({ features: [] });
    expect(without.client.hasFeature("undo")).toBe(false);
  });
});

describe("session.fork (T2 #3)", () => {
  const forkResult = {
    session_id: "s_fork",
    forked_from: { session_id: "s_src", message_id: "m_7" },
    full_context: true,
    warning: "file-history not copied",
  };

  test("a mid-point fork sends at_message_id and returns the daemon result", async () => {
    const { fake, client } = await connected({ handlers: { "session.fork": () => forkResult } });
    const r = await client.forkSession("s_src", { atMessageId: "m_7" });
    expect(r).toEqual(forkResult);
    const req = fake.requests.find((q) => q.method === "session.fork")!;
    expect(req.params).toEqual({ session_id: "s_src", at_message_id: "m_7" });
  });

  test("an end fork omits at_message_id (never sends an explicit null)", async () => {
    const { fake, client } = await connected({
      handlers: {
        "session.fork": () => ({ ...forkResult, forked_from: { session_id: "s_src", message_id: null } }),
      },
    });
    await client.forkSession("s_src");
    const req = fake.requests.find((q) => q.method === "session.fork")!;
    expect(req.params).toEqual({ session_id: "s_src" });
    expect("at_message_id" in req.params).toBe(false);
  });

  test("a no-fork rejection carries the structured reason through to the caller", async () => {
    // The fake delivers a real error FRAME (code + data), matching the
    // daemon's RpcMethodError path — so this pins the client's RpcError data
    // plumbing, and that isNoForkError branches on the reason.
    const { client } = await connected({
      handlers: {
        "session.fork": () => {
          const e = new Error('harness "opencode" cannot fork sessions') as Error & { data?: unknown };
          e.data = { reason: "fork_unsupported" };
          throw e;
        },
      },
    });
    const err = await client.forkSession("s_src").then(
      () => { throw new Error("expected rejection"); },
      (e: unknown) => e,
    );
    expect((err as { data?: { reason?: string } }).data?.reason).toBe("fork_unsupported");
    const { isNoForkError } = await import("../src/components/fork.js");
    expect(isNoForkError(err)).toBe(true);
  });
});

describe("session.main (assistant home)", () => {
  test("mainSession resolves the daemon's singleton id and attaches it", async () => {
    const { fake, client } = await connected({
      handlers: { "session.main": () => ({ session_id: "s_main" }) },
    });
    const id = await client.mainSession();
    expect(id).toBe("s_main");
    // session.main takes no params (the daemon owns the designation).
    expect(fake.requests.find((r) => r.method === "session.main")!.params).toEqual({});
    // Attached so a reconnect re-subscribes it (like a created session).
    expect(client.getSessionState("s_main")).toBeDefined();
  });
});

describe("session.clear (assistant reset)", () => {
  test("clearSession sends session.clear; a live session.cleared event empties the view", async () => {
    const { fake, client } = await connected();
    await client.openSession("s1");
    fake.pushEvent("message.user", { message_id: "mu", seq: 1, ts: 1, text: "hi" }, "s1");
    fake.pushEvent("message.start", { message_id: "ma", seq: 2, ts: 2 }, "s1");
    fake.pushEvent("message.complete", { message_id: "ma", seq: 3, ts: 3 }, "s1");
    expect(client.getSessionState("s1")!.messages).toHaveLength(2);

    await client.clearSession("s1");
    expect(fake.requests.find((r) => r.method === "session.clear")!.params).toEqual({ session_id: "s1" });

    // The daemon fans the transient cleared event to every subscriber (this
    // device included) — the view empties off THAT, not the ack.
    fake.pushEvent("session.cleared", { session_id: "s1" }, "s1");
    expect(client.getSessionState("s1")!.messages).toHaveLength(0);
  });
});

describe("session.model", () => {
  test("setModel sends session.model and returns the stored model", async () => {
    const { fake, client } = await connected({
      handlers: { "session.model": (p) => ({ model: p.model }) },
    });
    const m = await client.setModel("s1", "claude-opus-4-8");
    expect(m).toBe("claude-opus-4-8");
    expect(fake.requests.find((r) => r.method === "session.model")!.params).toEqual({
      session_id: "s1",
      model: "claude-opus-4-8",
    });
  });
});

describe("model.list default_model (daemon-owned new-session default)", () => {
  test("listModels emits the resolved default_model alongside the models list", async () => {
    const { client } = await connected({
      handlers: {
        "model.list": () => ({
          models: [{ id: "claude-opus-4-8", label: "Opus 4.8" }],
          default_model: "claude-opus-4-8",
        }),
      },
    });
    const seen: Array<{ models: number; def: string | null }> = [];
    client.on("models", (m, def) => seen.push({ models: m.length, def }));
    await client.listModels();
    expect(seen).toEqual([{ models: 1, def: "claude-opus-4-8" }]);
  });

  test("an absent default_model degrades to null (bare Default) — additive read", async () => {
    // A daemon that predates the field returns only `models`; the client must
    // read that as null, not crash or invent a default.
    const { client } = await connected({
      handlers: { "model.list": () => ({ models: [{ id: "m1", label: "One" }] }) },
    });
    let captured: string | null | undefined;
    client.on("models", (_m, def) => (captured = def));
    await client.listModels();
    expect(captured).toBe(null);
  });
});

describe("session.steer (T2 #6 — send while running)", () => {
  test("steerSession sends session.steer and renders an optimistic steered user bubble", async () => {
    const { fake, client } = await connected({
      handlers: { "session.steer": () => ({ message_id: "m_steer", seq: 5, ts: 5 }) },
    });
    await client.openSession("s1");
    await client.steerSession("s1", "actually, use TypeScript");
    const req = fake.requests.find((r) => r.method === "session.steer")!;
    expect(req.params).toMatchObject({ session_id: "s1", prompt: "actually, use TypeScript" });
    const msgs = client.getSessionState("s1")!.messages;
    expect(msgs).toHaveLength(1);
    expect(msgs[0]).toMatchObject({
      role: "user",
      text: "actually, use TypeScript",
      id: "m_steer",
      steered: true,
    });
  });
});
