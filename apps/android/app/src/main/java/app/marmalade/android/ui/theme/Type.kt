@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.marmalade.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.marmalade.android.R

// =============================================================================
// Marmalade typography — Manrope (body/headings), Space Mono (code),
// Momo Trust Display (wordmark). Manrope's weights are derived from the
// variable font's `wght` axis.
// Tokens locked in marmalade-design-scheme-v0 (2026-06-21).
//
// All three are SIL OFL 1.1; the notice + license text ship in the APK at
// assets/licenses/OFL-1.1.txt, and CREDITS.md carries the per-font holders.
// =============================================================================

private fun manrope(weight: FontWeight) = Font(
    R.font.manrope_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Body + headings. */
val Manrope = FontFamily(
    manrope(FontWeight.Normal),   // 400
    manrope(FontWeight.Medium),   // 500
    manrope(FontWeight.SemiBold), // 600
    manrope(FontWeight.Bold),     // 700
)

/** Code / terminal / mono. Space Mono renders small, so callers bump +2sp. */
val MarmaladeMono = FontFamily(Font(R.font.space_mono_regular, FontWeight.Normal))

/**
 * Wordmark "marmalade" — **Momo Trust Display**, the official final wordmark
 * face (maintainer, 2026-07-25). Always rendered lowercase, tracking 0.
 *
 * Declared at [FontWeight.Normal] with no `variationSettings` because the font
 * ships as a single static 400 master (it is a display face — its weight is
 * drawn in, not interpolated). Asking for 600 here would hand Compose a weight
 * it cannot resolve and get synthetic emboldening instead of the real letters.
 * Callers therefore must NOT set a fontWeight on wordmark text.
 *
 * Fredoka was the stand-in while this was believed to be non-distributable;
 * it is OFL 1.1 like everything else here, so the stand-in is gone rather than
 * kept as a fallback — a bundled font cannot fail to load, and the wordmark
 * string is pure basic-latin, so there is no missing-glyph case either.
 */
val Wordmark = FontFamily(Font(R.font.momo_trust_display, FontWeight.Normal))

/**
 * Material 3 typography with Manrope as the family across the board. Sizes
 * follow the scheme: body 14sp/400, H1 (headlineSmall) 26sp/600, H2
 * (titleLarge) 17sp/600, all headings tracking −0.3sp, normal case.
 */
val MarmaladeTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Manrope),
        displayMedium = displayMedium.copy(fontFamily = Manrope),
        displaySmall = displaySmall.copy(fontFamily = Manrope),
        headlineLarge = headlineLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineMedium = headlineMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.copy(
            fontFamily = Manrope, fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp, letterSpacing = (-0.3).sp,
        ),
        titleLarge = titleLarge.copy(
            fontFamily = Manrope, fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp, letterSpacing = (-0.3).sp,
        ),
        titleMedium = titleMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = Manrope),
        bodyMedium = bodyMedium.copy(fontFamily = Manrope, fontSize = 14.sp),
        bodySmall = bodySmall.copy(fontFamily = Manrope),
        labelLarge = labelLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
    )
}
