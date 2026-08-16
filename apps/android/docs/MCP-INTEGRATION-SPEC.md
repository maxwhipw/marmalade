# Marmalade App Changes — MCP Support + Identity Fix

## File Locations in This Repo

| Path | Purpose |
|------|---------|
| `docs/MCP-INTEGRATION-SPEC.md` | This spec |
| `docs/ANDROID-ASSISTANT-TRANSCRIPT.md` | Sanitized test session transcript |
| `gateway-skills/mcp/src/index.ts` | MCP server source (14 tools) |
| `gateway-skills/mcp/package.json` | MCP server dependencies |
| `gateway-skills/mcp/tsconfig.json` | TypeScript config |
| `gateway-skills/mcp/README.md` | MCP setup guide |
| `gateway-skills/marmalade-device-control.md` | Pure-skill fallback (no MCP) |
| `gateway-skills/marmalade-assistant-template.md` | Agent routing template |
| `gateway-skills/README.md` | Overview + installation |
| `gateway-skills/AGENTS.md` | Agent config snippets |



_Written: 2026-04-15 | For: Claude Code_

---

## Background

Marmalade uses **two separate systems** to let the agent interact with the Android device:

1. **Native node commands** (`nodes` tool): Gateway calls the phone via WebSocket RPC. The agent gets data back silently. The user sees nothing unless the agent narrates it. This is for: camera, location, contacts, device health, screen record, etc.

2. **MCP tools** (`marmalade_*`): A custom MCP server returns **formatted text** that the agent includes in its response. The Marmalade app parses that text and fires Android Intents or renders interactive UI cards. This is for: timers, alarms, app launches, dialer, SMS drafts, media, web search, UI cards (confirm/select/status).

The app already has:
- `ActionDispatcher.kt` / `parseMarmaladeAction()` — scans response text for `"marmalade_action"` JSON, fires Android Intents
- `MarmaladeBlockParser.kt` + `MarmaladeBlockRenderer.kt` — parses `\`\`\`marmalade` fences into typed interactive cards
- `ConnectionManager.kt` — builds connection options for both node and operator connections

This spec covers 4 changes needed to make MCP tools work end-to-end.

---

## Changes Required

### 1. Fix Client Identity
**Priority: High | Effort: Trivial | Confidence: Certain**

**File:** `app/src/main/java/app/marmalade/android/node/ConnectionManager.kt`

**Method:** `buildOperatorConnectOptions()`

**Current:**
```kotlin
client = buildClientInfo(clientId = "openclaw-control-ui", clientMode = "ui"),
```

**Change to:**
```kotlin
client = buildClientInfo(clientId = "openclaw-android", clientMode = "ui"),
```

**Why:** The operator (webchat/chat) connection currently identifies itself as `"openclaw-control-ui"` — the same ID used by the browser-based Control UI and webchat. This makes the gateway and agent unable to distinguish a browser from the Android app. The node connection already uses `"openclaw-android"` (see `buildNodeConnectOptions()`), which is a registered canonical client ID in `GATEWAY_CLIENT_IDS`. The operator connection should match. This is a one-line fix.

**After this change:** The agent can detect the Android app by session key (`android-assistant`) OR by `clientId == "openclaw-android"`, enabling future session-aware behavior.

---

### 2. Verify and Wire MCP Action Dispatch
**Priority: High | Effort: Small (likely already working, needs verification)**

**What to check:** `parseMarmaladeAction()` in `ActionDispatcher.kt` already handles the MCP output format. The function scans response text for the literal string `"marmalade_action"`, then uses brace-depth matching to extract the enclosing JSON object. This works even when the JSON is embedded inside a ` ```json ` code fence or surrounded by prose.

The **existing test** `parseExtractsActionEmbeddedInProseText()` in `ActionDispatcherTest.kt` confirms this:
```kotlin
val text = """
    Sure! I'll open that for you now.
    {
      "marmalade_action": {
        "action": "app.launch",
        "package": "com.spotify.music",
        ...
      }
    }
    Let me know if you need anything else.
""".trimIndent()
val result = parseMarmaladeAction(text)
assertNotNull(result)
```

The MCP server produces output in exactly this format (preamble text + ` ```json ` fence containing the JSON), so `parseMarmaladeAction` should find it.

**What to verify in `ChatViewModel` (or wherever messages are processed):**

Find where `parseMarmaladeAction()` is called (the ActionDispatcher.kt header says it's called from `ChatViewModel.afterResponseReceived()` or `OpenClawSession.handleResponseReceived()`). Confirm:

1. **It receives the full raw response text** — not a sanitized or markdown-stripped version. The raw text must include the ` ```json ` block. If the markdown renderer strips code blocks before passing to `parseMarmaladeAction`, the action won't be found.

2. **It's called for ALL assistant messages** — not just voice responses. The `ActionDispatcher.kt` is in the `voice/` package, which suggests it might have been originally voice-only. Confirm it's also invoked for chat responses.

3. **It's called once per message** — if the message arrives in streaming chunks, ensure `parseMarmaladeAction` is called on the **complete, assembled message text**, not on each chunk.

**If wiring is missing:** Call `parseMarmaladeAction(responseText)` in the chat message processing path, and if an action is returned, call `dispatchAction(context, action)`. The existing `ActionDispatcher.kt` functions are complete and correct for all supported action types.

**Add a unit test** covering the MCP output format with the ` ```json ` wrapper:
```kotlin
@Test
fun parseExtractsActionFromJsonCodeFence() {
    val text = """
        Setting a 5 minute timer.
        
        ```json
        {
          "marmalade_action": {
            "action": "device.timer",
            "params": {"duration_seconds": "300"},
            "displayText": "Setting a 5 minute timer"
          }
        }
        ```
    """.trimIndent()
    val result = parseMarmaladeAction(text)
    assertNotNull(result)
    assertEquals("device.timer", result!!.action)
    assertEquals("300", result.params["duration_seconds"])
}
```

---

### 3. Wire Marmalade Interactive Block Rendering in Chat
**Priority: High | Effort: Medium**

**What it is:** The MCP server produces ` ```marmalade ` code fences for interactive UI cards (confirm dialogs, select pickers, action buttons, status cards). The app already has `MarmaladeBlockParser` and `MarmaladeBlockRenderer` to handle them — but they need to be hooked into the chat markdown renderer.

**What to find:** Locate where the chat message markdown is rendered. This is likely in a Composable that handles `AssistantMessage` display — probably a `MarkdownText` or custom `MessageContent` composable. It should already have special handling for code blocks (language detection).

**What to add:** When the markdown renderer encounters a code block with language tag `marmalade`, instead of rendering it as a syntax-highlighted code block, it should:

1. Parse the block content using `MarmaladeBlockParser.parseMarmaladeBlock(content)`
2. If parsing succeeds: render with `MarmaladeBlockRenderer(block, onInteraction = { response -> sendMessage(response) })`
3. If parsing fails (returns null): fall back to rendering as a styled code block (this matches the existing `RawJsonFallback` behavior in `MarmaladeBlockRenderer`)

**The `sendMessage` callback:** When the user interacts with a block (taps Confirm, selects an option), `MarmaladeBlockRenderer` calls `onInteraction(formattedResponse)` with a ` ```marmalade-response ``` ` block string. This string should be sent as a chat message back to the agent via the existing chat send mechanism.

**Example expected behavior:**

Agent response text:
````
Which would you like to do?

```marmalade
{
  "type": "select",
  "blockId": "test-1",
  "data": {
    "message": "Pick one:",
    "options": [
      {"id": "a", "label": "Option A"},
      {"id": "b", "label": "Option B"}
    ]
  }
}
```
````

App should render a Material 3 card with a radio-button selector. When user taps "Option A", app sends:
````
```marmalade-response
{"blockId":"test-1","type":"select","response":"a"}
```
````

**Note:** The ` ```json ` fences (for device actions) should continue to render as normal code blocks in the UI — they're for machine parsing, not user display. Only ` ```marmalade ` fences need special rendering treatment.

---

### 4. Fix Alarm Label and Add Timer Label Support
**Priority: Medium | Effort: Small**

**Bug A — `dispatchAlarm` ignores the `message` param:**

In `ActionDispatcher.kt`, `dispatchAlarm()` does NOT set `AlarmClock.EXTRA_MESSAGE` even though the action's `params` map may contain a `"message"` key:

```kotlin
// CURRENT (broken for label):
val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
    putExtra(AlarmClock.EXTRA_HOUR, hour)
    putExtra(AlarmClock.EXTRA_MINUTES, minute)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

**Fix:**
```kotlin
val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
    putExtra(AlarmClock.EXTRA_HOUR, hour)
    putExtra(AlarmClock.EXTRA_MINUTES, minute)
    action.params["message"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

**Bug B — `dispatchTimer` has no label support at all:**

`dispatchTimer()` doesn't set a timer label. Android's `AlarmClock.ACTION_SET_TIMER` supports `EXTRA_MESSAGE` for a timer name (e.g., "Bananas"):

```kotlin
// CURRENT (no label):
val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

**Fix:**
```kotlin
val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    action.params["message"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

**MCP server also needs updating** — the `marmalade-mcp` server (`src/index.ts`, maintained outside this repo): The `SetTimerSchema` and `handleSetTimer` function don't have a `message` field. This is a separate change to the gateway-side MCP server, but document it here so it's not missed:

In `SetTimerSchema`:
```typescript
const SetTimerSchema = z.object({
  duration_seconds: z.number().int().positive()...,
  message: z.string().optional().describe("Timer label/name"),
  displayText: z.string().optional(),
});
```

In `handleSetTimer`, pass it to the action params:
```typescript
const params: Record<string, unknown> = { duration_seconds };
if (message) params.message = message;
const action = {
  action: "device.timer",
  params: stringifyParams(params),
  displayText: display,
};
```

---

## MCP Output Format Reference

This is the exact text the MCP server returns. The app must handle both formats.

### Device Actions (` ```json ` fence)
All 9 device action tools (`marmalade_set_timer`, `marmalade_set_alarm`, `marmalade_launch_app`, `marmalade_search_app`, `marmalade_web_search`, `marmalade_dial`, `marmalade_draft_sms`, `marmalade_play_media`, `marmalade_open_intent`) return this structure:

```
<preamble text ending in period>

```json
{
  "marmalade_action": {
    "action": "<action-type>",
    <optional top-level fields: "package", "intentAction", etc.>,
    "params": { "<key>": "<value>" },
    "displayText": "<human label>"
  }
}
```
```

**Concrete example (timer):**
```
Setting a 5 minute timer.

```json
{
  "marmalade_action": {
    "action": "device.timer",
    "params": {
      "duration_seconds": "300"
    },
    "displayText": "Setting a 5 minute timer"
  }
}
```
```

**Concrete example (app launch):**
```
Opening YouTube.

```json
{
  "marmalade_action": {
    "action": "app.launch",
    "package": "com.google.android.youtube",
    "params": {},
    "displayText": "Opening YouTube"
  }
}
```
```

### Interactive Blocks (` ```marmalade ` fence)
All 5 interactive block tools (`marmalade_confirm`, `marmalade_select`, `marmalade_multiselect`, `marmalade_action_buttons`, `marmalade_status`) return this structure:

````
```marmalade
{
  "type": "<confirm|select|multiselect|action|status>",
  "blockId": "<optional-id>",
  "title": "<optional-title>",
  "data": { <type-specific content> }
}
```
````

**Concrete example (confirm):**
````
```marmalade
{
  "type": "confirm",
  "blockId": "del-1",
  "title": "Delete file?",
  "data": {
    "message": "This cannot be undone.",
    "confirmLabel": "Delete",
    "cancelLabel": "Cancel"
  }
}
```
````

**Note:** Interactive blocks may or may not have a preamble line before the fence depending on whether the agent adds one.

---

## Test Cases

Send these messages to the `android-assistant` session and observe behavior:

### Test 1: Timer (device action)
**Send:** "Set a 30 second timer named Bananas"
**Expected:**
- App dispatches `AlarmClock.ACTION_SET_TIMER` with `EXTRA_LENGTH=30` and `EXTRA_MESSAGE="Bananas"`
- Clock app timer UI appears or confirms set
- Toast shows "Setting a 30 second timer"
- ` ```json ``` ` block visible (or hidden) in chat — device timer fires

### Test 2: Alarm (device action)
**Send:** "Set an alarm for 3:10 PM called Apple"
**Expected:**
- App dispatches `AlarmClock.ACTION_SET_ALARM` with `EXTRA_HOUR=15`, `EXTRA_MINUTES=10`, `EXTRA_MESSAGE="Apple"`
- Clock app shows alarm confirmation

### Test 3: App Launch (device action)
**Send:** "Open YouTube"
**Expected:**
- YouTube opens (or App not installed error if not present)
- Toast: "Opening com.google.android.youtube" (or similar)

### Test 4: Confirm Block (interactive)
**Send:** "Ask me to confirm deleting a test file using a confirm card"
**Expected:**
- App renders a Material 3 card with Confirm/Cancel buttons
- Tapping Confirm sends: ` ```marmalade-response\n{"type":"confirm","response":"confirmed"}\n``` `
- Tapping Cancel sends: ` ```marmalade-response\n{"type":"confirm","response":"cancelled"}\n``` `
- Agent receives the response and continues

### Test 5: Select Block (interactive)
**Send:** "Show me a menu of 3 things to do today using a select card"
**Expected:**
- App renders a Material 3 single-choice selector card
- Tapping an option sends a `marmalade-response` with the selected id

### Test 6: Client identity (after Change 1)
**Verify:** Gateway can now distinguish the Android app from a browser session
- Agent should detect `clientId == "openclaw-android"` if it inspects the session metadata
- Session routing rules in AGENTS.md (session key `android-assistant`) still apply

---

## Architecture Notes

### The Two-System Distinction

```
┌────────────────────────────────────────────────────────────────────┐
│  SYSTEM A: Native Node Commands                                    │
│  Agent → nodes() tool → Gateway → WebSocket RPC → Phone           │
│  Result flows BACK to the agent as data                            │
│  User sees nothing unless agent narrates it                        │
│  Examples: camera.snap, location.get, contacts.search             │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│  SYSTEM B: MCP Response-Text Parsing                               │
│  Agent → marmalade_* MCP tool → Returns text → Agent embeds in    │
│  response → Gateway delivers response → App parses → Intent fires │
│  User sees the action happen on the device                         │
│  Examples: timers, alarms, app launches, UI cards                  │
└────────────────────────────────────────────────────────────────────┘
```

### Why Both Systems Exist

Native node commands are built into the OpenClaw protocol — they're a direct RPC interface for data retrieval. They can't trigger user-visible actions like "open the timer app" because that would require the phone to launch Activities, which needs an active foreground context.

The MCP approach works around this by having the app parse action descriptions from the agent's response text and fire Intents itself, using its own foreground context. It also enables interactive UI cards (confirm, select) which create a feedback loop back to the agent.

### `parseMarmaladeAction` Algorithm (for reference)

The existing parser in `ActionDispatcher.kt` works as follows:
1. Scan `text` for the literal substring `"marmalade_action"`
2. Find the last `{` before that position (the enclosing JSON object start)
3. Walk forward counting `{` and `}` to find the matching closing brace
4. Attempt to `Json.decodeFromString` the extracted substring
5. Extract `root["marmalade_action"]` and deserialize as `MarmaladeAction`

This means the parser is **format-agnostic** — it doesn't care about markdown fences, prose before/after the JSON, or code fence language tags. If the text contains a valid `marmalade_action` JSON object anywhere, the parser will find it. The ` ```json ``` ` wrapper from the MCP server is not an obstacle.

### `MarmaladeBlockParser` vs `parseMarmaladeAction`

These are two separate parsers with separate responsibilities:

| Parser | Input | Output | Used for |
|--------|-------|--------|----------|
| `parseMarmaladeAction()` | Any response text | `MarmaladeAction?` | Device action dispatch (Intents) |
| `MarmaladeBlockParser.parseMarmaladeBlock()` | Content of a ` ```marmalade ` fence | `MarmaladeBlock?` | Interactive UI card rendering |

The markdown renderer should call `MarmaladeBlockParser` when it encounters a ` ```marmalade ` fence. The chat processing pipeline should call `parseMarmaladeAction` on the complete response text to catch device actions.

---

## Files to Modify

| File | Change | Effort |
|------|--------|--------|
| `app/.../node/ConnectionManager.kt` | Change `clientId` in `buildOperatorConnectOptions()` | 1 line |
| `app/.../voice/ActionDispatcher.kt` | Fix `dispatchAlarm()` and `dispatchTimer()` to use `EXTRA_MESSAGE` | ~4 lines |
| `ChatViewModel.kt` (or equivalent) | Verify `parseMarmaladeAction` is called on chat messages; wire if missing | Verify only / small wiring |
| Chat markdown renderer (find it) | Add ` ```marmalade ` code fence → `MarmaladeBlockRenderer` routing | Medium |
| `marmalade-mcp` `src/index.ts` (outside this repo) | Add `message` field to `SetTimerSchema` + `handleSetTimer` | ~6 lines, separate PR |
| `ActionDispatcherTest.kt` | Add test for JSON-code-fence input format | ~20 lines |

---

## What's Already Working (No Changes Needed)

- `parseMarmaladeAction()` — algorithm is correct, handles MCP output format
- All `dispatchXxx()` functions in `ActionDispatcher.kt` — Intent construction is correct
- `MarmaladeBlockParser.parseMarmaladeBlock()` — parses interactive block JSON correctly
- `MarmaladeBlockRenderer` — renders all block types with correct Material 3 styling
- `buildSupportedActions()` — advertises the correct 9 action types to the gateway
- All `MarmaladeAction` and `MarmaladeBlock` data models — match MCP server output
