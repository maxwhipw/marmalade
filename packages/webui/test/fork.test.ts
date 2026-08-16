// fork.test.ts — pure helpers behind the session-fork UI (T2 #3).

import { describe, expect, test } from "vitest";
import { forkSuccessToast, isNoForkError, NO_FORK_WARNING } from "../src/components/fork.js";
import { FORK_UNSUPPORTED_REASON, type SessionForkResult } from "@marmalade/protocol";
import { RpcError } from "../src/gateway/client.js";

describe("isNoForkError", () => {
  test("true on the STRUCTURED reason — the contract, independent of wording", () => {
    // The daemon marks the no-fork rejection with error.data.reason; the human
    // message is free to reword without breaking the client's branch.
    expect(
      isNoForkError(new RpcError("anything at all", -32602, { reason: FORK_UNSUPPORTED_REASON })),
    ).toBe(true);
  });

  test("substring fallback still catches a pre-reason daemon's message", () => {
    expect(
      isNoForkError(
        new Error(
          'harness "opencode" cannot fork sessions — fall back to a new session seeded with copied text (tool/reasoning context is lost)',
        ),
      ),
    ).toBe(true);
  });

  test("false for the other fork rejections (they must NOT trigger the fallback)", () => {
    expect(isNoForkError(new Error("session has a turn in flight — fork after it completes"))).toBe(false);
    expect(isNoForkError(new Error("message m_x not found in session s_y"))).toBe(false);
    expect(isNoForkError(new Error("fork cut must be an assistant reply with harness state — pick an assistant message"))).toBe(false);
    expect(isNoForkError(new Error("session has no harness state to fork yet (no turn has run)"))).toBe(false);
    // A structured error with a DIFFERENT reason is not a no-fork signal.
    expect(isNoForkError(new RpcError("nope", -32602, { reason: "something_else" }))).toBe(false);
    expect(isNoForkError(null)).toBe(false);
  });
});

describe("forkSuccessToast", () => {
  const base = (over: Partial<SessionForkResult>): SessionForkResult => ({
    session_id: "s_new",
    forked_from: { session_id: "s_src", message_id: null },
    full_context: true,
    ...over,
  });

  test("end fork, no warning", () => {
    expect(forkSuccessToast(base({}))).toBe("Branched from the end of the chat into a new chat.");
  });

  test("mid-point fork names the message cut", () => {
    const t = forkSuccessToast(base({ forked_from: { session_id: "s_src", message_id: "m_42" } }));
    expect(t).toContain("from this message");
  });

  test("appends the daemon's soft warning when present", () => {
    const t = forkSuccessToast(base({ warning: "file-history not copied" }));
    expect(t).toContain("file-history not copied");
  });
});

test("NO_FORK_WARNING states the loss without promising a fallback the webui lacks", () => {
  // The webui has no seed-create branch — the warning must say branching is
  // unavailable, not imply a degraded branch happened.
  expect(NO_FORK_WARNING).toMatch(/unavailable/i);
  expect(NO_FORK_WARNING).toMatch(/tool calls and reasoning/i);
});
