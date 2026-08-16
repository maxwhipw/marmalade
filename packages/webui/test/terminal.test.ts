// terminal.test.ts — the webui terminal glue: codec roundtrips + the client's
// terminal.* RPC wrappers and event routing (terminal.data/exit bypass the
// session watermark path — they're transient, attach-scoped, no session_id).

import { describe, expect, test } from "vitest";
import { makeEvent } from "@marmalade/protocol";
import { GatewayClient } from "../src/gateway/client.js";
import { FakeGateway, type FakeGatewayScript } from "./fake-gateway.js";
import { b64ToBytes, binaryToB64, textToB64 } from "../src/views/terminal-codec.js";

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
  fake.fireOpen();
  await flush();
  return { fake, client };
}

function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 0));
}

describe("terminal codec", () => {
  test("text → base64 → bytes roundtrips ASCII and control bytes", () => {
    const b64 = textToB64("echo hi\r\x03");
    expect(new TextDecoder().decode(b64ToBytes(b64))).toBe("echo hi\r\x03");
  });

  test("multi-byte unicode survives the input path (UTF-8, not charcodes)", () => {
    const b64 = textToB64("マーマレード 🍊");
    expect(new TextDecoder().decode(b64ToBytes(b64))).toBe("マーマレード 🍊");
  });

  test("binary path preserves raw bytes 0..255", () => {
    const bin = String.fromCharCode(...[0, 27, 91, 65, 255, 128]);
    expect([...b64ToBytes(binaryToB64(bin))]).toEqual([0, 27, 91, 65, 255, 128]);
  });

  test("large payloads chunk cleanly through btoa", () => {
    const big = "x".repeat(300_000);
    expect(new TextDecoder().decode(b64ToBytes(textToB64(big)))).toBe(big);
  });
});

describe("terminal feature gate + RPC wrappers", () => {
  test("hasFeature('terminal') derives from the negotiated hello", async () => {
    const { client } = await connected({ features: ["stable-ids", "terminal"] });
    expect(client.hasFeature("terminal")).toBe(true);
  });

  test("create/attach/input/resize/close/list send the wire shapes", async () => {
    const info = {
      terminal_id: "t_1", shell: "bash", cwd: "/home/user",
      cols: 80, rows: 24, pid: 42, created_at: 1, last_active: 1,
    };
    const { fake, client } = await connected({
      features: ["terminal"],
      handlers: {
        "terminal.create": () => ({ terminal: info }),
        "terminal.attach": () => ({ terminal: info, snapshot_b64: textToB64("scrollback$ ") }),
        "terminal.list": () => ({ terminals: [info] }),
        "terminal.input": () => ({}),
        "terminal.resize": () => ({ cols: 120, rows: 40 }),
        "terminal.close": () => ({ closed: true }),
        "terminal.detach": () => ({}),
      },
    });
    expect((await client.terminalCreate(80, 24)).terminal_id).toBe("t_1");
    const attach = await client.terminalAttach("t_1");
    expect(new TextDecoder().decode(b64ToBytes(attach.snapshot_b64))).toBe("scrollback$ ");
    expect((await client.terminalList())[0].pid).toBe(42);
    await client.terminalInput("t_1", textToB64("ls\r"));
    await client.terminalResize("t_1", 120, 40);
    await client.terminalClose("t_1");
    await client.terminalDetach("t_1");

    const methods = fake.requests.map((r) => r.method);
    for (const m of ["terminal.create", "terminal.attach", "terminal.list", "terminal.input", "terminal.resize", "terminal.close", "terminal.detach"]) {
      expect(methods).toContain(m);
    }
    const input = fake.requests.find((r) => r.method === "terminal.input")!;
    expect(input.params.terminal_id).toBe("t_1");
    expect(new TextDecoder().decode(b64ToBytes(String(input.params.data_b64)))).toBe("ls\r");
    const resize = fake.requests.find((r) => r.method === "terminal.resize")!;
    expect(resize.params).toMatchObject({ terminal_id: "t_1", cols: 120, rows: 40 });
  });

  test("terminal.data/exit route to the terminal listener, not the session path", async () => {
    const { fake, client } = await connected({ features: ["terminal"] });
    const seen: Array<{ type: string; payload: Record<string, unknown> }> = [];
    client.on("terminal", (type, payload) => seen.push({ type, payload }));

    fake.deliver(makeEvent("terminal.data", { terminal_id: "t_1", data_b64: textToB64("out") }));
    fake.deliver(makeEvent("terminal.exit", { terminal_id: "t_1", exit_code: 0 }));
    await flush();

    expect(seen).toHaveLength(2);
    expect(seen[0].type).toBe("terminal.data");
    expect(new TextDecoder().decode(b64ToBytes(String(seen[0].payload.data_b64)))).toBe("out");
    expect(seen[1]).toMatchObject({ type: "terminal.exit", payload: { terminal_id: "t_1", exit_code: 0 } });
    // No session state was created for a session-less transient event.
    expect(client.getSessionState("t_1")).toBeUndefined();
  });
});
