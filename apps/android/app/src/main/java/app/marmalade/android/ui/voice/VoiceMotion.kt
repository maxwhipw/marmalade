package app.marmalade.android.ui.voice

import kotlin.math.sin

// Shared deterministic motion helpers for the voice popup's animated
// controls (MorphPillButton, JarMascot). Everything derives from a single
// rememberInfiniteTransition clock — no per-frame state writes, which keeps
// the overlay cheap (voice.md perf rule) and Robolectric deterministic.

/** Deterministic per-element shimmer in [0,1] — ported from the mockup's bn(). */
internal fun barNoise(i: Int, tMs: Float): Float =
    .5f + .5f * sin(i * 3.7f + tMs * .011f + sin(i * 1.3f + tMs * .004f) * 2f)

/** Organic fake voice level in [0,1] — stand-in until real mic amplitude is piped in. */
internal fun fakeAmp(tMs: Float): Float =
    (.45f + .3f * sin(tMs * .0027f) + .2f * sin(tMs * .0013f + 1.7f)).coerceIn(.05f, 1f)
