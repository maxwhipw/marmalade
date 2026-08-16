---
paths:
  - "app/src/main/java/app/marmalade/android/ui/**/*.kt"
---

# Window insets — zero them on inner Scaffolds

The app is edge-to-edge (`enableEdgeToEdge()` in MainActivity). The
**outer** Scaffold in `MarmaladeApp` (MarmaladeNavHost.kt) consumes the
system-bar insets once and passes them down as `innerPadding`.

Every screen hosted inside the NavHost that creates its **own**
Scaffold / TopAppBar therefore MUST zero its insets, or Material 3's
defaults (`WindowInsets.statusBars` on TopAppBar, systemBars on
Scaffold content) apply the status-bar height a **second** time —
the "phantom spacer above the top bar" bug (seen on
WorkspaceDetailScreen, fixed 2026-07-19):

```kotlin
Scaffold(
    contentWindowInsets = WindowInsets(0),
    topBar = { TopAppBar(windowInsets = WindowInsets(0), ...) },
)
```

Checklist when adding ANY new screen with a Scaffold or TopAppBar:

1. Hosted inside `MarmaladeNavHost`? → both `contentWindowInsets =
   WindowInsets(0)` on the Scaffold and `windowInsets = WindowInsets(0)`
   on the TopAppBar. Copy an existing settings screen.
2. Standalone Activity (e.g. `ChatWidgetConfigActivity`)? → keep the
   defaults; there is no outer Scaffold, so the insets are needed once.

Quick audit for regressions:

```bash
for f in $(grep -rln "Scaffold(" app/src/main/java --include=*.kt); do
  grep -q contentWindowInsets "$f" || echo "$f"
done
```

(The only legitimate hits are MarmaladeNavHost.kt — the outer Scaffold —
and standalone activities.)
