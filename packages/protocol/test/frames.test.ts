import { test } from "node:test";
import assert from "node:assert/strict";
import {
  JsonRpcRequest,
  JsonRpcResponse,
  JsonRpcEvent,
  ClientFrame,
  ServerFrame,
  MethodParamSchemas,
  HelloRequest,
  HelloParams,
  isSupportedProtocolVersion,
  makeResult,
  makeError,
  makeEvent,
  PROTOCOL_VERSION,
} from "../dist/index.js";
import {
  CLIENT_REQUESTS,
  SERVER_RESPONSES,
  SERVER_EVENTS,
  HELLO_REQUEST,
  BAD_FRAMES,
  UNKNOWN_METHOD_REQUEST,
} from "./fixtures.ts";

test("v1 accepts every captured client request", () => {
  for (const frame of CLIENT_REQUESTS) {
    assert.doesNotThrow(() => JsonRpcRequest.parse(frame), `request ${frame.method}`);
    assert.doesNotThrow(() => ClientFrame.parse(frame), `client frame ${frame.method}`);
  }
});

test("v1 accepts every captured server response", () => {
  for (const frame of SERVER_RESPONSES) {
    assert.doesNotThrow(() => JsonRpcResponse.parse(frame), `response ${frame.id}`);
    assert.doesNotThrow(() => ServerFrame.parse(frame), `server frame ${frame.id}`);
  }
});

test("v1 accepts every captured server event", () => {
  for (const frame of SERVER_EVENTS) {
    assert.doesNotThrow(() => JsonRpcEvent.parse(frame), `event ${frame.params.type}`);
    assert.doesNotThrow(() => ServerFrame.parse(frame), `server frame event`);
  }
});

test("known-method params validate against their schemas", () => {
  for (const frame of CLIENT_REQUESTS) {
    const schema = MethodParamSchemas[frame.method as keyof typeof MethodParamSchemas];
    if (schema) {
      assert.doesNotThrow(() => schema.parse(frame.params), `params for ${frame.method}`);
    }
  }
});

test("unknown methods still parse as valid request envelopes", () => {
  assert.doesNotThrow(() => JsonRpcRequest.parse(UNKNOWN_METHOD_REQUEST));
  assert.equal(MethodParamSchemas["config.set" as keyof typeof MethodParamSchemas], undefined);
});

test("malformed frames are rejected", () => {
  for (const bad of BAD_FRAMES) {
    assert.throws(() => JsonRpcRequest.parse(bad), `should reject ${JSON.stringify(bad)}`);
  }
});

test("hello handshake parses and binds a protocol version + token", () => {
  const parsed = HelloRequest.parse(HELLO_REQUEST);
  assert.equal(parsed.params.protocolVersion, 1);
  assert.ok(parsed.params.auth?.token, "the hello fixture carries a bearer token");
  assert.ok(parsed.params.client.capabilities.includes("streaming"));
  assert.ok(isSupportedProtocolVersion(parsed.params.protocolVersion));
  assert.ok(!isSupportedProtocolVersion(999));
});

test("hello capabilities default to [] when omitted", () => {
  const p = HelloParams.parse({
    protocolVersion: 1,
    client: { name: "cli", version: "0.0.1" },
  });
  assert.deepEqual(p.client.capabilities, []);
});

test("constructors emit spec-shaped frames", () => {
  assert.deepEqual(makeResult("1", { ok: true }), { jsonrpc: "2.0", id: "1", result: { ok: true } });
  assert.deepEqual(makeError("1", -32601, "nope"), {
    jsonrpc: "2.0",
    id: "1",
    error: { code: -32601, message: "nope" },
  });
  const ev = makeEvent("message.delta", { text: "hi" }, "s_1");
  assert.deepEqual(ev, {
    jsonrpc: "2.0",
    method: "event",
    params: { type: "message.delta", payload: { text: "hi" }, session_id: "s_1" },
  });
  // Round-trip: every constructed frame validates.
  assert.doesNotThrow(() => JsonRpcResponse.parse(makeResult("1", 1)));
  assert.doesNotThrow(() => JsonRpcEvent.parse(makeEvent("gateway.ready")));
});

test("PROTOCOL_VERSION is 1", () => {
  assert.equal(PROTOCOL_VERSION, 1);
});

test("an ERROR frame parses as an error response with error INTACT (union-order bug, 2026-07-18)", () => {
  // Regression: with the result branch first in the union, zod's optional
  // treatment of `result: z.unknown()` made error frames parse as result
  // responses with `error` STRIPPED — the webui resolved daemon errors as
  // undefined results instead of rejecting.
  const frame = makeError("7", -32602, 'harness "opencode" cannot fork sessions', {
    reason: "fork_unsupported",
  });
  const parsed = JsonRpcResponse.parse(frame);
  assert.ok("error" in parsed, "error key must survive the parse");
  const err = (parsed as { error: { code: number; message: string; data?: { reason?: string } } }).error;
  assert.equal(err.code, -32602);
  assert.equal(err.data?.reason, "fork_unsupported");
  // And a plain result frame still parses as a result response.
  const res = JsonRpcResponse.parse(makeResult("8", { ok: true }));
  assert.ok(!("error" in res));
});
