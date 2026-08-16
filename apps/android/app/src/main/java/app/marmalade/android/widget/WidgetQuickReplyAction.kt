package app.marmalade.android.widget

/**
 * Widget quick-reply affordance.
 *
 * Glance 1.1.1 does not expose a general-purpose `TextField`/`TextInput` composable
 * that widgets can use for free-form typing — only system notification-style
 * RemoteInput actions, which render as a dialog not a field. Rather than ship a
 * degraded experience, the widget uses a "Reply" button that deep-links into
 * [app.marmalade.android.MainActivity] with the session key pre-selected, where
 * the normal soft-keyboard + composer UI is available.
 *
 * This file is kept as a placeholder to match the plan's artifact list and to
 * make it easy to upgrade to a real [androidx.glance.appwidget.action.ActionCallback]
 * once Glance adds a usable text input (or we add a notification-based reply flow).
 */
object WidgetQuickReplyAction {
    /** Intent extra key for the session key passed to MainActivity when tapping reply. */
    const val EXTRA_SESSION_KEY = "navigate_to_session"
}
