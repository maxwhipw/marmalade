# Marmalade Block Format Specification

## Overview

Marmalade blocks are JSON payloads inside triple-backtick `marmalade` code fences in agent responses. When the Marmalade Android app parses a message containing a marmalade code fence, it extracts the JSON and renders it as an interactive UI element instead of raw text.

This format enables gateway agents to present structured interactions -- confirmations, selections, action cards, and progress indicators -- directly in the chat conversation.

**Example in an agent message:**

````
Here are some options for you:

```marmalade
{
  "type": "select",
  "blockId": "lang-choice",
  "title": "Choose a language",
  "data": {
    "message": "Which programming language would you like to use?",
    "options": [
      {"id": "kotlin", "label": "Kotlin"},
      {"id": "python", "label": "Python"},
      {"id": "rust", "label": "Rust"}
    ]
  }
}
```

Let me know your preference!
````

## Block Structure

Every Marmalade block is a JSON object with this top-level schema:

```json
{
  "type": "confirm | select | multiselect | action | status",
  "blockId": "optional-unique-id",
  "title": "Optional title displayed above the block",
  "data": { ... }
}
```

| Field     | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `type`    | string | Yes      | One of: `confirm`, `select`, `multiselect`, `action`, `status` |
| `blockId` | string | No       | Unique identifier for the block. Used in responses and for in-place updates (status blocks). If omitted, the app generates one internally. |
| `title`   | string | No       | Header text rendered above the block content. |
| `data`    | object | Yes      | Type-specific payload. Schema varies by `type` (see below). |

**JSON parsing behavior:** The app uses `isLenient = true` and `ignoreUnknownKeys = true`, so agents can include extra fields without breaking parsing.

## Block Types

### confirm

Presents a yes/no confirmation dialog with customizable button labels.

**Data schema:**

```json
{
  "message": "Are you sure you want to delete this file?",
  "confirmLabel": "Yes, delete it",
  "cancelLabel": "Cancel"
}
```

| Field          | Type   | Required | Default    | Description |
|----------------|--------|----------|------------|-------------|
| `message`      | string | Yes      | --         | The confirmation question or description |
| `confirmLabel` | string | Yes      | `"Yes"`    | Label for the confirm button |
| `cancelLabel`  | string | Yes      | `"Cancel"` | Label for the cancel button |

**User response:**

```json
{
  "blockId": "delete-confirm",
  "type": "confirm",
  "response": "confirmed"
}
```

The `response` field is either `"confirmed"` or `"cancelled"`.

**Full example:**

````
I found a duplicate config file. Should I remove it?

```marmalade
{
  "type": "confirm",
  "blockId": "delete-confirm",
  "title": "Remove duplicate?",
  "data": {
    "message": "config.backup.json appears to be an exact copy of config.json.",
    "confirmLabel": "Yes, delete it",
    "cancelLabel": "Keep it"
  }
}
```
````

---

### select

Presents a single-choice selection from a list of options.

**Data schema:**

```json
{
  "message": "Which database would you like to use?",
  "options": [
    {"id": "postgres", "label": "PostgreSQL"},
    {"id": "sqlite", "label": "SQLite"},
    {"id": "mysql", "label": "MySQL"}
  ]
}
```

| Field     | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `message` | string | Yes      | Description or question above the options |
| `options` | array  | Yes      | List of `{id, label}` objects |

Each option object:

| Field   | Type   | Required | Description |
|---------|--------|----------|-------------|
| `id`    | string | Yes      | Unique identifier returned in the response |
| `label` | string | Yes      | Display text shown to the user |

**User response:**

```json
{
  "blockId": "db-choice",
  "type": "select",
  "response": "postgres"
}
```

The `response` field contains the `id` of the selected option.

---

### multiselect

Presents a multi-choice selection with checkboxes and a submit button.

**Data schema:**

```json
{
  "message": "Select the features you want to enable:",
  "options": [
    {"id": "dark-mode", "label": "Dark mode"},
    {"id": "notifications", "label": "Push notifications"},
    {"id": "analytics", "label": "Usage analytics"},
    {"id": "auto-update", "label": "Auto-update"}
  ],
  "submitLabel": "Enable selected"
}
```

| Field         | Type   | Required | Default    | Description |
|---------------|--------|----------|------------|-------------|
| `message`     | string | Yes      | --         | Description above the checkboxes |
| `options`     | array  | Yes      | --         | List of `{id, label}` objects (same as select) |
| `submitLabel` | string | Yes      | `"Submit"` | Label for the submit button |

**User response:**

```json
{
  "blockId": "feature-select",
  "type": "multiselect",
  "response": ["dark-mode", "notifications"]
}
```

The `response` field is an array of selected option `id` strings.

---

### action

Presents a row or grid of action buttons, optionally with Material icons.

**Data schema:**

```json
{
  "actions": [
    {"id": "open-browser", "label": "Open in browser", "icon": "Language"},
    {"id": "copy-url", "label": "Copy URL", "icon": "ContentCopy"},
    {"id": "share", "label": "Share", "icon": "Share"}
  ]
}
```

| Field     | Type  | Required | Description |
|-----------|-------|----------|-------------|
| `actions` | array | Yes      | List of action button definitions |

Each action object:

| Field   | Type   | Required | Description |
|---------|--------|----------|-------------|
| `id`    | string | Yes      | Unique identifier returned in the response |
| `label` | string | Yes      | Button text |
| `icon`  | string | No       | Material icon name (e.g., `"Language"`, `"ContentCopy"`, `"Share"`). Must match a `androidx.compose.material.icons` name. Omit for text-only buttons. |

**User response:**

```json
{
  "blockId": "url-actions",
  "type": "action",
  "response": "copy-url"
}
```

The `response` field contains the `id` of the tapped action.

---

### status

Displays an informational status card with optional progress bar. Status blocks are **not interactive** -- the user does not respond to them. Use the same `blockId` to update a status block in-place (e.g., progress from 0.0 to 1.0).

**Data schema:**

```json
{
  "message": "Downloading model weights...",
  "progress": 0.45,
  "state": "running"
}
```

| Field      | Type   | Required | Description |
|------------|--------|----------|-------------|
| `message`  | string | Yes      | Status description text |
| `progress` | float  | No       | Progress value from `0.0` to `1.0`. Renders a linear progress bar when present. |
| `state`    | string | Yes      | One of: `"running"`, `"complete"`, `"error"` |

**No user response.** Status blocks are informational only.

**In-place update pattern:** Send a new status block with the same `blockId` to update the progress and message:

````
```marmalade
{"type": "status", "blockId": "download-1", "data": {"message": "Downloading... 45%", "progress": 0.45, "state": "running"}}
```
````

Then later:

````
```marmalade
{"type": "status", "blockId": "download-1", "data": {"message": "Download complete!", "progress": 1.0, "state": "complete"}}
```
````

## Response Format

When a user interacts with an interactive block (confirm, select, multiselect, or action), the app sends a response as a chat message containing a triple-backtick `marmalade-response` code fence:

````
```marmalade-response
{"blockId": "lang-choice", "type": "select", "response": "kotlin"}
```
````

**Response payload schema:**

```json
{
  "blockId": "the-block-id",
  "type": "confirm | select | multiselect | action",
  "response": "value or array"
}
```

| Field      | Type          | Description |
|------------|---------------|-------------|
| `blockId`  | string        | The block's `blockId` (or auto-generated if none was provided) |
| `type`     | string        | The block type that was interacted with |
| `response` | string/array  | The user's selection. String for confirm/select/action; array of strings for multiselect. |

**Response values by type:**

| Type        | Response type | Possible values |
|-------------|---------------|-----------------|
| confirm     | string        | `"confirmed"` or `"cancelled"` |
| select      | string        | The selected option's `id` |
| multiselect | array         | Array of selected option `id` strings |
| action      | string        | The tapped action's `id` |
| status      | --            | No response (informational only) |

## Rendering Behavior

- Blocks render as **standalone card elements** in the chat, visually distinct from regular message bubbles.
- Each block type has its own card layout with appropriate Material 3 styling.
- The optional `title` field renders as a header above the block content.
- After a user interacts with a block, the selected option is **checkmarked** and all buttons are **disabled** to prevent duplicate submissions.
- Status blocks with `state: "complete"` show a checkmark; `state: "error"` shows an error indicator.

## Error Handling

- **Invalid JSON:** If the content inside a `marmalade` code fence is not valid JSON, the block falls back to rendering as a styled code block (syntax-highlighted, with a copy button).
- **Unknown block type:** If `type` is not one of the five recognized types, the raw JSON is displayed in a formatted code view.
- **Missing required fields:** If a required field (e.g., `data.message`) is missing, parsing fails and the block renders as raw JSON.
- **Extra fields are ignored:** The parser uses `ignoreUnknownKeys = true`, so agents can include additional metadata without breaking the block.

## End-to-End Example

**Agent sends a message with a confirm block:**

````
I noticed you have uncommitted changes. Should I stash them before switching branches?

```marmalade
{
  "type": "confirm",
  "blockId": "stash-confirm",
  "title": "Stash changes?",
  "data": {
    "message": "You have 3 modified files. Stashing will save them temporarily so you can switch branches safely.",
    "confirmLabel": "Stash and switch",
    "cancelLabel": "Stay on current branch"
  }
}
```
````

**App renders:** A card with the title "Stash changes?", the message text, and two buttons: "Stash and switch" (primary) and "Stay on current branch" (secondary).

**User taps "Stash and switch".**

**App sends as a chat message:**

````
```marmalade-response
{"blockId":"stash-confirm","type":"confirm","response":"confirmed"}
```
````

**Agent receives the response** and proceeds with the stash + branch switch operation.

## Gateway Skill Integration

This block format is consumed by the Marmalade Android app. Gateway agents generate these blocks as part of their responses to present structured interactions to the user.

Further documentation on building gateway skills that produce Marmalade blocks will be provided in the gateway skill specification (Phase 6, SKILL-01 through SKILL-03).
