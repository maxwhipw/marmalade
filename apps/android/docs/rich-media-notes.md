# Rich Media & Interactivity — Feature Notes

> Written by Marmalade (OpenClaw agent), 2026-04-01  
> Based on review of the codebase + OpenClaw gateway docs + conversation with the maintainer

---

## What Already Exists (Great Starting Point)

After reading the code, a lot of the foundation is already here:

- **`CanvasController.kt`** — full WebView controller with `navigate()`, `eval()`, `snapshotBase64()`. Canvas is implemented and working at the controller level.
- **`A2UIHandler.kt`** — A2UI push/reset fully implemented.
- **`ChatMessageContent`** — already has `mimeType`, `fileName`, `base64` fields. The data model is ready for attachments.
- **`OutgoingAttachment`** — outbound attachment type already exists.
- **`ChatMarkdownPreprocessor`** — already stripping gateway metadata before display.
- **Node command handlers** — camera, location, screen, system, etc. all wired up.

The main gaps are:
1. No `CanvasHandler.kt` — canvas node commands (`canvas.present`, `canvas.navigate`, etc.) aren't dispatched yet
2. Inbound attachment rendering in chat bubbles isn't implemented
3. Special code fence rendering (Mermaid, HTML) isn't implemented

---

## Gap 1: Canvas Node Commands (CanvasHandler)

`CanvasController` exists but there's no `CanvasHandler` to wire it to the node invoke dispatcher.

Need to create `node/CanvasHandler.kt` and register it in `InvokeDispatcher.kt`.

### Commands to handle:

```kotlin
"canvas.present"  -> show canvas panel, optionally navigate to URL
"canvas.hide"     -> hide canvas panel
"canvas.navigate" -> navigate to URL or path
"canvas.eval"     -> evaluate JS, return result
"canvas.snapshot" -> capture WebView bitmap, return base64
"canvas.a2ui.push"      -> delegate to A2UIHandler
"canvas.a2ui.pushJSONL" -> delegate to A2UIHandler (legacy alias)
"canvas.a2ui.reset"     -> delegate to A2UIHandler
```

### Canvas panel UX options (discussed with the maintainer):

**Recommended: Floating resizable overlay (PiP-style)**
- Draggable, resizable floating panel over chat
- Toggle button in chat header (show/hide)
- `canvas.present` auto-shows it
- `canvas.hide` dismisses it
- User can see chat + canvas simultaneously

**Not recommended: Per-bubble WebView**
- Memory expensive (one WebView per message)
- Can't be interactive after scrolling off screen
- State doesn't persist across messages

**Alternative: Bottom sheet / modal**
- Simpler to implement than overlay
- Loses the "see both chat and canvas" advantage
- Fine for v1 if overlay is too complex

### Canvas host URL
The gateway serves canvas content at:
```
http://<gateway-host>:<port>/__openclaw__/canvas/
```
Files live at `~/.openclaw/workspace/canvas/` on the server.
The gateway advertises this URL in the connection handshake — already stored in `GatewaySession.canvasHostUrl`.

The WebView should load this URL (LAN or Tailscale). The gateway injects a live-reload client, so content updates automatically when files change on disk.

---

## Gap 2: Inbound Attachment Rendering in Chat

`ChatMessageContent` already has `mimeType`, `base64`, `fileName` fields — the model is ready. Need rendering in the chat bubble UI.

### What the gateway sends

The gateway `chat.message` event can include content blocks with `type` other than `"text"`. Check `GatewaySession` for how content blocks are parsed — may need to handle `type = "image"` / `type = "audio"` alongside `"text"` and `"tool_use"`.

Also check if the gateway sends a separate `attachments` array on the message envelope (see OpenClaw docs: `src/gateway/chat-attachments.ts`).

### Rendering plan per type:

**Images (`image/png`, `image/jpeg`, `image/gif`, `image/webp`)**
- Render inline in the chat bubble below text
- Tap to full-screen / share
- Source: `base64` field on `ChatMessageContent`

**Audio (`audio/mpeg`, `audio/ogg`, `audio/wav`)**  
- Render as a compact playback bar in the bubble (play/pause, scrubber, duration)
- TTS output will come through this path
- Source: `base64` field

**SVG (`image/svg+xml`)**
- Render inline via `ImageView` with SVG library (Coil has SVG support), or a tiny WebView

**Files (other mimeTypes)**
- Show as a file attachment chip (icon + filename + size)
- Tap to open/share

### How the agent sends attachments

Currently via the gateway session tool the agent uses:
- `tts` tool → delivers audio automatically in the session stream
- `message` tool with `buffer` (base64) + `contentType` — but this is for channel plugins

For the custom Android session, the agent needs a way to attach binary data to a reply. Two options:
1. **Gateway adds attachment support to `chat.message` events** — cleanest, requires gateway-side work
2. **Agent writes file to workspace, sends a URL** — app fetches from gateway HTTP server (`http://<gateway>:<port>/...`). No gateway changes needed, but requires app to make HTTP requests.

Option 2 is the quick path. The gateway already serves the canvas directory over HTTP — same mechanism could serve agent-generated files (images, audio, diagrams).

---

## Gap 3: Special Code Fence Rendering

`ChatMarkdownPreprocessor` strips metadata but doesn't do fence detection yet. Add fence detection + rendering for:

### ` ```mermaid `

Two approaches:
- **Server-side (easier):** Agent renders Mermaid → PNG using `mmdc` CLI on the gateway machine, sends as image attachment. App just renders an image. No special handling needed.
- **Client-side (richer):** App detects ` ```mermaid ``` ` block, renders it in an inline WebView using Mermaid.js. Interactive, but adds complexity.

Recommend starting with server-side PNG — add a skill that tells Marmalade to always render Mermaid server-side and send as image. Add client-side later as an enhancement.

### ` ```html `

Detect `html` fenced blocks, render in a sandboxed WebView within the bubble (fixed height, scrollable). 

Security considerations:
- Sandbox the WebView (`settings.javaScriptEnabled = false` for untrusted HTML, or use `WebViewAssetLoader`)
- Or: render in the canvas panel instead of inline (safer, more space)
- Don't allow navigation out of the sandboxed content

### ` ```svg `

Detect `svg` fenced blocks or inline SVG, render via Coil SVG + ImageView.

---

## Suggested Skill: `marmalade-app`

A skill that teaches the agent what the app can render natively:

```markdown
---
name: marmalade-app
description: >
  Capabilities of the Marmalade Android app. Use when sending rich content
  to the maintainer on his phone — tells you how to send images, audio, diagrams, and
  canvas content that the app renders natively.
---

# Marmalade App Capabilities

When responding to the maintainer in chat, the app supports:

## Images
Send image attachments — they render inline in chat bubbles.
To send a generated image, write it to ~/.openclaw/workspace/output/<name>.png
and attach via the message mechanism.

## Audio / TTS
Use the `tts` tool — audio is delivered as a voice note in the chat bubble.

## Diagrams
Use ```mermaid fenced blocks — the app renders them natively.
Or: render server-side with `mmdc` and send as an image attachment.

## Canvas
Use canvas node commands to push interactive HTML content to the canvas panel.
The canvas panel is a floating overlay the user can show/hide.
canvas.navigate to a URL or local canvas file.
canvas.snapshot to capture what's currently shown and send as image.

## HTML
Use ```html fenced blocks for simple rich content rendered in-bubble.
Use canvas for complex interactive content.
```

---

## Build Order (Recommended)

1. **CanvasHandler.kt** — wire `canvas.*` commands to `CanvasController` + `A2UIHandler`; implement floating overlay panel UX (or bottom sheet for v1)
2. **Inbound image rendering** in chat bubbles (base64 PNG/JPEG inline)
3. **Inbound audio rendering** (playback bar for TTS output)
4. **Mermaid server-side** (install `mmdc` on gateway, add marmalade-app skill)
5. **HTML fence rendering** in bubbles (sandboxed WebView)
6. **Mermaid client-side** (WebView with Mermaid.js, enhancement)
7. **marmalade-app skill** — teach agent what the app supports

---

## Open Questions

1. Does the current gateway `chat.message` WS event include an `attachments` array, or is the content block array the only mechanism? Check what `GatewaySession.onEvent` receives for TTS output.
2. Is the canvas panel currently shown anywhere in the UI (Activity/Fragment), or does `CanvasController` exist but have no view attached yet?
3. What markdown rendering library is currently used for chat bubbles? (Markwon? Compose Markdown?) — affects how fence detection hooks in.
4. Is the node WS role ("node") multiplexed on the same connection as chat ("operator"), or separate?
