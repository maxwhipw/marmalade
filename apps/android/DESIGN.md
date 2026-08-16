# Marmalade Android -- Design Standards

Reference document for maintaining visual consistency across the Marmalade Android app. Codifies the color system, spacing, typography, mascot usage, and component patterns established during the design phase.

## Color System

### Theme Presets

Marmalade uses Material You (dynamic wallpaper colors) as the default theme, with curated presets as alternatives. The theme system is built on M3 color schemes with a custom `MarmaladeColors` semantic layer.

| Preset | Description | Light Primary | Dark Primary |
|--------|-------------|---------------|--------------|
| **System** (default) | Material You dynamic colors from device wallpaper | Auto | Auto |
| Marmalade | Warm orange with neutral dark surfaces | `#F97316` | `#FB923C` |
| Midnight | Deep blue on warm stone | `#2563EB` | `#60A5FA` |
| Forest | Earth green on warm stone | `#16A34A` | `#4ADE80` |
| Berry | Warm red on warm stone | `#DC2626` | `#F87171` |

Key decisions:
- **System (Material You) is the default.** Every Android 12+ user gets dynamic wallpaper-based colors out of the box.
- **Curated presets** overlay their primary family onto the base Marmalade warm-stone scheme. Surfaces/backgrounds stay warm for non-System presets.
- **Marmalade preset dark surfaces are neutral gray** (not warm brown). `surface: #262626`, `surfaceVariant: #3D3D3D`, `background: #1A1A1A`.
- **Chat bubbles follow the theme.** System and non-Marmalade presets derive bubble colors from `primaryContainer` (pastel). Marmalade has hand-tuned bubble colors.

Persistence: `SettingsRepository.themePreset` (preset name string, e.g. `"SYSTEM"`, `"MARMALADE"`).

### Marmalade Preset M3 Colors

| Role | Light | Dark | Notes |
|------|-------|------|-------|
| primary | Orange 500 `#F97316` | Orange 400 `#FB923C` | Marmalade orange, not amber |
| secondary | Orange 600 `#EA580C` | Orange 500 `#F97316` | |
| tertiary | Orange 700 `#C2410C` | Orange 300 `#FDBA74` | |
| surface | Orange 50 `#FFF7ED` | Neutral `#262626` | Dark is neutral gray |
| surfaceVariant | Orange 100 `#FFEDD5` | Neutral `#3D3D3D` | Input bar, cards |
| background | Orange 50 `#FFF7ED` | Neutral `#1A1A1A` | |
| error | Red 600 `#DC2626` | Red 500 `#EF4444` | |
| outline | Stone 400 `#A8A29E` | Neutral `#737373` | |
| onPrimaryContainer | Deep brown `#4A1808` | Near-white `#FFFBF7` | User bubble text |

### MarmaladeColors (Custom Semantic Tokens)

For UI elements that don't map to Material 3 roles, use the `MarmaladeColors` data class via `MaterialTheme.marmaladeColors`.

**Access pattern:**
```kotlin
// M3 standard roles
MaterialTheme.colorScheme.primary
MaterialTheme.colorScheme.surface

// Custom semantic tokens
MaterialTheme.marmaladeColors.codeBackground
MaterialTheme.marmaladeColors.userBubble
```

**Never use `Color(0x...)` directly in composables.** Always reference theme tokens.

For System and non-Marmalade presets, `MarmaladeColors` is derived from the M3 scheme via `deriveMarmaladeColors()`. Chat bubbles use `primaryContainer` (pastel) for a soft, easy-on-the-eyes look. Status colors (connected/disconnected) always stay semantic green/red regardless of theme.

Marmalade preset has hand-tuned values:

| Category | Token | Dark Value | Light Value | Purpose |
|----------|-------|-----------|-------------|---------|
| Chat | userBubble | `#DB9550` | `#FDBA74` | User message bubble (pastel) |
| Chat | userBubbleGradientEnd | `#D08840` | `#FAAE60` | User bubble gradient end |
| Chat | assistantBubble | `#2A2A2A` | `#FFEDD5` | Assistant message bubble |
| Code | codeBackground | `#0D0D0D` | `#FFF7ED` | Code block background |
| Code | codeBorder | `#2C2C2C` | `#E7E5E4` | Code block border |
| Code | codeText | `#D4EDBA` | `#365314` | Code block text |
| Tool | toolSuccess | `#4CAF50` | `#16A34A` | Success chip background |
| Banner | bannerWarning | `#FB923C` | `#F97316` | Reconnecting banner |
| Banner | bannerError | `#EF4444` | `#DC2626` | Disconnected banner |
| Status | statusConnected | `#4CAF50` | `#16A34A` | Connected indicator |
| Status | statusConnecting | `#FB923C` | `#F97316` | Connecting indicator |
| Status | statusDisconnected | `#EF4444` | `#DC2626` | Disconnected indicator |

### Chat Bubble Design

- **User bubbles**: Pastel `primaryContainer`-based colors with subtle vertical gradient (top→bottom)
- **User bubble text**: `onPrimaryContainer` — adapts automatically to any theme
- **Timestamps on user bubbles**: `onPrimaryContainer` at 60% alpha
- **Assistant bubbles**: Flat `surfaceVariant` (no gradient)
- **Tool call/result blocks**: `onSurface` at 6% alpha overlay — subtle but distinct from bubble
- **Tool blocks never wrap with timestamp** — timestamp renders standalone below tool blocks

### Input Bar

- Bar background: `surface` with `tonalElevation = 3.dp`
- Text field background: warm off-white `#FFFBF7` (light), subtle lighter gray `#4A4A4A` (dark) — distinct from surrounding bar
- Input text survives orientation changes via `rememberSaveable`

## Spacing

Standard scale (dp):

```
4  8  12  16  24  32  48
```

| Usage | Value |
|-------|-------|
| Tight inline gap | 4dp |
| Standard element gap | 8dp |
| Compact padding | 12dp |
| Standard padding | 16dp |
| Section spacing | 24dp |
| Large spacing | 32dp |
| Screen horizontal margin | 16dp |
| Touch targets | minimum 48x48dp |
| Bubble padding | horizontal 12dp, vertical 6dp |

**Do NOT use:** 10dp, 14dp, 18dp, 22dp, or any value not on the scale.

Exception: Visual element sizes (dot diameters, icon sizes) follow their own rules and may use non-scale values (e.g. 10dp status dot, 44dp circular icon surface).

## Typography

Use `MaterialTheme.typography.*` roles exclusively. Never hardcode `fontSize = X.sp`.

Exception: timestamps use `fontSize = 10.sp` for compact inline display.

| Usage | Typography Role |
|-------|----------------|
| Screen title (TopAppBar) | Default M3 TopAppBar (titleLarge) |
| Chat bar title | titleSmall (compact custom bar) |
| Section header | titleSmall |
| Card title | titleMedium |
| Body text | bodyLarge |
| Secondary text | bodyMedium |
| Subtitle/caption | bodySmall |
| Button labels | labelLarge |
| Chip/tag text | labelMedium |

## Mascot

### Design Source

The mascot is based on the `concept1-kawaii-classic` draft (an internal design asset, not in this repo) — a kawaii marmalade jar with warm amber body (#F5A623), cream lid, label with orange slice detail, and large expressive eyes.

### Expression System

8 expressions as VectorDrawable XML assets, plus a BLINK variant for idle animation. All share the identical jar body; only the face section (eyes, mouth, brows, blush) changes per expression.

| Expression | File | Eyes | Mouth | Extras |
|-----------|------|------|-------|--------|
| HAPPY | `mascot_happy.xml` | Large ovals + single highlight | Relaxed smile | Blush |
| SLEEPY | `mascot_sleepy.xml` | Half-moon droopy | Small subtle curve | No blush |
| WORRIED | `mascot_worried.xml` | Slightly smaller | Frown | Angled brows |
| ALERT | `mascot_alert.xml` | Extra large | Slightly parted (flat ellipse) | No blush |
| SPEAKING | `mascot_speaking.xml` | Normal + highlights | Open oval | Blush |
| CONFUSED | `mascot_confused.xml` | Asymmetric | Wavy line | One brow up, one down |
| FOCUSED | `mascot_focused.xml` | Narrow slits | Flat line | No blush |
| JOY | `mascot_joy.xml` | Happy squint arcs | Big grin | Stronger blush |
| BLINK | `mascot_blink.xml` | Closed arcs | Same as happy | Blush |

Design notes: single eye highlight per eye (not double — avoids looking too babyish). Mouths are ~11 units wide (relaxed, not tiny).

### Display Locations

**YES:** Home screen (via empty chat state, connection-reactive), gateway not-configured screen (SLEEPY), about screen, onboarding welcome/done, voice popup.

**NO:** Navigation bar icons, chat message avatars, session list rows, settings category screens.

### State Mapping

| App State | Expression |
|-----------|------------|
| Connected, normal | HAPPY |
| No gateway configured | SLEEPY |
| Disconnected, error | WORRIED |
| Voice listening | ALERT |
| Voice processing | FOCUSED |
| TTS speaking / preparing speech | SPEAKING |
| Processing / thinking | FOCUSED |
| Action complete | JOY |
| Unknown | CONFUSED |

### Animation

- **Blink:** Random interval 4-8s, 150ms eye-close duration
- **Bob:** Gentle 6dp vertical offset, 2s cycle, `FastOutSlowInEasing`, starts after 7s delay
- **Expression transitions:** `Crossfade(animationSpec = tween(300))` between expressions
- Use `graphicsLayer` for animation offsets (not `Modifier.offset` which causes recomposition)
- Always compose animation state unconditionally (Compose rule: no conditional composables)

### Asset Replacement

To update mascot art: replace the 9 drawable XML files (`mascot_happy` through `mascot_blink`). The `MascotExpression` enum auto-maps `@DrawableRes` values, so no code changes needed.

## Component Patterns

### Settings Cards (SettingsCategoryCard)

Used on the Settings main screen for category navigation:
- `Card` with `surfaceVariant` container color, `shapes.medium`
- Row layout: 44dp circular `Surface` with tinted icon (15% alpha background), 16dp gap, Column with `titleMedium` title + `bodySmall` subtitle, trailing chevron
- Card horizontal padding: 16dp, vertical: 4dp. Internal padding: 16dp.
- Each category gets a unique tint: primary (Appearance), secondary (Voice), tertiary (Gateway), outline (Permissions), onSurfaceVariant (App Info)

### Settings Information Architecture (maintainer, 2026-07-26)

**Settings must not become overwhelming.** They aren't today, but the default
drift of a client like this is to keep bolting dials onto the main screen until
a new user opens Settings and bounces. Guard against that structurally:

- The **main Settings screen carries only what a normal user would plausibly
  want to change** — the things that are self-explanatory from their title.
- **Fine-tuned dials live behind a single `Advanced` entry**, placed **toward
  the bottom of the main screen but not at the very bottom** (App Info stays
  last; Developer stays below Advanced). "Advanced" is the honest signal that
  what's inside is optional and most people should skip it.
- A setting belongs in Advanced when a typical user would **not want to, not
  care to, or not understand** touching it. If explaining it needs a paragraph,
  it's Advanced.
- Advanced is **not** a dumping ground for anything half-finished — it's for
  legitimate power-user controls. Debug/diagnostic surfaces still go to
  Developer, not Advanced.
- Don't compensate for a crowded screen with longer subtitles. Fewer rows beats
  better labels.

The specific split (which existing categories move under Advanced) is the maintainer's
call — propose it, don't reorganize unilaterally.

### Tab Headings

All tabs use the standard M3 `TopAppBar` component except the chat screen:

| Tab | Heading Style |
|-----|--------------|
| Chat (Home) | Compact custom bar (titleSmall, 48dp, no TopAppBar) |
| Sessions | `TopAppBar(title = "Sessions")` with search/add action icons |
| Terminal | `TopAppBar(title = "Terminal")` |
| Gateway | `TopAppBar(title = "Gateway")` |
| Settings | `TopAppBar(title = "Settings")` |

All settings sub-screens also use M3 `TopAppBar` with default styling and a back arrow navigation icon.

### Status Indicators

- Use `animateColorAsState(tween(300))` for smooth color transitions between states
- Colors from `marmaladeColors.statusConnected` / `statusConnecting` / `statusDisconnected`
- Connecting state pulses dot alpha (0.3-1.0 at 800ms)

### Disconnection Banner

- Cross-tab banner using `AnimatedVisibility` with `fadeIn + expandVertically` / `fadeOut + shrinkVertically` (both `tween(300)`)
- Amber for reconnecting, red for disconnected
- Colors from `marmaladeColors.bannerWarning` / `bannerError`

## Do's and Don'ts

### DO

- Use `MaterialTheme.colorScheme.*` for standard M3 color roles
- Use `MaterialTheme.marmaladeColors.*` for custom semantic tokens
- Use `MaterialTheme.typography.*` for all text styling
- Use standard spacing scale: 4, 8, 12, 16, 24, 32, 48dp
- Use `graphicsLayer` for animation offsets
- Set `label` parameter on all animation calls
- Use `spring()` for interactive animations, `tween()` for entrance/exit
- Use `animateColorAsState` for state-driven color changes
- Derive bubble colors from `primaryContainer` for non-Marmalade themes

### DON'T

- Use `Color(0x...)` in composables -- always use theme tokens
- Use hardcoded `fontSize = X.sp` -- use typography roles (exception: timestamps)
- Use arbitrary spacing (10dp, 14dp, 18dp, 22dp)
- Use `Modifier.offset` for animations (causes recomposition)
- Add mascot to navigation bar, chat avatars, or session list
- Change Gateway tab layout (it's excellent as-is, per user decision)
- Use winking expressions unless contextually coy
- Skip animation labels (required for tooling and debugging)
- Wrap tool call/result blocks with TextWithInlineTimestamp (causes overlap)
- Use warm/brown tones for dark mode surfaces in the Marmalade preset (neutral gray only)
