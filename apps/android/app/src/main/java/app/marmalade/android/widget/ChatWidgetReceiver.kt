package app.marmalade.android.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * BroadcastReceiver registered in AndroidManifest that hosts [ChatWidget].
 * GlanceAppWidgetReceiver routes APPWIDGET_UPDATE intents into the Glance composition pipeline.
 */
class ChatWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChatWidget()
}
