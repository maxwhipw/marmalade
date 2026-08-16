package app.marmalade.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.luminance

// =============================================================================
// Marmalade Orange Palette
// =============================================================================

// --- Light scheme colors (orange) ---
val MarmaladeLightColors = lightColorScheme(
    primary = Color(0xFFF97316),            // Orange 500
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFED7AA),    // Orange 200
    onPrimaryContainer = Color(0xFF4A1808),  // Rich dark brown for bubble text
    secondary = Color(0xFFEA580C),           // Orange 600
    onSecondary = Color.White,
    // Toast — the design scheme's selected-chip/selected-tab fill. Must NOT
    // equal surface (Orange 50) or selected FilterChips vanish into the bg.
    secondaryContainer = Color(0xFFFED7AA),  // Toast (Orange 200)
    onSecondaryContainer = Color(0xFF7C2D12),
    tertiary = Color(0xFFC2410C),            // Orange 700
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFED7AA),
    onTertiaryContainer = Color(0xFF7C2D12),
    surface = Color(0xFFFFF7ED),             // Orange 50
    onSurface = Color(0xFF1C1917),           // Stone 900
    surfaceVariant = Color(0xFFFFEDD5),      // Orange 100
    onSurfaceVariant = Color(0xFF57534E),    // Stone 600
    // Tonal container roles — set explicitly so M3 components (NavigationBar,
    // sheets, cards, menus) stay in the warm cream family instead of falling
    // back to Material's default cool/purple-tinted grays.
    surfaceContainerLowest = Color(0xFFFFFBF7),
    surfaceContainerLow = Color(0xFFFFF3E4),
    surfaceContainer = Color(0xFFFFEDD5),
    surfaceContainerHigh = Color(0xFFFCE6CE),
    surfaceContainerHighest = Color(0xFFF7E0C8),
    surfaceBright = Color(0xFFFFF7ED),
    surfaceDim = Color(0xFFEFE4D6),
    background = Color(0xFFFFF7ED),          // Orange 50
    onBackground = Color(0xFF1C1917),        // Stone 900
    error = Color(0xFFDC2626),               // Red 600
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFFA8A29E),             // Stone 400
    outlineVariant = Color(0xFFD6D3D1),      // Stone 300
    inverseSurface = Color(0xFF1C1917),
    inverseOnSurface = Color(0xFFFAFAF9),
    inversePrimary = Color(0xFFFB923C),      // Orange 400
    surfaceTint = Color(0xFFF97316),
)

// --- Dark scheme colors ---
//
// Design rule: orange is banned on dark large fills, so `primary` (which M3
// fans out to ~80 fills + tints across the app) is TOAST — legible both as a
// button fill and as an icon/text tint on the stone-deep surface. The precious
// rich-brown accent lives on `marmaladeColors.accentButtonBg`, reserved for the
// one hero CTA per screen, not on `primary`. Surfaces are stone-deep #1C1917
// (neutral-warm), never amber-brown.
val MarmaladeDarkColors = darkColorScheme(
    primary = Color(0xFFFED7AA),             // Toast — soft accent, legible on dark
    onPrimary = Color(0xFF7C2D12),           // Deep amber ink (toast's locked partner)
    primaryContainer = Color(0xFFFEF3C7),    // Soft pastel
    onPrimaryContainer = Color(0xFF422006),  // Rich brown ink
    secondary = Color(0xFFFED7AA),           // Toast
    onSecondary = Color(0xFF7C2D12),
    secondaryContainer = Color(0xFF7C2D12),  // Orange 900 (dark container, light ink)
    onSecondaryContainer = Color(0xFFFED7AA),
    tertiary = Color(0xFFFDBA74),            // Peach — small accents / running dot
    onTertiary = Color(0xFF422006),
    tertiaryContainer = Color(0xFF7C2D12),
    onTertiaryContainer = Color(0xFFFED7AA),
    surface = Color(0xFF1C1917),             // Stone deep (neutral-warm)
    onSurface = Color(0xFFF5F5F4),           // Stone 100
    surfaceVariant = Color(0xFF292524),      // Stone 800 — warm elevated surface
    onSurfaceVariant = Color(0xFFD6D3D1),    // Stone 300
    // Tonal container roles — warm stone family, so M3 components don't fall
    // back to Material's default cool grays on the stone-deep surface.
    surfaceContainerLowest = Color(0xFF141110),
    surfaceContainerLow = Color(0xFF1C1917),
    surfaceContainer = Color(0xFF211D1B),
    surfaceContainerHigh = Color(0xFF292524),
    surfaceContainerHighest = Color(0xFF332E2B),
    surfaceBright = Color(0xFF383430),
    surfaceDim = Color(0xFF1C1917),
    background = Color(0xFF1C1917),          // Stone deep
    onBackground = Color(0xFFF5F5F4),        // Stone 100
    error = Color(0xFFEF4444),
    onError = Color(0xFF1C1917),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    outline = Color(0xFF78716C),             // Stone 500
    outlineVariant = Color(0xFF44403C),      // Stone 700
    inverseSurface = Color(0xFFF5F5F4),
    inverseOnSurface = Color(0xFF1C1917),
    inversePrimary = Color(0xFFF97316),      // Orange (light-mode accent)
    surfaceTint = Color(0xFFFED7AA),         // Toast
)

// =============================================================================
// MarmaladeColors — Semantic color tokens via CompositionLocal
// =============================================================================

/**
 * Semantic color tokens for Marmalade-specific UI elements that don't map
 * cleanly to Material 3 built-in color roles.
 *
 * Access via [MaterialTheme.marmaladeColors] extension in any composable.
 */
@Immutable
data class MarmaladeColors(
    // Code blocks
    val codeBackground: Color,
    val codeBorder: Color,
    val codeText: Color,
    // Tool call chips
    val toolSuccess: Color,
    val toolSuccessDim: Color,
    val toolChipText: Color,
    // Thinking block
    val thinkingChipBackground: Color,
    val thinkingChipBorder: Color,
    // Chat surfaces
    val userBubble: Color,
    val userBubbleGradientEnd: Color,
    val assistantBubble: Color,
    // Ink ON the bubbles — paired with the bubble bg so dark mode's light
    // soft-pastel/peach bubbles get dark ink instead of inheriting the
    // light on-surface color (which would white-out on a light bubble).
    val onUserBubble: Color,
    val onAssistantBubble: Color,
    val chatTextPrimary: Color,
    val chatTextSecondary: Color,
    val chatTextMuted: Color,
    // Hero accent button (the single most important CTA per screen).
    // Mode-aware: orange in light, precious rich-brown in dark.
    val accentButtonBg: Color,
    val accentButtonFg: Color,
    // Wordmark "marmalade" color — orange in light, cream in dark.
    val wordmark: Color,
    // Avatar gradients (by session category)
    val avatarMainStart: Color,
    val avatarMainEnd: Color,
    val avatarAgentStart: Color,
    val avatarAgentEnd: Color,
    val avatarTelegramStart: Color,
    val avatarTelegramEnd: Color,
    val avatarDiscordStart: Color,
    val avatarDiscordEnd: Color,
    val avatarOtherStart: Color,
    val avatarOtherEnd: Color,
    // Banner colors
    val bannerWarning: Color,
    val bannerError: Color,
    // Status indicator
    val statusConnected: Color,
    val statusConnecting: Color,
    val statusDisconnected: Color,
    /** "A turn is in flight / this shell is alive" — the drawer's running dots
     *  and the Live chip. Green, deliberately NOT the orange family: those dots
     *  used `tertiary` (Orange 700 in light), which reads as red-alert next to
     *  the error dot, i.e. "broken" rather than "working" (maintainer, 2026-07-26). */
    val statusRunning: Color,
    /** "The agent is blocked on you" — a tool call parked behind an approval.
     *  Brand orange in light; Orange 400 in dark, matching [bannerWarning],
     *  because the light-mode #F97316 goes muddy on the stone-deep surface.
     *  NOT `colorScheme.primary`: that is Toast in dark mode and would put a
     *  pale cream dot where the legend needs a warning (maintainer, 2026-07-26). */
    val statusAwaiting: Color,
    /** "This is the message you jumped to" — the transient focus ring drawn
     *  around an anchored bubble after a search result / navigator jump
     *  (design-lab `session-search` lab 3, frame 1: 2px outline + soft glow).
     *  Must read as a highlight against BOTH chat bubbles, so it is not a
     *  bubble color: peach on the dark stone canvas, brand orange in light
     *  (where the peach user bubble would swallow a peach ring). */
    val focusRing: Color,
)

/**
 * Dark theme semantic colors. Values taken from the legacy named colors
 * that were hand-tuned for dark mode.
 */
val DarkMarmaladeColors = MarmaladeColors(
    // Code blocks
    codeBackground = Color(0xFF0D0D0D),
    codeBorder = Color(0xFF2C2C2C),
    codeText = Color(0xFFD4EDBA),
    // Tool call chips
    toolSuccess = Color(0xFF4CAF50),
    toolSuccessDim = Color(0xFF1E3B1F),
    toolChipText = Color(0xFF6FCF73),
    // Thinking block
    thinkingChipBackground = Color(0xFF3D2A00),
    thinkingChipBorder = Color(0xFF444444),
    // Chat bubbles (dark). User bubble stays peach (warm accent). The
    // assistant bubble was reworked away from the design-scheme Soft-pastel
    // (#FEF3C7): a big pale block on the stone-deep background read as jarring
    // and clashed with the near-black code block nested inside it. It's now a
    // warm-neutral elevated surface (stone-800), one step up from the #1c1917
    // background, with light stone ink — so nested code reads as a darker
    // inset and the whole bubble sits quietly on the dark canvas.
    userBubble = Color(0xFFFDBA74),           // Peach
    userBubbleGradientEnd = Color(0xFFFBB061),
    assistantBubble = Color(0xFF292524),      // Stone 800 (elevated surface)
    onUserBubble = Color(0xFF422006),         // Rich brown
    onAssistantBubble = Color(0xFFF5F5F4),    // Stone 100 ink
    chatTextPrimary = Color(0xFFF5F5F4),      // On-surface chat text (not on a bubble)
    chatTextSecondary = Color(0xFFA8A29E),
    chatTextMuted = Color(0xFF78716C),
    // Avatar gradients
    avatarMainStart = Color(0xFF3A2800),
    avatarMainEnd = Color(0xFF4D3500),
    avatarAgentStart = Color(0xFF1A2D40),
    avatarAgentEnd = Color(0xFF1E3A52),
    avatarTelegramStart = Color(0xFF0D2A3D),
    avatarTelegramEnd = Color(0xFF0E3550),
    avatarDiscordStart = Color(0xFF1A1A35),
    avatarDiscordEnd = Color(0xFF22224A),
    avatarOtherStart = Color(0xFF242424),
    avatarOtherEnd = Color(0xFF2E2E2E),
    // Banner colors
    bannerWarning = Color(0xFFFB923C),
    bannerError = Color(0xFFEF4444),
    // Status indicator
    statusConnected = Color(0xFF4CAF50),
    // Amber, deliberately NOT brand orange: "connecting/in-progress" must be
    // distinguishable from the orange palette at a glance (maintainer, 2026-07-03).
    statusConnecting = Color(0xFFFBBF24),
    statusDisconnected = Color(0xFFEF4444),
    statusRunning = Color(0xFF4CAF50),
    statusAwaiting = Color(0xFFFB923C),
    focusRing = Color(0xFFFED7AA),           // Cream-peach (lab 3 chip-selected)
    // Hero accent button — dark: precious rich brown / toast
    accentButtonBg = Color(0xFF422006),
    accentButtonFg = Color(0xFFFED7AA),
    wordmark = Color(0xFFFFEDD5),            // Cream (dark-mode wordmark)
)

/**
 * Light theme semantic colors. Adjusted for warm light backgrounds.
 */
val LightMarmaladeColors = MarmaladeColors(
    // Code blocks in light mode: a neutral Stone-soft panel (distinct from the
    // warm cream/peach bubbles) with Stone-dark ink. ChatCodeBlock picks a
    // light syntax theme when the ground is light (dark mode keeps near-black
    // + monokai). Palette: marmalade-design (Stone soft / Stone dark).
    codeBackground = Color(0xFFF5F5F4),
    codeBorder = Color(0xFFE7E5E4),
    codeText = Color(0xFF1C1917),
    // Tool call chips
    toolSuccess = Color(0xFF16A34A),
    toolSuccessDim = Color(0xFFDCFCE7),
    toolChipText = Color(0xFF15803D),
    // Thinking block
    thinkingChipBackground = Color(0xFFFFF7ED),
    thinkingChipBorder = Color(0xFFD6D3D1),
    // Chat bubbles — the locked maintainer-approved combination (light): peach user bubble,
    // cream assistant bubble.
    userBubble = Color(0xFFFDBA74),           // Peach
    userBubbleGradientEnd = Color(0xFFFAAE60),
    assistantBubble = Color(0xFFFFEDD5),      // Cream
    onUserBubble = Color(0xFF422006),         // Rich brown
    onAssistantBubble = Color(0xFF1C1917),    // Stone ink
    chatTextPrimary = Color(0xFF1C1917),
    chatTextSecondary = Color(0xFF57534E),
    chatTextMuted = Color(0xFFA8A29E),
    // Avatar gradients
    avatarMainStart = Color(0xFFFED7AA),
    avatarMainEnd = Color(0xFFFDBA74),
    avatarAgentStart = Color(0xFFDBEAFE),
    avatarAgentEnd = Color(0xFFBFDBFE),
    avatarTelegramStart = Color(0xFFCFFAFE),
    avatarTelegramEnd = Color(0xFFA5F3FC),
    avatarDiscordStart = Color(0xFFEDE9FE),
    avatarDiscordEnd = Color(0xFFDDD6FE),
    avatarOtherStart = Color(0xFFE7E5E4),
    avatarOtherEnd = Color(0xFFD6D3D1),
    // Banner colors
    bannerWarning = Color(0xFFF97316),
    bannerError = Color(0xFFDC2626),
    // Status indicator
    statusConnected = Color(0xFF16A34A),
    // Amber — see the dark-palette note.
    statusConnecting = Color(0xFFF59E0B),
    statusDisconnected = Color(0xFFDC2626),
    statusRunning = Color(0xFF16A34A),
    statusAwaiting = Color(0xFFF97316),
    focusRing = Color(0xFFF97316),           // Brand orange (peach would vanish
                                             // on the peach user bubble here)
    // Hero accent button — light: precious orange / white
    accentButtonBg = Color(0xFFF97316),
    accentButtonFg = Color.White,
    wordmark = Color(0xFFF97316),            // Orange (light-mode wordmark)
)

val LocalMarmaladeColors = staticCompositionLocalOf { DarkMarmaladeColors }

/**
 * Extension property for convenient access to [MarmaladeColors] semantic
 * tokens from any composable. Use as `MaterialTheme.marmaladeColors.codeBackground`.
 *
 * Lives here next to [LocalMarmaladeColors] rather than in `:app`'s `Theme.kt`
 * so shared chat composables can read the tokens; `MarmaladeTheme` (which
 * *provides* them) stays in `:app` because it needs Android dynamic color.
 */
val MaterialTheme.marmaladeColors: MarmaladeColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMarmaladeColors.current

// =============================================================================
// Theme Presets
// =============================================================================

/**
 * Curated theme presets. SYSTEM uses Material You dynamic colors from the
 * device wallpaper. Other presets override the primary color family and
 * derive chat bubble colors from it so everything stays coordinated.
 *
 * Each preset defines light/dark primary + primaryContainer colors.
 * The warm stone surface/background tones are shared across all presets.
 */
enum class ThemePreset(
    val displayName: String,
    val lightPrimary: Color,
    val darkPrimary: Color,
    val lightPrimaryContainer: Color,
    val darkPrimaryContainer: Color,
) {
    SYSTEM("System", Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified),
    MARMALADE("Marmalade", Color(0xFFF97316), Color(0xFFFB923C), Color(0xFFFED7AA), Color(0xFF9A3412)),
    MIDNIGHT("Midnight", Color(0xFF2563EB), Color(0xFF60A5FA), Color(0xFFDBEAFE), Color(0xFF1E3A5F)),
    FOREST("Forest", Color(0xFF16A34A), Color(0xFF4ADE80), Color(0xFFDCFCE7), Color(0xFF14532D)),
    BERRY("Berry", Color(0xFFDC2626), Color(0xFFF87171), Color(0xFFFEE2E2), Color(0xFF7F1D1D)),
    ;

    companion object {
        fun fromString(name: String): ThemePreset =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * Build a color scheme for a curated preset by overlaying its primary family
 * onto the base Marmalade warm-stone scheme. Surfaces/backgrounds stay warm.
 */
fun buildPresetScheme(base: ColorScheme, preset: ThemePreset, isDark: Boolean): ColorScheme {
    val primary = if (isDark) preset.darkPrimary else preset.lightPrimary
    val primaryContainer = if (isDark) preset.darkPrimaryContainer else preset.lightPrimaryContainer
    val onPrimary = if (primary.luminance() < 0.5f) Color.White else Color(0xFF1C1917)
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = if (primaryContainer.luminance() < 0.5f) Color.White else Color(0xFF1C1917),
        surfaceTint = primary,
        inversePrimary = if (isDark) preset.lightPrimary else preset.darkPrimary,
    )
}

/**
 * Derive [MarmaladeColors] from an M3 [ColorScheme] — used for Material You
 * dynamic colors and for non-Marmalade curated presets. Chat bubbles follow
 * the scheme's primary color so everything stays visually coordinated.
 */
fun deriveMarmaladeColors(scheme: ColorScheme, isDark: Boolean): MarmaladeColors {
    // Use primaryContainer for soft pastel bubbles, not the full-strength primary
    val userBubble = scheme.primaryContainer
    val bubbleEnd = scheme.primary.copy(alpha = 0.4f)
    return MarmaladeColors(
        // Code ground follows the mode: near-black + monokai in dark, Stone
        // panel + light syntax theme in light (ChatCodeBlock picks the theme
        // by ground luminance). Matches the static Light/DarkMarmaladeColors.
        codeBackground = if (isDark) Color(0xFF0D0D0D) else Color(0xFFF5F5F4),
        codeBorder = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE7E5E4),
        codeText = if (isDark) Color(0xFFD4EDBA) else Color(0xFF1C1917),
        toolSuccess = if (isDark) Color(0xFF4CAF50) else Color(0xFF16A34A),
        toolSuccessDim = if (isDark) Color(0xFF1E3B1F) else Color(0xFFDCFCE7),
        toolChipText = if (isDark) Color(0xFF6FCF73) else Color(0xFF15803D),
        thinkingChipBackground = scheme.surfaceVariant,
        thinkingChipBorder = scheme.outlineVariant,
        userBubble = userBubble,
        userBubbleGradientEnd = bubbleEnd,
        assistantBubble = scheme.surfaceVariant,
        onUserBubble = if (userBubble.luminance() < 0.5f) Color.White else Color(0xFF1C1917),
        onAssistantBubble = scheme.onSurfaceVariant,
        chatTextPrimary = scheme.onSurface,
        chatTextSecondary = scheme.onSurfaceVariant,
        chatTextMuted = scheme.outline,
        avatarMainStart = scheme.primaryContainer,
        avatarMainEnd = scheme.primary.copy(alpha = 0.3f),
        avatarAgentStart = if (isDark) Color(0xFF1A2D40) else Color(0xFFDBEAFE),
        avatarAgentEnd = if (isDark) Color(0xFF1E3A52) else Color(0xFFBFDBFE),
        avatarTelegramStart = if (isDark) Color(0xFF0D2A3D) else Color(0xFFCFFAFE),
        avatarTelegramEnd = if (isDark) Color(0xFF0E3550) else Color(0xFFA5F3FC),
        avatarDiscordStart = if (isDark) Color(0xFF1A1A35) else Color(0xFFEDE9FE),
        avatarDiscordEnd = if (isDark) Color(0xFF22224A) else Color(0xFFDDD6FE),
        avatarOtherStart = if (isDark) Color(0xFF242424) else Color(0xFFE7E5E4),
        avatarOtherEnd = if (isDark) Color(0xFF2E2E2E) else Color(0xFFD6D3D1),
        bannerWarning = if (isDark) Color(0xFFFB923C) else Color(0xFFF97316),
        bannerError = if (isDark) Color(0xFFEF4444) else Color(0xFFDC2626),
        statusConnected = if (isDark) Color(0xFF4CAF50) else Color(0xFF16A34A),
        statusConnecting = if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B),
        statusDisconnected = if (isDark) Color(0xFFEF4444) else Color(0xFFDC2626),
        statusRunning = if (isDark) Color(0xFF4CAF50) else Color(0xFF16A34A),
        statusAwaiting = if (isDark) Color(0xFFFB923C) else Color(0xFFF97316),
        // The focus ring rides the scheme's primary so a Material You /
        // curated preset highlights in its own accent, not marmalade orange.
        focusRing = scheme.primary,
        // Non-Marmalade presets don't have a brand accent; use the scheme's
        // own primary as the hero-CTA color so it stays coordinated.
        accentButtonBg = scheme.primary,
        accentButtonFg = scheme.onPrimary,
        wordmark = scheme.primary,
    )
}

