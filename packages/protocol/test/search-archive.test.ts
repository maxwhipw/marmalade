// search-archive.test.ts — the archive corpus's WIRE contract.
//
// Everything here is additive by construction, and the tests say so: an
// old-shaped search.messages frame must still parse to the live path, and the
// new fields must be optional in both directions.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  SearchScope,
  SearchMessagesParams,
  SearchSessionWire,
  SearchArchiveParams,
  SearchArchiveResult,
  SearchMessagesResult,
  MethodParamSchemas,
  ServerFeature,
} from "../dist/index.js";

test("scope.corpus is optional and round-trips both values", () => {
  assert.equal(SearchScope.parse({}).corpus, undefined);
  assert.equal(SearchScope.parse({ corpus: "live" }).corpus, "live");
  assert.equal(SearchScope.parse({ corpus: "archive" }).corpus, "archive");
  assert.equal(SearchScope.safeParse({ corpus: "backup" }).success, false);
  // It composes with the existing scope fields rather than replacing them.
  const s = SearchScope.parse({ corpus: "archive", workspace_ids: ["w1"], quick_chats: true, session_ids: ["a"] });
  assert.deepEqual(s, { corpus: "archive", workspace_ids: ["w1"], quick_chats: true, session_ids: ["a"] });
});

test("an old-shaped search.messages frame still parses, with no corpus", () => {
  const before = SearchMessagesParams.parse({ query: "pangolin", scope: { workspace_ids: ["w1"] } });
  assert.equal(before.scope!.corpus, undefined, "absent means live — the pre-archive behaviour");
  assert.deepEqual(
    { include_archived: before.include_archived, sort: before.sort, limit: before.limit, offset: before.offset },
    { include_archived: false, sort: "rank", limit: 20, offset: 0 },
    "the defaults are unchanged",
  );
  const after = SearchMessagesParams.parse({ query: "pangolin", scope: { corpus: "archive" } });
  assert.equal(after.scope!.corpus, "archive");
});

test("SearchSessionWire.corpus marks archive entries only", () => {
  const live = SearchSessionWire.parse({ title: "t", workspace_id: null, archived: false, last_active: 1 });
  assert.equal(live.corpus, undefined, "a live entry carries no marker — old clients see the old shape");
  const arch = SearchSessionWire.parse({ title: null, workspace_id: "w1", archived: false, last_active: 2, corpus: "archive" });
  assert.equal(arch.corpus, "archive");
  assert.equal(SearchSessionWire.safeParse({ title: null, workspace_id: null, archived: false, last_active: 1, corpus: "live" }).success, false);

  // The result envelope accepts a mixed-marker sessions map unchanged.
  const r = SearchMessagesResult.parse({ total: 0, hits: [], sessions: { a: live, b: arch } });
  assert.equal(r.sessions.b.corpus, "archive");
});

test("search.archive params default and clamp; the result shape is exact", () => {
  const p = SearchArchiveParams.parse({ session_id: "uuid" });
  assert.deepEqual(p, { session_id: "uuid", limit: 100, offset: 0 });
  assert.equal(SearchArchiveParams.safeParse({ session_id: "u", limit: 201 }).success, false);
  assert.equal(SearchArchiveParams.safeParse({ session_id: "u", limit: 0 }).success, false);
  assert.equal(SearchArchiveParams.safeParse({ session_id: "u", offset: -1 }).success, false);
  assert.equal(SearchArchiveParams.safeParse({}).success, false);

  const r = SearchArchiveResult.parse({
    session: { title: null, cwd: "/home/user/proj", last_active: 5, message_count: 2 },
    total: 2,
    messages: [
      { ordinal: 0, role: "user", ts: 1, text: "q" },
      { ordinal: 1, role: "assistant", ts: 2, text: "a" },
    ],
  });
  assert.equal(r.messages[1].role, "assistant");
});

test("search.archive is registered for gateway-side validation, and the feature is declarable", () => {
  assert.equal(MethodParamSchemas["search.archive"], SearchArchiveParams);
  assert.equal(ServerFeature.parse("search_archive"), "search_archive");
});
