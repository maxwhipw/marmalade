package app.marmalade.android.ui.home

import androidx.annotation.DrawableRes
import app.marmalade.android.R

/**
 * Mascot expression variants mapped to VectorDrawable resources.
 *
 * Each expression modifies the face elements (eyes, mouth, brows) of the
 * marmalade jar mascot while keeping the jar body identical. This makes
 * swapping to final artist-produced art trivial -- replace 9 drawable files.
 *
 * Expression selection is driven by app state:
 * - Home screen: connection state (HAPPY/WORRIED/SLEEPY)
 * - Voice popup: assistant state (ALERT/FOCUSED/SPEAKING/JOY)
 * - Onboarding: fixed expressions (JOY for welcome/done)
 * - Empty chat / About: HAPPY (default)
 */
enum class MascotExpression(@DrawableRes val drawableRes: Int) {
    /** Default calm smile. Connected, normal state. */
    HAPPY(R.drawable.mascot_happy),

    /** Droopy half-closed eyes. Idle, no gateway configured. */
    SLEEPY(R.drawable.mascot_sleepy),

    /** Furrowed brows, small worried mouth. Disconnected, error. */
    WORRIED(R.drawable.mascot_worried),

    /** Wide open eyes, attentive. Voice active, listening. */
    ALERT(R.drawable.mascot_alert),

    /** Normal eyes, open mouth. TTS playback. */
    SPEAKING(R.drawable.mascot_speaking),

    /** Asymmetric eyebrows, wavy mouth. Unexpected errors. */
    CONFUSED(R.drawable.mascot_confused),

    /** Narrowed determined eyes. Processing, tool calls. */
    FOCUSED(R.drawable.mascot_focused),

    /** Big open-mouth grin, squint eyes. Success, celebration. */
    JOY(R.drawable.mascot_joy),
    ;

    companion object {
        /** Drawable resource for the blink animation frame (eyes closed, happy mouth). */
        @DrawableRes
        val BLINK_DRAWABLE_RES: Int = R.drawable.mascot_blink
    }
}
