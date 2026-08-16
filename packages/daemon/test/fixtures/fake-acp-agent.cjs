#!/usr/bin/env node
// fake-acp-agent.cjs — a minimal ACP agent over stdio for the send-contract
// tests. Speaks just enough ndjson JSON-RPC for ClientSideConnection:
// initialize → session/new → session/prompt. Behavior is selected by a `mode`
// file in the cwd (the child env is a strict allowlist, so cwd is the only
// per-test channel):
//   ok         — answer session/prompt with end_turn after 150ms (a "turn")
//   fail-turn  — answer session/prompt with a JSON-RPC error
//   die        — exit(1) on session/prompt (child death mid-turn)
"use strict";
const { readFileSync } = require("node:fs");
const { join } = require("node:path");

let mode = "ok";
try { mode = readFileSync(join(process.cwd(), "mode"), "utf8").trim(); } catch {}

const send = (obj) => process.stdout.write(JSON.stringify(obj) + "\n");

let buf = "";
process.stdin.on("data", (chunk) => {
  buf += chunk.toString();
  let nl;
  while ((nl = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, nl).trim();
    buf = buf.slice(nl + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { continue; }
    if (msg.method === "initialize") {
      send({ jsonrpc: "2.0", id: msg.id, result: { protocolVersion: 1, agentCapabilities: {} } });
    } else if (msg.method === "session/new") {
      send({ jsonrpc: "2.0", id: msg.id, result: { sessionId: "fake-acp-session" } });
    } else if (msg.method === "session/prompt") {
      if (mode === "die") process.exit(1);
      if (mode === "fail-turn") {
        send({ jsonrpc: "2.0", id: msg.id, error: { code: -32000, message: "model backend unavailable" } });
      } else {
        setTimeout(() => {
          send({ jsonrpc: "2.0", id: msg.id, result: { stopReason: "end_turn" } });
        }, 150);
      }
    } else if (msg.id !== undefined) {
      send({ jsonrpc: "2.0", id: msg.id, error: { code: -32601, message: `unhandled: ${msg.method}` } });
    }
  }
});
