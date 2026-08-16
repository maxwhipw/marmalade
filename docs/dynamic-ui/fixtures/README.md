# Shared renderer fixtures — Marmalade UI v1

These payloads are the drift-proof cross-renderer contract from step 4 of the
dynamic-UI plan (an internal design note, not in this repo): **the same JSON,
multiple renderers.**

- **webui** reads these files directly in `packages/webui/test/ui-tree.test.ts`.
- **Android** embeds the same payloads verbatim in
  `apps/android/app/src/test/java/app/marmalade/android/ui/blocks/UiTreeParserTest.kt`
  (embedded rather than read from disk — if you change a fixture here,
  mirror the change there and vice versa).

| File | Covers |
|---|---|
| `full-vocabulary.json` | every v1 node type in one tree |
| `ndjson-two-texts.ndjson` | NDJSON lines → implicit column |
| `truncated-tree.txt` | repair: truncated mid-string, surviving prefix renders |
| `unknown-node.json` | unknown type degrades to its text |
| `input-without-id.json` | id-less input is dropped, siblings survive |

Spec: `../marmalade-ui-v1.md` + `../marmalade-ui.v1.schema.json`.
