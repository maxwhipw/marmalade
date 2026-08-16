// opencode-adapter.ts — the SECOND concrete harness adapter (Decision 4, M3).
//
// Drives `opencode acp` as an ACP subprocess (Zed's Agent Client Protocol) via
// the official @agentclientprotocol/sdk client. Implements the SAME
// HarnessAdapter interface as ClaudeCodeAdapter and emits the SAME gateway
// events (via acp-normalize) — so the router is genuinely harness-neutral.
//
// No generic AcpAdapter base yet (simp-H2): this is a concrete adapter. Extract
// the shared ACP client only when Codex arrives as the second ACP consumer.
//
// Auth: OpenCode authenticates itself (BYO per harness) — marmalade passes only
// the allowlist env from policy, never a token.

import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { Readable, Writable } from "node:stream";
import { ClientSideConnection, ndJsonStream, type Agent, type Client } from "@agentclientprotocol/sdk";
import type { SessionSpec } from "./policy.js";
import { buildChildEnv } from "./policy.js";
import { normalizeAcp } from "./acp-normalize.js";
import type { HarnessAdapter, HarnessSession, SpawnOptions, AdapterCallbacks } from "./adapter.js";

export interface OpenCodeAdapterConfig {
  path: string; // PATH for the allowlist child env
  meteredKey?: string;
  /** The opencode executable (default: "opencode" on PATH). */
  opencodeBin?: string;
  log?: (line: string) => void;
}

export class OpenCodeAdapter implements HarnessAdapter {
  readonly name = "opencode";
  constructor(private cfg: OpenCodeAdapterConfig) {}

  spawn(spec: SessionSpec, opts: SpawnOptions, cb: AdapterCallbacks): HarnessSession {
    const log = this.cfg.log ?? (() => {});
    const env = buildChildEnv(spec, { path: this.cfg.path, meteredKey: this.cfg.meteredKey });

    // Mutable holder (not a reassigned local) so token counts written by the
    // async sessionUpdate handler are visible when a turn resolves.
    const usageAccum = { inputTokens: 0, outputTokens: 0 };

    const child: ChildProcessWithoutNullStreams = spawn(
      this.cfg.opencodeBin ?? "opencode",
      ["acp", "--cwd", spec.cwd],
      { cwd: spec.cwd, env, stdio: ["pipe", "pipe", "pipe"] },
    );
    let dead = false;
    // One failure report per session, total (send contract): the router treats
    // onError as terminal (error event + ended + removed from live), and a
    // single failure surfaces on several paths at once (conn.prompt rejects
    // AND the child 'exit' fires). First reporter wins; the rest just log.
    let errorReported = false;
    const reportError = (kind: string, message: string): void => {
      if (errorReported) { log(`[opencode-adapter] suppressed duplicate error (${kind}): ${message}`); return; }
      errorReported = true;
      cb.onError?.(kind, message);
    };
    child.stderr.on("data", (d) => log(`[opencode acp stderr] ${d.toString().trim()}`));
    child.on("error", (e) => { dead = true; log(`[opencode-adapter] spawn error: ${e.message}`); reportError("spawn_failed", e.message); });
    child.on("exit", (code, signal) => {
      // A dead child otherwise leaves ensureReady()'s promise unsettled forever
      // and every send() hanging (R2). Mark dead + surface it.
      if (!dead) { dead = true; reportError("child_exited", `opencode acp exited (code=${code} signal=${signal})`); }
    });

    // ndJsonStream(output=WritableStream to child stdin, input=ReadableStream from child stdout).
    const toChild = Writable.toWeb(child.stdin) as WritableStream<Uint8Array>;
    const fromChild = Readable.toWeb(child.stdout) as ReadableStream<Uint8Array>;
    const stream = ndJsonStream(toChild, fromChild);

    // Our Client handler: receive streaming updates + auto-approve (M1 parity).
    const clientHandler = (_agent: Agent): Client => ({
      async sessionUpdate(params) {
        cb.onActivity();
        const norm = normalizeAcp(params.update as never, opts.daemonSessionId);
        for (const ev of norm.events) cb.onEvent(ev);
        if (norm.usage) {
          usageAccum.inputTokens = norm.usage.inputTokens;
          usageAccum.outputTokens = norm.usage.outputTokens;
        }
      },
      async requestPermission(params) {
        // M2: route through the router's requestApproval seam (same one the
        // Claude adapter uses), then map the decision back onto the ACP
        // options the agent offered. No callback = allow with log (M1/tests).
        const p = params as {
          options?: Array<{ kind?: string; optionId?: string; name?: string }>;
          toolCall?: { title?: string; kind?: string; rawInput?: unknown };
        };
        const options = p.options;
        const pickAllow = () => options?.find((o) => o.kind?.startsWith("allow")) ?? options?.[0];
        const pickReject = () =>
          options?.find((o) => o.kind?.includes("reject") || o.kind?.includes("deny")) ?? options?.[options.length - 1];
        if (!cb.requestApproval) {
          log(`[approval] auto-approved (opencode) ${JSON.stringify(params).slice(0, 120)}`);
          return { outcome: { outcome: "selected", optionId: pickAllow()?.optionId ?? "allow" } } as never;
        }
        const decision = await cb.requestApproval({
          toolName: p.toolCall?.kind ?? p.toolCall?.title ?? "tool",
          input: p.toolCall?.rawInput ?? p.toolCall ?? {},
          ...(p.toolCall?.title ? { description: p.toolCall.title } : {}),
        });
        const picked = decision.behavior === "allow" ? pickAllow() : pickReject();
        if (decision.behavior === "deny" && !picked) log(`[approval] deny requested but no reject option offered — falling back to last option`);
        return { outcome: { outcome: "selected", optionId: picked?.optionId ?? (decision.behavior === "allow" ? "allow" : "reject") } } as never;
      },
    });

    const conn = new ClientSideConnection(clientHandler, stream);
    let acpSessionId: string | null = null;
    let ready: Promise<void> | null = null;

    const INIT_TIMEOUT_MS = 30_000;
    const ensureReady = async (): Promise<void> => {
      if (ready) return ready;
      ready = (async () => {
        if (dead) throw new Error("opencode child is not running");
        // Bound the handshake — a dead/wedged child otherwise never settles (R2).
        const withTimeout = <T>(p: Promise<T>, what: string) =>
          Promise.race([p, new Promise<never>((_, rej) => setTimeout(() => rej(new Error(`opencode ${what} timed out after ${INIT_TIMEOUT_MS}ms`)), INIT_TIMEOUT_MS).unref?.())]);
        await withTimeout(conn.initialize({
          protocolVersion: 1,
          clientCapabilities: { fs: { readTextFile: false, writeTextFile: false } },
        } as never), "initialize");
        const res = (await withTimeout(conn.newSession({ cwd: spec.cwd, mcpServers: [] } as never), "newSession")) as { sessionId: string };
        acpSessionId = res.sessionId;
        cb.onHarnessSession(acpSessionId);
        log(`[opencode-adapter] session ready ${acpSessionId}`);
      })().catch((e) => {
        ready = null; // don't cache a failed handshake — allow a later retry
        reportError("init_failed", (e as Error).message);
        throw e;
      });
      return ready;
    };

    const onTurnComplete = (resp: { stopReason?: string }): void => {
      // Turn complete → the same message.complete + result the Claude path emits.
      cb.onEvent({ jsonrpc: "2.0", method: "event", params: { type: "message.complete", payload: { stopReason: resp.stopReason }, session_id: opts.daemonSessionId } });
      cb.onResult(
        {
          subtype: resp.stopReason ?? "success",
          isError: false,
          totalCostUsd: 0, // ACP usage is token-based; cost stays 0 (meter uses tokens)
          inputTokens: usageAccum.inputTokens,
          outputTokens: usageAccum.outputTokens,
        },
        "opencode",
      );
    };

    // Serialize sends per session: overlapping conn.prompt calls corrupt usage
    // accounting + turn framing (R8). Each send waits for the previous.
    let sendChain: Promise<void> = Promise.resolve();

    return {
      // Send contract (adapter.ts): queue-and-return. Accept = the turn is
      // chained; the returned promise never carries turn-time failures (the
      // Claude path resolves on queue-push — same RPC, same latency). A turn
      // failure reports ONCE via reportError (ensureReady reports init_failed
      // itself; anything else is the prompt call failing), never as a send
      // rejection on top of the error event.
      async send(prompt: string) {
        if (dead) throw new Error("opencode child is not running"); // cannot accept
        const run = sendChain.then(async () => {
          await ensureReady();
          usageAccum.inputTokens = 0;
          usageAccum.outputTokens = 0;
          const resp = (await conn.prompt({
            sessionId: acpSessionId!,
            prompt: [{ type: "text", text: prompt }],
          } as never)) as { stopReason?: string };
          onTurnComplete(resp);
        });
        sendChain = run.catch(() => {}); // keep the chain alive on failure
        run.catch((e) => reportError("turn_failed", (e as Error).message));
      },
      async interrupt() {
        // Prefer a graceful ACP cancel; fall back to signalling the child.
        try { await (conn as unknown as { cancel?: (p: unknown) => Promise<void> }).cancel?.({ sessionId: acpSessionId }); }
        catch { /* fall through */ }
        if (dead) return;
        child.kill("SIGINT");
      },
      async stop() {
        // Deliberate stop: mark dead FIRST so the 'exit' handler doesn't
        // report the SIGTERM we asked for as a child_exited failure.
        dead = true;
        child.kill("SIGTERM");
      },
    };
  }
}
