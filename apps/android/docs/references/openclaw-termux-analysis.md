# openclaw-termux Source Analysis

Reference: a local checkout of the `openclaw-termux` repo
Version: 1.8.3 (pubspec) / 1.8.2 (constants)
License: MIT
Author: Mithun Gowda B (community, not official OpenClaw team)

---

## 1. Project Structure Overview

```
openclaw-termux/
  flutter_app/                     # Flutter/Dart UI + Android native layer
    lib/
      app.dart                     # App root, theme, provider wiring
      constants.dart               # All magic values in one file
      main.dart                    # Entry point
      models/                      # Immutable state models (copyWith pattern)
        gateway_state.dart
        node_state.dart
        node_frame.dart            # Protocol v3 frame type
      providers/                   # ChangeNotifier providers (Flutter state)
        gateway_provider.dart
        node_provider.dart
        setup_provider.dart
      screens/                     # One file per screen
        dashboard_screen.dart
        node_screen.dart
        terminal_screen.dart
        onboarding_screen.dart
        settings_screen.dart
        logs_screen.dart
        web_dashboard_screen.dart
        ssh_screen.dart
        (+ setup wizard, packages, providers, configure screens)
      services/
        gateway_service.dart       # Gateway lifecycle + health check
        node_service.dart          # Node protocol (challenge/pair/invoke)
        node_ws_service.dart       # Raw WebSocket with reconnect + ping
        node_identity_service.dart # Ed25519 key gen + auth payload signing
        native_bridge.dart         # All MethodChannel calls to Kotlin
        terminal_service.dart      # proot shell config for Pty
        preferences_service.dart   # SharedPreferences wrapper
        capabilities/              # Each capability is its own class
          camera_capability.dart
          location_capability.dart
          screen_capability.dart
          sensor_capability.dart
          vibration_capability.dart
          flash_capability.dart
          canvas_capability.dart
      widgets/
        gateway_controls.dart      # Start/stop/status card
        node_controls.dart         # Enable/disable/status card
        terminal_toolbar.dart      # Ctrl/Alt/function key bar for terminal
        status_card.dart           # Reusable nav tile
        progress_step.dart
    android/app/src/main/kotlin/com/nxg/openclawproot/
      MainActivity.kt              # All MethodChannel handlers in one class
      GatewayService.kt            # Foreground service running gateway process
      NodeForegroundService.kt     # Foreground service keeping node alive
      TerminalSessionService.kt    # Foreground service for terminal PTY
      SshForegroundService.kt      # Foreground service running sshd
      SetupService.kt              # Foreground service for initial install
      ScreenCaptureService.kt      # MediaProjection screen recording
      BootstrapManager.kt          # First-time install: rootfs + Node.js + openclaw
      ProcessManager.kt            # proot command builder + executor
      ArchUtils.kt                 # Detect arm64/armv7/x86_64
  lib/                             # Node.js installer script (npm package)
  bin/openclawx                    # CLI entry point
  package.json                     # npm package wrapping the installer
```

---

## 2. Tech Stack

**Flutter/Dart layer:**
- Flutter 3.x, Dart SDK >=3.2.0
- `provider` ^6.1.0 — ChangeNotifier-based state management
- `xterm` ^4.0.0 — terminal emulator widget
- `flutter_pty` ^0.4.2 — PTY for connecting terminal to proot shell
- `webview_flutter` ^4.4.0 — embedded web dashboard
- `web_socket_channel` ^3.0.0 — WebSocket client
- `cryptography` ^2.7.0 — Ed25519 key generation and signing
- `shared_preferences` ^2.2.0 — key-value persistence
- `permission_handler` ^11.3.0 — runtime permission requests
- `camera` ^0.11.0 — camera access for node capability
- `geolocator` ^12.0.0 — GPS for node capability
- `google_fonts` ^6.1.0 — Inter font family
- `dio` + `http` — HTTP client (dio for downloads, http for health checks)

**Kotlin/Android layer (no Jetpack Compose, no Hilt, no Room):**
- Plain Android Service classes (foreground services)
- `ProcessBuilder` + `Process` for proot subprocess management
- Apache Commons Compress for tar/ar/xz/zstd extraction
- Flutter MethodChannel + EventChannel for Dart-Kotlin bridge
- PowerManager WakeLock, SensorManager, MediaProjection, Vibrator

**Node.js layer:**
- OpenClaw gateway (the `openclaw` npm package) runs inside proot
- Ubuntu 24.04 rootfs with Node.js 22.13.1 installed
- Gateway launched via: `openclaw gateway --verbose`

---

## 3. Gateway Management

### Starting the Gateway
The gateway is an OpenClaw Node.js process running inside a proot Ubuntu 24.04 container on the Android device. Starting it:

1. Dart calls `NativeBridge.startGateway()` → MethodChannel → Kotlin
2. Kotlin calls `GatewayService.start(context)` → starts foreground service
3. `GatewayService.startGateway()` builds a proot process via `ProcessManager.buildGatewayCommand("openclaw gateway --verbose")`
4. Process stdout/stderr are read on background threads and emitted via `EventChannel` to Dart
5. Dart `GatewayService` listens to the `EventChannel` log stream and:
   - Stores logs (capped at 500 lines)
   - Parses the token URL out of logs using regex: `http://localhost:18789/#token=...`
   - Saves the token URL to `SharedPreferences`

### Health Checking
Every 5 seconds: `http.head("http://127.0.0.1:18789")`. If <500 → gateway healthy. If unreachable AND process not running → update state to stopped and stop timer.

### Auto-restart
`GatewayService.kt` implements exponential backoff restarts: 2s, 4s, 8s, up to 3 attempts. After 3 failures, stops and marks crashed.

### Gateway Configuration
The app writes to `/root/.openclaw/openclaw.json` inside proot (via `runInProot`) to set `allowCommands`. This is how the gateway knows which node capabilities to allow.

---

## 4. Node + Operator: The Critical Pattern

This is what Marmalade needs to understand. openclaw-termux does **both** roles separately on the same device:

### Role Separation
- **Gateway** = the embedded `openclaw gateway` process running in proot. It IS the gateway.
- **Node** = the Flutter/Kotlin app connecting TO that gateway as a node client.

They don't share code or a connection — they communicate over localhost WebSocket just like any remote node would. This means:
- The node pairing and auth flow is identical whether connecting to local or remote gateway
- Auto-approve local pairing works because the app can run `openclaw nodes approve <code>` in proot

### Node Connection Flow (Gateway Protocol v3)

```
App boots → NodeService.connect(127.0.0.1:18789)
  → WebSocket connects
  → Gateway sends: event "connect.challenge" {nonce: "..."}
  → App signs: "v2|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce"
     with Ed25519 private key
  → App sends: req "connect" {minProtocol:3, maxProtocol:3, client:{...},
       role:"node", scopes:["node.device"], caps:[...], commands:[...],
       auth:{token:...}, device:{id, publicKey, signature, nonce, signedAt}}
  → Gateway responds ok → node is "paired"
  → App sends: event "node.capabilities" {deviceId, capabilities:[...]}
```

If gateway responds with `TOKEN_INVALID`, `NOT_PAIRED`, or `DEVICE_NOT_PAIRED`:
```
  → App sends: req "node.pair.request" {deviceId}
  → Gateway responds with {code: "XXXX"} (pairing code)
  → For local gateway: app runs `openclaw nodes approve XXXX` in proot (auto-approve)
  → For remote gateway: user must approve on gateway dashboard
  → After approval: gateway responds with {token: "..."} → store as deviceToken
  → Reconnect with new token
```

### Token Resolution Priority
1. Manually entered token (for remote gateways, stored in prefs)
2. Token extracted from dashboard URL in prefs (regex `#token=([0-9a-fA-F]+)`)
3. Device token (from previous successful pairing, stored in prefs)

### Invoke Handling
When gateway sends `node.invoke.request`:
```json
{event: "node.invoke.request", payload: {id, nodeId, command, paramsJSON, timeoutMs}}
```
App looks up command in capability handler map, executes, then sends:
```
req "node.invoke.result" {id, nodeId, ok:true, payloadJSON:"..."}
```
or `{ok:false, error:{code, message}}` on failure.

### Operator Role
This app does NOT act as an operator (it does not initiate sessions/send chat messages to the gateway). The only way to interact with the gateway as an operator is via the embedded web dashboard (WebView pointing at `http://127.0.0.1:18789/#token=...`).

**This is the key gap for Marmalade**: openclaw-termux only connects as node, never as operator. Marmalade needs to initiate conversations as an operator (send chat messages to sessions). The openclaw-assistant community app fills this gap with `OpenClawAssistantService.kt`.

---

## 5. UI Patterns for Gateway Management

### Dashboard Screen
Single-scroll layout with:
- `GatewayControls` widget at top (status badge + start/stop/logs buttons)
- Grid of `StatusCard` nav tiles for every feature (Terminal, Web Dashboard, Node, Packages, SSH, etc.)
- Section labels in uppercase small caps with letter-spacing

### Status Badges
Pill-shaped badge with icon + label, colored by state:
- Green (#22C55E) = Running/Paired
- Amber (#F59E0B) = Starting/Connecting
- Red (#EF4444) = Error
- Grey (#6B7280) = Stopped/Disabled/Disconnected

Implementation pattern: `Container` with `BoxDecoration` using `color.withAlpha(25)` fill and `color.withAlpha(60)` border. Concise, no external dependencies.

### Gateway Controls Widget
Card containing:
- Title + status badge row
- Dashboard URL (clickable, opens WebView; copyable via icon button)
- Error message if present
- `Wrap` of action buttons: "Start Gateway" (FilledButton), "Stop Gateway" (OutlinedButton), "View Logs" (OutlinedButton)

Button visibility depends on current status: start shown when stopped/error, stop shown when running/starting.

### Node Controls Widget
Card containing:
- Title + status badge row
- Connection info (host:port) when paired
- Pairing code display (SelectableText) when pairing
- Wrap of action buttons: Enable/Disable/Reconnect/Configure

### Node Screen
Settings page layout:
- Local vs Remote radio selector
- Remote fields: host, port, token (obscured)
- Pairing code display when pairing
- Capability list (static, just shows checkmarks — no per-capability on/off)
- Device ID (SelectableText with monospace)
- Live log view (reversed ListView, 200px fixed height, monospace 11sp)

### Log Viewer Pattern
Logs stored as `List<String>` in state, capped at 500 entries. Display as reversed `ListView.builder` (newest at bottom). ANSI escape stripping done via regex for display. Log entries prefixed with `[INFO]`, `[WARN]`, `[ERROR]`, `[NODE]`.

---

## 6. Chat and Voice Features

**None.** openclaw-termux has no chat UI and no voice features whatsoever. The only interaction path with the AI is:
- The embedded web dashboard (WebView) — full gateway web UI
- Running terminal commands like `openclaw onboard`

This is a management/infrastructure app, not an assistant app.

---

## 7. Embedded Gateway (Proot) Setup

### What Gets Installed (First Run)
`BootstrapManager.kt` orchestrates a multi-step install:
1. Download Ubuntu 24.04 base rootfs tarball (arm64/armhf/amd64 per device arch)
2. Extract rootfs into `<filesDir>/rootfs/ubuntu/` — handles symlinks via `--link2symlink` style Java extraction
3. Download Node.js 22.13.1 tarball from nodejs.org (bypasses curl/gpg which fail in proot)
4. Extract Node.js into rootfs
5. Run `apt-get update && apt-get install -y ca-certificates curl git ...` in proot install mode
6. Install bionic-bypass.js (fixes Android Bionic linker issues with Node.js)
7. Run `npm install -g @openclaw/openclaw-cli` in proot

### proot Command Structure
Two modes, both in `ProcessManager.kt`:

**Install mode** (matches `proot-distro run_proot_cmd`):
```
libproot.so
  --root-id
  --link2symlink -L --kill-on-exit
  --rootfs=<filesDir>/rootfs/ubuntu
  --cwd=/root
  --bind=/dev --bind=/proc --bind=/sys
  --bind=<configDir>/resolv.conf:/etc/resolv.conf
  [fake /proc entries for loadavg, stat, uptime, version, vmstat, fips_enabled]
  [fake /sys/fs/selinux → empty dir]
  /usr/bin/env -i HOME=/root LANG=C.UTF-8 PATH=... TERM=xterm-256color
    DEBIAN_FRONTEND=noninteractive /bin/bash -c <command>
```

**Gateway mode** (matches `proot-distro command_login`):
```
libproot.so
  --change-id=0:0
  --sysvipc
  --kernel-release=\Linux\localhost\6.17.0-PRoot-Distro\...\
  --link2symlink -L --kill-on-exit
  --rootfs=<filesDir>/rootfs/ubuntu
  [same binds as install mode]
  /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 PATH=...
    NODE_OPTIONS=--require /root/.openclaw/bionic-bypass.js
    CHOKIDAR_USEPOLLING=true UV_USE_IO_URING=0 /bin/bash -c "openclaw gateway --verbose"
```

Key decisions:
- **Do NOT set `PROOT_NO_SECCOMP`** — proot-distro does not set it; seccomp BPF provides proper fork/clone tracking
- **`pb.environment().clear()`** before setting proot env — critical to prevent JVM vars (LD_PRELOAD, CLASSPATH, ANDROID_ROOT) from leaking into proot and breaking fork/exec
- **`env -i`** inside the command — removes all host vars from guest environment
- **Fake /proc entries** — Android restricts most /proc, so bind-mount static files for loadavg, stat, uptime, version, vmstat
- **Fake /sys/fs/selinux** — bind-mount to empty dir to disable SELinux checks
- **resolv.conf** written in three places: `<configDir>/resolv.conf` (bind source), `rootfs/etc/resolv.conf` (fallback if bind fails), and at process start time
- **libtalloc.so** — proot needs `libtalloc.so.2` but Android names it `libtalloc.so`; create a copy with the right name

### Android Services
Five separate foreground services, each with its own notification channel:
- `GatewayService` — runs the proot gateway process, PARTIAL_WAKE_LOCK 24h
- `NodeForegroundService` — keeps node WebSocket alive in background, PARTIAL_WAKE_LOCK 24h
- `TerminalSessionService` — keeps terminal PTY alive
- `SshForegroundService` — runs sshd in proot for remote access
- `SetupService` — shown during first-time install with progress notification

All declared with `foregroundServiceType="specialUse"` in manifest.

---

## 8. Terminal Emulator Implementation

### Stack
- `xterm` Flutter package v4.0.0 — full VT100/xterm terminal emulator widget
- `flutter_pty` v0.4.2 — native PTY (pseudo-terminal) allocation, connects to proot shell
- `DejaVuSansMono` font bundled as asset (regular + bold)
- Font fallback chain: DejaVuSansMono → monospace → Noto Sans Mono variants → Noto Color Emoji

### How It Works
```dart
// Initialize with 10,000 line scrollback buffer
_terminal = Terminal(maxLines: 10000);
_controller = TerminalController();

// Defer PTY start until after first frame (so viewWidth/viewHeight are real)
WidgetsBinding.instance.addPostFrameCallback((_) { _startPty(); });

// Start PTY with proot shell
_pty = Pty.start(
  config['executable'],   // libproot.so path
  arguments: prootArgs,   // full proot + env -i + /bin/bash -l
  environment: hostEnv,   // ONLY proot-specific vars
  columns: _terminal.viewWidth,
  rows: _terminal.viewHeight,
);

// Wire PTY output to terminal widget
_pty.output.cast<List<int>>().listen((data) {
  _terminal.write(utf8.decode(data, allowMalformed: true));
});

// Wire terminal input back to PTY
_terminal.onOutput = (data) { _pty.write(utf8.encode(data)); };

// Handle terminal resize
_terminal.onResize = (w, h, pw, ph) { _pty.resize(h, w); };
```

### Terminal Toolbar
A row of special-key buttons rendered below the terminal:
- Ctrl toggle (latching) — next alphanumeric key sends control code (a-z → bytes 1-26)
- Alt toggle (latching) — next key sends ESC + key
- Tab, Esc, function keys (F1-F12), arrow keys, Page Up/Down

### URL Detection
Smart URL handling in the terminal:
- Tap on terminal text: joins 5 adjacent lines, strips box-drawing chars, extracts URL via regex
- If URL found → shows "Open Link" dialog with Cancel/Copy/Open options
- Selection copy: if copied text contains URL, SnackBar shows "Open" action
- ANSI escape codes stripped before URL matching

This handles URLs that wrap across terminal lines or appear inside TUI box-drawing frames.

### Onboarding Terminal
A variant of TerminalScreen that runs `openclaw onboard` instead of a login shell. It:
- Monitors output for token URL regex `http://localhost:18789/#token=...` and saves to prefs
- Monitors output for completion pattern (regex matching "onboarding complete" etc.)
- Shows "Go to Dashboard" button when done
- Screenshot capability (captures terminal widget as image)

---

## 9. Additional Features Relevant to an Assistant App

### SSH Access (SshForegroundService)
Runs `sshd` in proot as a foreground service. UI shows device IPs and lets user set a root password. Useful pattern for remote access to the gateway environment.

### Config Snapshot Export/Import
`SettingsScreen._exportSnapshot()` reads `/root/.openclaw/openclaw.json` from rootfs + SharedPreferences, serializes to JSON, saves to Downloads. Import reverses. Good model for Marmalade's settings backup.

### Web Dashboard WebView
A simple `WebView` pointed at the gateway dashboard URL (with token in fragment). Handles the bionic-bypass.js injection (loaded as a local asset, injected into WebView at startup). This is the operator interface the app uses — Marmalade should replace this with native chat UI.

### Background Lifecycle Handling
`NodeProvider` implements `WidgetsBindingObserver`:
- On `resumed`: checks if foreground service is alive (restart if not), checks if WebSocket is stale (reconnect if so)
- On `paused`: ensures foreground service is running so Android doesn't kill the process

Stale detection: no data received for 90 seconds → `isStale = true`.

Watchdog timer every 45 seconds:
1. Verify foreground service is alive
2. If not paired and not connecting → reconnect
3. If paired but stale → disconnect + reconnect

### Battery Optimization
Proactively asks user to disable battery optimization via `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Checks `PowerManager.isIgnoringBatteryOptimizations()` and shows warning in Settings UI if still optimized.

### Capability Registration Pattern
```dart
_nodeService.registerCapability(
  capability.name,                // e.g. "camera"
  capability.commands.map((c) => '${capability.name}.$c').toList(), // ["camera.snap", "camera.clip", ...]
  (cmd, params) => capability.handle(cmd, params),
);
```
Handlers stored in a `Map<String, Future<NodeFrame> Function(String, Map)>` keyed by full command name. Lookup is O(1).

### AI Provider Configuration Screen
There is a `ProvidersScreen` and `ProviderDetailScreen` with a `ProviderConfigService` — this manages API keys and model configuration for the embedded gateway (which AI backend to use). No detail read here, but the screens exist and may be useful reference.

---

## 10. What This App Does Well — Lessons for Marmalade

### Things to Port Directly
1. **Full node connection flow** (`NodeService.dart` + `NodeWsService.dart`) — the challenge/sign/connect/pair/invoke pipeline is complete and correct. Port this to Kotlin, keeping the same state machine (disabled → connecting → challenging → pairing → paired).

2. **Ed25519 device identity** (`NodeIdentityService.dart`) — generate-once, store in prefs, SHA-256 of public key = deviceId, auth payload format `"v2|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce"`. Use BouncyCastle in Kotlin for the same.

3. **proot process construction** (`ProcessManager.kt`) — the exact bind-mount flags, fake /proc entries, `env -i` environment isolation, `pb.environment().clear()`, and the gateway vs install mode distinction are hard-won knowledge. Port verbatim.

4. **Token URL extraction from logs** (`GatewayService.dart`) — the ANSI stripping + box-drawing stripping + regex approach handles the terminal TUI output format correctly.

5. **Auto-approve local pairing** (`NodeService._requestPairing`) — detect local gateway (127.0.0.1/localhost) and run `openclaw nodes approve <code>` in proot automatically. Essential for UX.

6. **Status badge widget pattern** — the `color.withAlpha(25)` fill + `color.withAlpha(60)` border pill badge is clean and translates directly to Compose `Surface` + `Border`.

7. **Foreground service architecture** — separate services for gateway, node, terminal, SSH. Each with PARTIAL_WAKE_LOCK. Clear start/stop/isRunning companion object pattern. Translates directly to Kotlin/Android.

8. **Watchdog + app lifecycle reconnect** — the 45-second watchdog and `onResumed`/`onPaused` reconnect logic handles Android background process killing correctly. Essential for the assistant app where node must stay connected.

9. **Three-layer resolv.conf safety net** — writing to configDir, rootfsDir/etc, and verifying at every proot invocation. This solved a real bug (#40 — Android clearing filesDir on app update).

10. **Log capping pattern** — truncate to last 500 entries on every append. Simple, avoids memory growth.

### Things to Improve in Marmalade
1. **No chat UI** — openclaw-termux only surfaces the gateway web dashboard via WebView. Marmalade must replace this with a native Compose chat interface.

2. **No operator connection** — the app never sends messages as an operator. It only receives and dispatches invoke commands as a node. Marmalade needs to add operator WebSocket connection to send chat messages and receive streaming responses.

3. **No session management** — there is no concept of chat sessions, message history, or session switching. Entirely absent.

4. **No voice** — no wake word, STT, or TTS. Entirely absent.

5. **Dart preference wrapper is fragile** — `PreferencesService` requires `await prefs.init()` to be called before every use, and callers forget. Kotlin/Room in Marmalade avoids this entirely.

6. **Terminal onboarding is rough** — using a raw terminal for API key setup is functional but not polished. Marmalade should provide a native settings screen for gateway configuration.

7. **Single gateway only** — no multi-gateway support, no gateway list. Marmalade needs to support multiple gateways with persistence.

---

## Key Constants to Know

```
Gateway default: ws://127.0.0.1:18789
Gateway Protocol version: 3
Node role: "node"
Node scopes: ["node.device"]
Node clientId: "node-host"
Node clientMode: "node"
WS reconnect: base 350ms, multiplier 1.7x, cap 8s
Ping interval: 30s
Stale threshold: 90s (no data received)
Watchdog interval: 45s
Pairing timeout: 300s (5 minutes)
Maximum gateway restarts: 3 (2s, 4s, 8s backoff)
Health check interval: 5s (HTTP HEAD to gateway)
Log buffer: 500 entries (both gateway and node logs)
Node.js version: 22.13.1
Ubuntu rootfs: 24.04.3 base
```

## Files in This Repo NOT to Port (Flutter-specific)

- Any `provider`-based state management → use Hilt + Flow/StateFlow
- `web_socket_channel` → use OkHttp WebSocket
- `cryptography` package → use BouncyCastle (already in Marmalade stack)
- `shared_preferences` → use Room + DataStore
- `xterm` + `flutter_pty` → port terminal using the Kotlin `TerminalSessionService` foreground service pattern but wire to xterm.js in WebView per the Marmalade spec
- `camera`, `geolocator` Flutter plugins → native Android camera2/FusedLocationProvider APIs via Kotlin capability handlers
