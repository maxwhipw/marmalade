// clarify.test.ts — the clarify.respond wire shape from the webui client.
// The daemon contract (packages/protocol methods.ts ClarifyRespondParams):
// answers maps question text → chosen answer; response is freeform; sending
// NEITHER = dismissed. Empty maps must be omitted, not sent as {}.

import { describe, expect, test } from "vitest";
import { GatewayClient } from "../src/gateway/client.js";
import { FakeGateway, type FakeGatewayScript } from "./fake-gateway.js";

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

describe("submitClarifyResponse", () => {
  test("answers + freeform response ride the wire with the request_id", async () => {
    const { fake, client } = await connected();
    await client.submitClarifyResponse("s_1", "req_9", { "Which library?": "Ktor, OkHttp" }, "prefer PKCE");
    const sent = fake.requests.find((r) => r.method === "clarify.respond");
    expect(sent?.params).toEqual({
      session_id: "s_1",
      request_id: "req_9",
      answers: { "Which library?": "Ktor, OkHttp" },
      response: "prefer PKCE",
    });
  });

  test("dismiss sends neither answers nor response (empty map omitted)", async () => {
    const { fake, client } = await connected();
    await client.submitClarifyResponse("s_1", undefined, {}, undefined);
    const sent = fake.requests.find((r) => r.method === "clarify.respond");
    expect(sent?.params).toEqual({ session_id: "s_1" });
  });
});
