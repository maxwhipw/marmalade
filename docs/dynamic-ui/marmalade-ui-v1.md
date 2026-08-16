# Marmalade UI v1 — the dynamic-UI node tree (all clients)

Status: SPEC v1, 2026-07-12. This file +
`marmalade-ui.v1.schema.json` are the language-neutral truth all three
renderers (Compose Android, React webui, CLI) cite. Model: **the LLM emits
DATA in a closed vocabulary; clients own fixed native renderers.** No HTML,
no JS, no free-form styling — the closed enum set is simultaneously the
security model and the cross-platform contract.

Architecture adopted from Kai (github.com/SimonSchubert/Kai, Apache-2.0) —
see the plan for the decision record. The wire is untouched: trees ride
inside normal `message.delta`/`complete` text; responses ride
`prompt.submit` as plain user text. Zero daemon changes.

## Transport

A tree is emitted inside a fenced code block:

    ```marmalade-ui
    {"type":"card","title":"Trip","children":[...]}
    ```

- The fence body is ONE JSON node object, or NDJSON (one object per line)
  which clients wrap in an implicit `column`.
- Clients repair common LLM JSON damage before parsing (truncation,
  `"key=[` for `"key":[`, extra/missing closers, orphaned trailing keys) —
  a partial node renders with field defaults, never an error card.
- Unknown node types degrade to their `text`-ish content or are skipped —
  never an error. Unknown fields are ignored.
- The legacy 5 flat blocks (```marmalade fences: confirm / select /
  multiselect / action / status) remain valid as ALIASES — see §Legacy.

## Node vocabulary (closed — v1)

Every node: `{"type": <string>, "id"?: <string>, ...props}`. Container
nodes have `children: [node]`. Ids are only required on input nodes that a
button collects from.

### Layout
| type | props |
|---|---|
| `column` | `children` |
| `row` | `children` |
| `card` | `title?`, `children` |
| `divider` | — |

### Content
| type | props |
|---|---|
| `text` | `text`, `style?: headline\|title\|body\|caption` (default body), `bold?: bool`, `color?: default\|primary\|success\|warning\|error` |
| `list` | `items: [string]`, `ordered?: bool` |
| `table` | `columns: [string]`, `rows: [[string]]` |
| `code` | `code`, `language?` |
| `alert` | `text`, `level?: info\|success\|warning\|error` (default info), `title?` |

### Interactive
| type | props |
|---|---|
| `button` | `label`, `action: callback\|open_url\|copy_to_clipboard`, `event?` (callback name), `collect_from?: [ids]`, `url?` (open_url), `text?` (copy payload), `variant?: primary\|secondary\|danger` |
| `text_input` | `id`, `label?`, `placeholder?`, `value?` (initial) |
| `select` | `id`, `label?`, `options: [{id,label}]` (bare strings allowed: id=label) |
| `checkbox` | `id`, `label`, `checked?: bool` |
| `chip_group` | `id`, `options` (as select), `multi?: bool` |

### Feedback
| type | props |
|---|---|
| `progress` | `value?: 0..1` (absent = indeterminate), `label?` |
| `status` | `text`, `state?: pending\|active\|success\|error` |
| `countdown` | `until` (epoch ms) or `seconds`, `label?` |

Deferred to v2 (do NOT emit): tabs, accordion, slider, radio_group,
switch, image, stat, badge, avatar, quote, icon.

## Interaction contract

Inputs hold LOCAL state only. Conversation history is the only state
carrier between screens. A `button` with `action: "callback"` closes the
loop: the client synthesizes a PLAIN user message through the normal send
path (`prompt.submit` — the daemon sees ordinary text):

- No `collect_from`: `Pressed: <event or label>`
- With `collect_from`: `Responded with: <event>: <id>=<value>; <id>=<value>`
  - text_input → the entered string
  - select / chip_group (single) → the chosen option id
  - chip_group (multi) → comma-joined chosen ids
  - checkbox → `true` / `false`
  - a collected id with no value contributes `<id>=`

`open_url` opens the platform browser; `copy_to_clipboard` copies `text`.
These are the ONLY escape hatches.

## Behavior rules (for the behavior-spec catalog)

- Never render a button implying an action a callback can't perform.
- No fake loading states — `progress`/`status` only for real work.
- Keep trees shallow (≤4 levels) and small (≤40 nodes); prefer several
  small cards over one giant tree.
- Always accompany a tree with a short plain-text line for clients that
  can't render it (CLI subset, TTS).

## Legacy aliases (the shipped 5 flat blocks)

The ```marmalade fence (`{type, blockId?, title?, data}`) stays parsed by
existing renderers. Semantic mapping (documentation only — clients keep the
dedicated legacy path):
`confirm` ≈ card[text + row[button,button]] · `select` ≈ card[text +
chip_group(single) + button] · `multiselect` ≈ card[text +
chip_group(multi) + button] · `action` ≈ card[row[buttons]] · `status` ≈
status/progress. Legacy responses keep their ```marmalade-response JSON
format; v1 tree responses use the plain-text grammar above.
