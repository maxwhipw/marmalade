// search-archive.test.ts — the ARCHIVE CORPUS in the webui (additive
// 2026-07-28): the pre-daemon Claude Code history, searched through the same
// search.messages method with scope.corpus="archive" and read through the
// read-only search.archive viewer.
//
// Same split as search.test.ts: the pure helpers that decide what the view may
// draw (corpus param building, "is this hit openable", transcript paging), and
// the wire + feature gate through the digital-twin fake gateway.
//
// The invariant this file protects: LIVE mode must stay byte-identical to the
// frame that shipped before the archive existed (no `corpus` field at all),
// and an archive hit must never offer a live open — the daemon does not know
// those ids as sessions.

import { describe, expect, test } from "vitest";
import type { SearchArchiveMessageWire, SearchSessionWire } from "@marmalade/protocol";
import {
  appendArchivePage,
  archiveSessionLabel,
  buildScope,
  buildSearchParams,
  groupConsecutive,
  isArchiveSession,
} from "../src/components/search.js";
import { GatewayClient } from "../src/gateway/client.js";
import { FakeGateway, type FakeGatewayScript } from "./fake-gateway.js";

const sessionWire = (over: Partial<SearchSessionWire> = {}): SearchSessionWire => ({
  title: "A session",
  workspace_id: null,
  archived: false,
  last_active: 1_000,
  ...over,
});

const msg = (ordinal: number, over: Partial<SearchArchiveMessageWire> = {}): SearchArchiveMessageWire => ({
  ordinal,
  role: ordinal % 2 === 0 ? "user" : "assistant",
  ts: 1_000 + ordinal,
  text: `message ${ordinal}`,
  ...over,
});

// ── Corpus in the scope ─────────────────────────────────────────────────────

describe("buildScope / buildSearchParams — corpus", () => {
  test("live mode carries NO corpus field (byte-identical to the pre-archive frame)", () => {
    expect(buildScope({ workspaceIds: [], quickChats: false })).toBeUndefined();
    expect(buildScope({ workspaceIds: ["w1"], quickChats: false })).toEqual({ workspace_ids: ["w1"] });
    const p = buildSearchParams("badge", { workspaceIds: [], quickChats: false }, {
      sort: "rank",
      includeArchived: false,
      limit: 20,
      offset: 0,
    });
    expect(p).toEqual({ query: "badge", include_archived: false, sort: "rank", limit: 20, offset: 0 });
    expect("scope" in p).toBe(false);
  });

  test("an explicit corpus:undefined is still no corpus field", () => {
    expect(buildScope({ workspaceIds: [], quickChats: false, corpus: undefined })).toBeUndefined();
  });

  test("archive mode alone produces a scope that is ONLY the corpus switch", () => {
    expect(buildScope({ workspaceIds: [], quickChats: false, corpus: "archive" })).toEqual({
      corpus: "archive",
    });
  });

  test("workspace and quick-chat scoping still OR on top of the archive corpus", () => {
    expect(buildScope({ workspaceIds: ["w1"], quickChats: true, corpus: "archive" })).toEqual({
      workspace_ids: ["w1"],
      quick_chats: true,
      corpus: "archive",
    });
  });

  test("the archive page params carry the corpus through unchanged", () => {
    const p = buildSearchParams("seen_at", { workspaceIds: ["w2"], quickChats: false, corpus: "archive" }, {
      sort: "recent",
      includeArchived: false,
      limit: 20,
      offset: 20,
    });
    expect(p.scope).toEqual({ workspace_ids: ["w2"], corpus: "archive" });
    expect(p.offset).toBe(20);
  });
});

// ── What an archive hit may offer ───────────────────────────────────────────

describe("isArchiveSession", () => {
  test("only a session stamped corpus:archive is archive", () => {
    expect(isArchiveSession(sessionWire({ corpus: "archive" }))).toBe(true);
    expect(isArchiveSession(sessionWire())).toBe(false);
    // An ARCHIVED live session is a different thing entirely — it can still be
    // opened, so it must never take the archive branch.
    expect(isArchiveSession(sessionWire({ archived: true }))).toBe(false);
  });

  test("a hit whose session the page didn't carry is treated as live-shaped, not archive", () => {
    expect(isArchiveSession(null)).toBe(false);
    expect(isArchiveSession(undefined)).toBe(false);
  });

  test("grouping carries the corpus onto the header the view renders", () => {
    const sessions: Record<string, SearchSessionWire> = {
      a1: sessionWire({ title: "Old work", corpus: "archive" }),
      s1: sessionWire({ title: "Live work" }),
    };
    const groups = groupConsecutive(
      [
        { session_id: "a1", message_id: "m1", seq: 0, role: "user", ts: 1, snippet: "x", text: "x" },
        { session_id: "s1", message_id: "m2", seq: 0, role: "user", ts: 2, snippet: "y", text: "y" },
      ],
      sessions,
    );
    expect(groups.map((g) => isArchiveSession(g.session))).toEqual([true, false]);
  });
});

describe("archiveSessionLabel", () => {
  test("prefers the extracted title", () => {
    expect(archiveSessionLabel({ title: "Fix the badge", cwd: "/home/u/proj" })).toBe("Fix the badge");
  });

  test("falls back to the cwd leaf — most old conversations were never titled", () => {
    expect(archiveSessionLabel({ title: null, cwd: "/home/u/coding/marmalade" })).toBe("marmalade");
    expect(archiveSessionLabel({ title: "   ", cwd: "/home/u/coding/marmalade/" })).toBe("marmalade");
  });

  test("a root cwd still yields something to draw", () => {
    expect(archiveSessionLabel({ title: null, cwd: "/" })).toBe("/");
  });
});

// ── Viewer paging ───────────────────────────────────────────────────────────

describe("appendArchivePage", () => {
  test("appends the next page in ordinal order", () => {
    const merged = appendArchivePage([msg(0), msg(1)], [msg(2), msg(3)]);
    expect(merged.map((m) => m.ordinal)).toEqual([0, 1, 2, 3]);
  });

  test("a re-fetched page never doubles a message", () => {
    const merged = appendArchivePage([msg(0), msg(1)], [msg(1), msg(2)]);
    expect(merged.map((m) => m.ordinal)).toEqual([0, 1, 2]);
  });

  test("an out-of-order page is sorted back into the transcript", () => {
    const merged = appendArchivePage([msg(2)], [msg(0), msg(1)]);
    expect(merged.map((m) => m.ordinal)).toEqual([0, 1, 2]);
  });

  test("the newer copy of an ordinal wins (a re-read is authoritative)", () => {
    const merged = appendArchivePage([msg(0, { text: "stale" })], [msg(0, { text: "fresh" })]);
    expect(merged).toEqual([msg(0, { text: "fresh" })]);
  });

  test("an empty page leaves the transcript alone", () => {
    expect(appendArchivePage([msg(0)], [])).toEqual([msg(0)]);
    expect(appendArchivePage([], [])).toEqual([]);
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

const archiveResult = {
  session: { title: "Old work", cwd: "/home/u/coding/marmalade", last_active: 5_000, message_count: 3 },
  total: 3,
  messages: [msg(0), msg(1), msg(2)],
};

describe("archive corpus wire", () => {
  test("archive mode sends scope.corpus and the hits come back stamped archive", async () => {
    const { fake, client } = await connected({
      features: ["search", "search_archive"],
      handlers: {
        "search.messages": () => ({
          total: 1,
          hits: [
            { session_id: "arch-uuid", message_id: "arch-uuid:4", seq: 4, role: "user", ts: 9, snippet: "s", text: "t" },
          ],
          sessions: { "arch-uuid": sessionWire({ title: "Old work", corpus: "archive" }) },
        }),
      },
    });
    const r = await client.searchMessages(
      buildSearchParams("badge", { workspaceIds: [], quickChats: false, corpus: "archive" }, {
        sort: "rank",
        includeArchived: false,
        limit: 20,
        offset: 0,
      }),
    );
    expect(fake.requests.find((q) => q.method === "search.messages")!.params.scope).toEqual({
      corpus: "archive",
    });
    expect(isArchiveSession(r.sessions["arch-uuid"])).toBe(true);
    // No reply_text in the archive corpus — replyTo is live-corpus machinery.
    expect(r.hits[0].reply_text).toBeUndefined();
  });

  test("live mode sends a frame with no corpus key at all", async () => {
    const { fake, client } = await connected({
      features: ["search", "search_archive"],
      handlers: { "search.messages": () => ({ total: 0, hits: [], sessions: {} }) },
    });
    await client.searchMessages(
      buildSearchParams("badge", { workspaceIds: ["w1"], quickChats: false }, {
        sort: "rank",
        includeArchived: false,
        limit: 20,
        offset: 0,
      }),
    );
    const sent = fake.requests.find((q) => q.method === "search.messages")!.params;
    expect(sent.scope).toEqual({ workspace_ids: ["w1"] });
    expect(JSON.stringify(sent)).not.toContain("corpus");
  });

  test("search.archive fetches a transcript page and types it", async () => {
    const { fake, client } = await connected({
      features: ["search", "search_archive"],
      handlers: { "search.archive": () => archiveResult },
    });
    const r = await client.searchArchive({ session_id: "arch-uuid", limit: 100, offset: 0 });
    expect(r.session.cwd).toBe("/home/u/coding/marmalade");
    expect(r.total).toBe(3);
    expect(r.messages.map((m) => m.ordinal)).toEqual([0, 1, 2]);
    expect(fake.requests.find((q) => q.method === "search.archive")!.params).toEqual({
      session_id: "arch-uuid",
      limit: 100,
      offset: 0,
    });
  });

  test("paging asks for the next offset and merges without doubling", async () => {
    const pages: Record<number, SearchArchiveMessageWire[]> = { 0: [msg(0), msg(1)], 2: [msg(2)] };
    const { fake, client } = await connected({
      features: ["search", "search_archive"],
      handlers: {
        "search.archive": (p) => ({
          session: archiveResult.session,
          total: 3,
          messages: pages[p.offset as number] ?? [],
        }),
      },
    });
    const first = await client.searchArchive({ session_id: "a", limit: 2, offset: 0 });
    const next = await client.searchArchive({ session_id: "a", limit: 2, offset: first.messages.length });
    const merged = appendArchivePage(first.messages, next.messages);
    expect(merged.map((m) => m.ordinal)).toEqual([0, 1, 2]);
    expect(merged.length).toBe(first.total);
    expect(fake.requests.filter((q) => q.method === "search.archive").map((q) => q.params.offset)).toEqual([0, 2]);
  });

  test("FEATURE GATE: no \"search_archive\" means no frame is ever sent", async () => {
    const { fake, client } = await connected({ features: ["search"] });
    expect(client.hasFeature("search_archive")).toBe(false);
    await expect(client.searchArchive({ session_id: "arch-uuid" })).rejects.toThrow(/no archive corpus/);
    expect(fake.requests.some((q) => q.method === "search.archive")).toBe(false);
  });

  test("the gates are independent: search without the archive still searches live", async () => {
    const { client } = await connected({
      features: ["search"],
      handlers: { "search.messages": () => ({ total: 0, hits: [], sessions: {} }) },
    });
    expect(client.hasFeature("search")).toBe(true);
    expect(client.hasFeature("search_archive")).toBe(false);
    await expect(client.searchMessages({ query: "badge" })).resolves.toEqual({
      total: 0,
      hits: [],
      sessions: {},
    });
  });

  test("an unknown archive session surfaces as a rejection, not an empty transcript", async () => {
    const { client } = await connected({
      features: ["search", "search_archive"],
      handlers: {
        "search.archive": () => {
          throw new Error("archive session nope not found");
        },
      },
    });
    await expect(client.searchArchive({ session_id: "nope" })).rejects.toThrow(/not found/);
  });
});
