// marmalade — interactive terminal client for marmaladed.
//
// Connects to the gateway, opens a main session (state-preloaded, behavior-
// injected by the daemon), and streams a chat REPL. This is what makes the
// M4a daily-usable core usable by hand: `marmaladed` in one shell, `marmalade`
// in another.

import { createInterface } from "node:readline";
import { hostname } from "node:os";
import { JsonRpcResponse, JsonRpcEvent } from "@marmalade/protocol";
import { UiFenceFilter } from "./ui-render.js";
import { cronCommand, CRON_USAGE } from "./cron-cli.js";
import { usageCommand, USAGE_USAGE } from "./usage-cli.js";
import { secretMain } from "./secret-cli.js";

const URL = process.env.MARMALADE_GATEWAY ?? "ws://127.0.0.1:9130/api/ws?token=cli";

const C = {
  dim: (s: string) => `\x1b[2m${s}\x1b[0m`,
  bold: (s: string) => `\x1b[1m${s}\x1b[0m`,
  orange: (s: string) => `\x1b[38;5;208m${s}\x1b[0m`,
  green: (s: string) => `\x1b[32m${s}\x1b[0m`,
};

/** `marmalade pair` — mint a pairing setup code and render it as a QR for a
 *  new device (M2). Waits until the device claims (or the code expires). */
function pairCommand(): void {
  const ws = new WebSocket(URL);
  let nextId = 1;
  const calls = new Map<string, (result: any, error?: { message: string }) => void>();
  const call = (method: string, params: Record<string, unknown>): Promise<any> =>
    new Promise((resolve, reject) => {
      const id = String(nextId++);
      calls.set(id, (result, error) => (error ? reject(new Error(error.message)) : resolve(result)));
      ws.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
    });

  ws.onerror = () => {
    process.stdout.write(C.dim(`could not reach the gateway at ${URL} — is marmaladed running?\n`));
    process.exit(1);
  };

  ws.onmessage = async (ev: MessageEvent) => {
    const frame = JSON.parse(String(ev.data)) as any;
    if (frame.method === "event" && frame.params?.type === "gateway.ready") {
      try {
        await call("hello", {
          protocolVersion: 1,
          client: { name: "marmalade-cli", version: "0.1.0", capabilities: [], deviceId: `cli-${hostname()}`, platform: "cli" },
        });
        const before = new Set(
          ((await call("device.list", {})) as { devices: { device_id: string }[] }).devices.map((d) => d.device_id),
        );
        const started = await call("pairing.start", {});
        process.stdout.write(`\n${C.bold("Pair a new device")}\n`);
        process.stdout.write(`${C.dim("gateway:")} ${started.url}\n`);
        process.stdout.write(`${C.dim("expires:")} ${new Date(started.expires_at).toLocaleTimeString()}\n\n`);
        try {
          const { default: qr } = await import("qrcode-terminal");
          qr.generate(started.setup_code, { small: true }, (art: string) => process.stdout.write(art + "\n"));
        } catch { /* QR rendering is best-effort; the paste code below always prints */ }
        process.stdout.write(`${C.dim("or paste this setup code:")}\n${started.setup_code}\n\n`);
        process.stdout.write(C.dim("waiting for the device to claim… (Ctrl-C to stop)\n"));
        const poll = setInterval(async () => {
          try {
            if (Date.now() > started.expires_at) {
              clearInterval(poll);
              process.stdout.write(C.dim("setup code expired — run `marmalade pair` again.\n"));
              process.exit(1);
            }
            const now = (await call("device.list", {})) as { devices: { device_id: string; platform: string; paired: boolean }[] };
            const claimed = now.devices.find((d) => d.paired && !before.has(d.device_id));
            if (claimed) {
              clearInterval(poll);
              process.stdout.write(C.green(`✔ paired: ${claimed.device_id} (${claimed.platform})\n`));
              process.exit(0);
            }
          } catch { /* transient poll failure — keep waiting */ }
        }, 2000);
      } catch (e) {
        process.stdout.write(C.dim(`pairing failed: ${(e as Error).message}\n`));
        process.exit(1);
      }
      return;
    }
    const resp = JsonRpcResponse.safeParse(frame);
    if (resp.success) {
      const cb = calls.get(String(frame.id));
      if (cb) { calls.delete(String(frame.id)); cb((frame as any).result, (frame as any).error); }
    }
  };
}

/** One-shot RPC session for subcommands (`marmalade cron …` / `usage …`):
 *  hello, run the command, exit. All state lives in the daemon; this is
 *  pure plumbing. */
function subcommandMain(
  argv: string[],
  command: (argv: string[], call: (m: string, p: Record<string, unknown>) => Promise<any>, print: (line: string) => void) => Promise<number>,
  usageText: string,
): void {
  const ws = new WebSocket(URL);
  let nextId = 1;
  const calls = new Map<string, (result: any, error?: { message: string }) => void>();
  const call = (method: string, params: Record<string, unknown>): Promise<any> =>
    new Promise((resolve, reject) => {
      const id = String(nextId++);
      calls.set(id, (result, error) => (error ? reject(new Error(error.message)) : resolve(result)));
      ws.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
    });

  ws.onerror = () => {
    process.stdout.write(C.dim(`could not reach the gateway at ${URL} — is marmaladed running?\n`));
    process.exit(1);
  };

  ws.onmessage = async (ev: MessageEvent) => {
    const frame = JSON.parse(String(ev.data)) as any;
    if (frame.method === "event" && frame.params?.type === "gateway.ready") {
      try {
        await call("hello", {
          protocolVersion: 1,
          client: { name: "marmalade-cli", version: "0.1.0", capabilities: [], deviceId: `cli-${hostname()}`, platform: "cli" },
        });
        const code = await command(argv, call, (line) => process.stdout.write(line + "\n"));
        process.exit(code);
      } catch (e) {
        process.stdout.write(`${(e as Error).message}\n`);
        process.stdout.write(C.dim(usageText + "\n"));
        process.exit(1);
      }
      return;
    }
    const resp = JsonRpcResponse.safeParse(frame);
    if (resp.success) {
      const cb = calls.get(String(frame.id));
      if (cb) { calls.delete(String(frame.id)); cb((frame as any).result, (frame as any).error); }
    }
  };
}

function main(): void {
  const ws = new WebSocket(URL);
  let sessionId: string | null = null;
  let nextId = 1;
  let streaming = false;
  // Holds back ```marmalade-ui fences from the raw stream and renders them
  // as prompt-style text when they close (dynamic-ui step 5 — ui-render.ts).
  const uiFilter = new UiFenceFilter();

  const rl = createInterface({ input: process.stdin, output: process.stdout });
  let pending: string | null = null; // a line typed before the session was ready

  const send = (method: string, params: Record<string, unknown>) => {
    ws.send(JSON.stringify({ jsonrpc: "2.0", id: String(nextId++), method, params }));
  };

  const submit = (text: string) => send("prompt.submit", { session_id: sessionId, prompt: text });

  const prompt = () => {
    if (sessionId) rl.setPrompt(C.orange("you › "));
    rl.prompt();
  };

  ws.onopen = () => process.stdout.write(C.dim("connecting to marmalade…\n"));

  ws.onerror = () => {
    process.stdout.write(C.dim(`\ncould not reach the gateway at ${URL}\n`));
    process.stdout.write(C.dim("is marmaladed running?\n"));
    process.exit(1);
  };

  ws.onclose = () => {
    process.stdout.write(C.dim("\nconnection closed.\n"));
    process.exit(0);
  };

  ws.onmessage = (ev: MessageEvent) => {
    const frame = JSON.parse(String(ev.data)) as unknown;

    // Event notifications (streaming output).
    const asEvent = JsonRpcEvent.safeParse(frame);
    if (asEvent.success) {
      handleEvent(asEvent.data);
      return;
    }
    // Responses (session.create result, errors).
    const asResp = JsonRpcResponse.safeParse(frame);
    if (asResp.success && "result" in asResp.data) {
      const r = asResp.data.result as { session_id?: string } | undefined;
      if (r?.session_id && !sessionId) {
        sessionId = r.session_id;
        // Attach this connection to the main session's live event stream
        // (session.main ensures it exists+warm but does not subscribe us).
        send("session.resume", { session_id: sessionId });
        process.stdout.write(C.green(`● marmalade ready`) + C.dim(`  (${sessionId.slice(0, 12)}…)\n`));
        process.stdout.write(C.dim("type a message, or Ctrl-C to exit.\n\n"));
        if (pending) { const p = pending; pending = null; submit(p); } else prompt();
      }
      return;
    }
    if (asResp.success && "error" in asResp.data) {
      process.stdout.write(C.dim(`\n[error] ${asResp.data.error.message}\n`));
      prompt();
    }
  };

  function handleEvent(event: JsonRpcEvent): void {
    const { type, payload } = event.params;
    switch (type) {
      case "gateway.ready":
        // Upgrade to v1: declares who/where we are so the daemon stamps
        // message origins (P1) — then open the session.
        send("hello", {
          protocolVersion: 1,
          client: {
            name: "marmalade-cli", version: "0.1.0",
            capabilities: ["streaming", "stable-ids"],
            // Per-host device identity: puts this CLI on the device roster
            // (P3) and keys its seen cursors — two hosts' CLIs are two devices.
            deviceId: `cli-${hostname()}`,
            platform: "cli", tzOffset: -new Date().getTimezoneOffset(),
          },
        });
        // The CLI is an assistant surface: open THE daemon-managed main
        // session (always warm, same conversation everywhere) — never mint a
        // fresh session per REPL.
        send("session.main", {});
        break;
      case "message.delta":
        if (!streaming) {
          process.stdout.write("\n" + C.bold("marmalade › "));
          streaming = true;
        }
        process.stdout.write(uiFilter.feed(String((payload as { text?: string })?.text ?? "")));
        break;
      case "tool.start":
        process.stdout.write(C.dim(`\n  [tool: ${(payload as { name?: string })?.name}]\n`));
        break;
      case "message.complete":
        streaming = false;
        process.stdout.write(uiFilter.flush() + "\n\n");
        prompt();
        break;
      case "error":
        process.stdout.write(C.dim(`\n[agent error] ${JSON.stringify(payload)}\n`));
        break;
    }
  }

  rl.on("line", (line) => {
    const text = line.trim();
    if (!text) { if (sessionId) prompt(); return; }
    if (!sessionId) { pending = text; return; } // buffer until ready
    submit(text);
    // The next output arrives as message.delta events.
  });

  rl.on("SIGINT", () => {
    process.stdout.write(C.dim("\nbye.\n"));
    ws.close();
    process.exit(0);
  });
}

if (process.argv[2] === "pair") pairCommand();
else if (process.argv[2] === "cron") subcommandMain(process.argv.slice(3), cronCommand, CRON_USAGE);
else if (process.argv[2] === "usage") subcommandMain(process.argv.slice(3), usageCommand, USAGE_USAGE);
// `secret` deliberately does NOT open a gateway connection: it drives the
// keyring in-process off the daemon's config file, so it works while
// marmaladed is down and no secret value ever crosses a socket (secret-cli.ts).
else if (process.argv[2] === "secret") void secretMain(process.argv.slice(3));
else main();
