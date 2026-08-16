---
paths:
  - "app/src/main/java/app/marmalade/android/ui/theme/**/*.kt"
  - "app/src/main/java/app/marmalade/android/ui/settings/AppearanceScreen.kt"
---

# Theme & accent rules

Gotchas for the Marmalade theme system, accent presets, and
light/dark switching.

## Composition locals

- Use **`staticCompositionLocalOf`** for `MarmaladeColors`, not
  `compositionLocalOf`. Theme changes are rare; static avoids
  unnecessary recomposition.

## Accent palettes — do not generate at runtime

- **Do not** generate Material 3 tonal palettes at runtime from a
  user's custom accent color via the HCT color science API. Use
  pre-computed curated `AccentPreset` values for predictable contrast.
- For the custom-accent picker (HSV wheel), only remap `primary`,
  `onPrimary`, `primaryContainer`, `onPrimaryContainer`. **Keep
  surfaces fixed** — surfaces carry the warm marmalade vibe;
  remapping them per-accent breaks cohesion.
- Compute `onPrimary` luminance and pick black or white text dynamically
  to avoid invisible text against custom accent colors.

## Theme switching

- `themeMode` is a `String` ("system" | "light" | "dark") in
  `SettingsRepository`.
- Hoist the theme state as `MutableState<String>` at `setContent` level
  in `MainActivity`. Theme changes recompose **without Activity restart**.
- Pure function `resolveThemeIsDark()` in `Theme.kt` derives the boolean
  from `themeMode + system dark mode`. Keep that pure — it's tested.

## Color hue shift in dark mode

- Light-mode primary is **Orange 500** (#F97316); dark-mode primary is
  **Toast** (#FED7AA) — orange is banned on dark fills (design scheme
  v0). Visually verify before assuming a single hue covers both modes.
- `secondaryContainer` (selected chips, nav indicator) must never equal
  `surface` — that's the invisible-selected-chip bug fixed 2026-07-18.
  Light = Toast #FED7AA; dark = Orange 900 #7C2D12.

## Legacy color references

- No file outside `Color.kt` / `Theme.kt` should import the legacy
  named colors (`MarmaladeBg`, `MarmaladeChatTextPrimary`, etc.).
  All access goes through `MaterialTheme.colorScheme.*` or
  `MarmaladeColors.*`. If you find a legacy import elsewhere, that's
  drift — fix it.
