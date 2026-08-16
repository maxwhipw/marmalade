// search.test.ts — message search (search.messages, additive 2026-07-24).
//
// Two halves, matching the rest of the suite's split:
//   - the pure presentation helpers (marker parsing, consecutive-session
//     grouping, scope/param building, the honest empty-state wording);
//   - the wire + feature gate through the digital-twin fake gateway (the frame
//     the client actually sends, and the fact that a daemon WITHOUT the
//     "search" feature never sees a frame at all).

import { describe, expect, test } from "vitest";
import {
  SNIPPET_CLOSE,
  SNIPPET_OPEN,
  type SearchHitWire,
  type SearchSessionWire,
  type WorkspaceWire,
} from "@marmalade/protocol";
import {
  buildScope,
  buildSearchParams,
  groupConsecutive,
  hitAgeLabel,
  isSearchable,
  parseSnippet,
  roleLabel,
  scopeSummary,
  unsearchedSummary,
} from "../src/components/search.js";
import { GatewayClient } from "../src/gateway/client.js";
import { FakeGateway, type FakeGatewayScript } from "./fake-gateway.js";

const mark = (s: string) => `${SNIPPET_OPEN}${s}${SNIPPET_CLOSE}`;

const hit = (over: Partial<SearchHitWire> & { session_id: string; message_id: string }): SearchHitWire => ({
  seq: 1,
  role: "user",
  ts: 1_000,
  snippet: "…snippet…",
  text: "full text",
  ...over,
});

const sessionWire = (over: Partial<SearchSessionWire> = {}): SearchSessionWire => ({
  title: "A session",
  workspace_id: null,
  archived: false,
  last_active: 1_000,
  ...over,
});

const ws = (id: string, name: string): WorkspaceWire => ({
  workspace_id: id,
  path: `/home/u/${id}`,
  name,
  emoji: null,
  created_at: 0,
  updated_at: 0,
  detection: { git_branch: null, has_claude_md: false, has_agents_md: false, memory_notes: 0 },
});

// ── Snippet markers ─────────────────────────────────────────────────────────

describe("parseSnippet", () => {
  test("splits on the private-use markers and strips them", () => {
    const parts = parseSnippet(`…merge the ${mark("seen_at")} stamp ${mark("monotonic")}ally…`);
    expect(parts).toEqual([
      { text: "…merge the ", match: false },
      { text: "seen_at", match: true },
      { text: " stamp ", match: false },
      { text: "monotonic", match: true },
      { text: "ally…", match: false },
    ]);
  });

  test("never leaks a marker into the rendered text", () => {
    const joined = parseSnippet(`a${SNIPPET_OPEN}b${SNIPPET_CLOSE}c`)
      .map((p) => p.text)
      .join("");
    expect(joined).toBe("abc");
    expect(joined).not.toContain(SNIPPET_OPEN);
    expect(joined).not.toContain(SNIPPET_CLOSE);
  });

  test("unbalanced markers still yield readable text", () => {
    expect(parseSnippet(`${SNIPPET_OPEN}open forever`)).toEqual([{ text: "open forever", match: true }]);
    expect(parseSnippet(`stray close${SNIPPET_CLOSE}`)).toEqual([{ text: "stray close", match: false }]);
  });

  test("a snippet with no match markers is one plain run", () => {
    expect(parseSnippet("plain")).toEqual([{ text: "plain", match: false }]);
  });

  test("empty snippet yields no parts (nothing to draw)", () => {
    expect(parseSnippet("")).toEqual([]);
  });
});

// ── Consecutive-session grouping ────────────────────────────────────────────

describe("groupConsecutive", () => {
  const sessions: Record<string, SearchSessionWire> = {
    s1: sessionWire({ title: "One" }),
    s2: sessionWire({ title: "Two" }),
  };

  test("consecutive hits from the same session share one group", () => {
    const groups = groupConsecutive(
      [
        hit({ session_id: "s1", message_id: "m1" }),
        hit({ session_id: "s1", message_id: "m2" }),
        hit({ session_id: "s2", message_id: "m3" }),
      ],
      sessions,
    );
    expect(groups).toHaveLength(2);
    expect(groups[0].hits.map((h) => h.message_id)).toEqual(["m1", "m2"]);
    expect(groups[0].session?.title).toBe("One");
    expect(groups[1].hits.map((h) => h.message_id)).toEqual(["m3"]);
  });

  test("a session that reappears later gets a SECOND group — ranking is never reordered", () => {
    const groups = groupConsecutive(
      [
        hit({ session_id: "s1", message_id: "m1" }),
        hit({ session_id: "s2", message_id: "m2" }),
        hit({ session_id: "s1", message_id: "m3" }),
      ],
      sessions,
    );
    expect(groups.map((g) => g.sessionId)).toEqual(["s1", "s2", "s1"]);
    // The flat order the daemon ranked is preserved exactly.
    expect(groups.flatMap((g) => g.hits.map((h) => h.message_id))).toEqual(["m1", "m2", "m3"]);
  });

  test("a hit whose session is missing from the map still renders (session null)", () => {
    const groups = groupConsecutive([hit({ session_id: "ghost", message_id: "m1" })], sessions);
    expect(groups[0].session).toBeNull();
    expect(groups[0].hits).toHaveLength(1);
  });

  test("no hits, no groups", () => {
    expect(groupConsecutive([], sessions)).toEqual([]);
  });
});

// ── Scope + params ──────────────────────────────────────────────────────────

describe("buildScope", () => {
  test("no selection = undefined (everywhere), not an empty object", () => {
    expect(buildScope({ workspaceIds: [], quickChats: false })).toBeUndefined();
  });

  test("workspaces only", () => {
    expect(buildScope({ workspaceIds: ["w1", "w2"], quickChats: false })).toEqual({
      workspace_ids: ["w1", "w2"],
    });
  });

  test("quick chats is its own axis and ORs with workspaces", () => {
    expect(buildScope({ workspaceIds: ["w1"], quickChats: true })).toEqual({
      workspace_ids: ["w1"],
      quick_chats: true,
    });
    expect(buildScope({ workspaceIds: [], quickChats: true })).toEqual({ quick_chats: true });
  });

  test("find-in-conversation is scope-of-one through session_ids", () => {
    expect(buildScope({ workspaceIds: [], quickChats: false, sessionIds: ["s1"] })).toEqual({
      session_ids: ["s1"],
    });
  });

  test("an empty session_ids array is omitted (it would mean 'nothing')", () => {
    expect(buildScope({ workspaceIds: [], quickChats: false, sessionIds: [] })).toBeUndefined();
  });
});

describe("buildSearchParams", () => {
  test("trims the query, omits an empty scope, carries sort/paging", () => {
    expect(
      buildSearchParams("  seen_at  ", { workspaceIds: [], quickChats: false }, {
        sort: "recent",
        includeArchived: true,
        limit: 20,
        offset: 40,
      }),
    ).toEqual({
      query: "seen_at",
      include_archived: true,
      sort: "recent",
      limit: 20,
      offset: 40,
    });
  });

  test("a scoped search carries the resolved scope object", () => {
    const p = buildSearchParams("badge", { workspaceIds: ["w1"], quickChats: true }, {
      sort: "rank",
      includeArchived: false,
      limit: 20,
      offset: 0,
    });
    expect(p.scope).toEqual({ workspace_ids: ["w1"], quick_chats: true });
    expect(p.include_archived).toBe(false);
  });
});

describe("isSearchable", () => {
  test("the daemon's 2-char floor is enforced client-side, whitespace not counted", () => {
    expect(isSearchable("a")).toBe(false);
    expect(isSearchable(" a ")).toBe(false);
    expect(isSearchable("ab")).toBe(true);
  });
});

// ── Honest wording ──────────────────────────────────────────────────────────

describe("scopeSummary / unsearchedSummary", () => {
  const workspaces = [ws("w1", "Marmalade Client"), ws("w2", "Finance"), ws("w3", "Agent Wiki")];

  test("everywhere has nothing to disclaim", () => {
    const sel = { workspaceIds: [], quickChats: false };
    expect(scopeSummary(sel, workspaces)).toBe("everywhere");
    expect(unsearchedSummary(sel, workspaces)).toBeNull();
  });

  test("a narrow scope names what it didn't search", () => {
    const sel = { workspaceIds: ["w2"], quickChats: false };
    expect(scopeSummary(sel, workspaces)).toBe("Finance");
    expect(unsearchedSummary(sel, workspaces)).toBe("2 workspaces and your quick chats weren't searched.");
  });

  test("quick chats selected drops it from the disclaimer", () => {
    const sel = { workspaceIds: ["w1", "w2"], quickChats: true };
    // Quick chats is a scope like any other, so three selections collapse.
    expect(scopeSummary(sel, workspaces)).toBe("3 scopes");
    expect(scopeSummary({ workspaceIds: ["w1"], quickChats: true }, workspaces)).toBe(
      "Marmalade Client and quick chats",
    );
    expect(unsearchedSummary(sel, workspaces)).toBe("1 workspace wasn't searched.");
  });

  test("three or more scopes collapse to a count", () => {
    expect(scopeSummary({ workspaceIds: ["w1", "w2", "w3"], quickChats: false }, workspaces)).toBe("3 scopes");
  });

  test("everything selected has nothing left to disclaim", () => {
    const sel = { workspaceIds: ["w1", "w2", "w3"], quickChats: true };
    expect(unsearchedSummary(sel, workspaces)).toBeNull();
  });

  test("scope-of-one speaks for itself", () => {
    const sel = { workspaceIds: [], quickChats: false, sessionIds: ["s1"] };
    expect(scopeSummary(sel, workspaces)).toBe("this conversation");
    expect(unsearchedSummary(sel, workspaces)).toBe("Other sessions weren't searched.");
  });
});

describe("row labels", () => {
  test("roles read as people, not wire tokens", () => {
    expect(roleLabel("user")).toBe("You");
    expect(roleLabel("assistant")).toBe("Agent");
  });

  test("ages compact from minutes to weeks", () => {
    const now = 1_000_000_000;
    expect(hitAgeLabel(now, now)).toBe("now");
    expect(hitAgeLabel(now - 5 * 60_000, now)).toBe("5m");
    expect(hitAgeLabel(now - 3 * 3_600_000, now)).toBe("3h");
    expect(hitAgeLabel(now - 2 * 86_400_000, now)).toBe("2d");
    expect(hitAgeLabel(now - 21 * 86_400_000, now)).toBe("3w");
    expect(hitAgeLabel(now + 5_000, now)).toBe("now"); // clock skew never reads negative
  });
});

// ── Wire + feature gate ─────────────────────────────────────────────────────

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
  await new Promise((r) => setTimeout(r, 0));
  return { fake, client };
}

describe("search.messages wire", () => {
  const emptyResult = { total: 0, hits: [], sessions: {} };

  test("the built params are sent verbatim and the result comes back typed", async () => {
    const { fake, client } = await connected({
      features: ["stable-ids", "search"],
      handlers: {
        "search.messages": () => ({
          total: 1,
          hits: [hit({ session_id: "s1", message_id: "m1", snippet: mark("badge") })],
          sessions: { s1: sessionWire({ title: "Fix the badge" }) },
        }),
      },
    });
    const r = await client.searchMessages(
      buildSearchParams("badge", { workspaceIds: ["w1"], quickChats: false }, {
        sort: "rank",
        includeArchived: false,
        limit: 20,
        offset: 0,
      }),
    );
    expect(r.total).toBe(1);
    expect(r.sessions.s1.title).toBe("Fix the badge");
    expect(fake.requests.find((q) => q.method === "search.messages")!.params).toEqual({
      query: "badge",
      scope: { workspace_ids: ["w1"] },
      include_archived: false,
      sort: "rank",
      limit: 20,
      offset: 0,
    });
  });

  test("find-in-conversation sends scope.session_ids and nothing else", async () => {
    const { fake, client } = await connected({
      features: ["search"],
      handlers: { "search.messages": () => emptyResult },
    });
    await client.searchMessages(
      buildSearchParams("boundary", { workspaceIds: [], quickChats: false, sessionIds: ["s7"] }, {
        sort: "recent",
        includeArchived: true,
        limit: 50,
        offset: 0,
      }),
    );
    expect(fake.requests.find((q) => q.method === "search.messages")!.params.scope).toEqual({
      session_ids: ["s7"],
    });
  });

  test("FEATURE GATE: a daemon without \"search\" never sees a frame", async () => {
    const { fake, client } = await connected({ features: ["stable-ids", "subscribe"] });
    expect(client.hasFeature("search")).toBe(false);
    await expect(client.searchMessages({ query: "badge" })).rejects.toThrow(/no message search/);
    expect(fake.requests.some((q) => q.method === "search.messages")).toBe(false);
  });

  test("a daemon-side rejection surfaces as a rejection (no silent empty page)", async () => {
    const { client } = await connected({
      features: ["search"],
      handlers: {
        "search.messages": () => {
          throw new Error("search not configured");
        },
      },
    });
    await expect(client.searchMessages({ query: "badge" })).rejects.toThrow(/not configured/);
  });
});
