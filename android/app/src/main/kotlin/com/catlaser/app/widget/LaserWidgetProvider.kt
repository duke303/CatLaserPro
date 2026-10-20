package com.catlaser.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.catlaser.app.MainActivity
import com.catlaser.app.R
import com.catlaser.app.data.SettingsRepository
import com.catlaser.app.network.CatLaserApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Home-screen widget: shows the laser status and offers two quick
 * actions (laser ON/OFF, next pattern) without having to open the app.
 * The system rarely updates it automatically (30 minutes at the
 * minimum) -- for live data, run the background service from the
 * settings, which updates the widget on every status poll.
 */
class LaserWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = buildBaseViews(context, widgetId)
            manager.updateAppWidget(widgetId, views)

            // Fetch the current status in a short background thread.
            Thread {
                try {
                    val settings = SettingsRepository(context.applicationContext)
                    val host = runBlocking { settings.hostFlow.first() }
                    val api = CatLaserApi { host }
                    val status = runBlocking { api.getStatus() }

                    val updated = buildBaseViews(context, widgetId)
                    val laserText = if (status.laserRequested) "ON" else "OFF"
                    updated.setTextViewText(R.id.widget_status, "$laserText · ${status.patternName()}")
                    manager.updateAppWidget(widgetId, updated)
                } catch (_: Exception) {
                    // no connection -- base views (placeholder text) stay as they are
                }
            }.start()
        }

        private fun buildBaseViews(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_laser)

            val openIntent = Intent(context, MainActivity::class.java)
            val openPending = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_title, openPending)

            val toggleIntent = Intent(context, WidgetActionReceiver::class.java)
                .setAction(WidgetActionReceiver.ACTION_TOGGLE_LASER)
            val togglePending = PendingIntent.getBroadcast(
                context, widgetId, toggleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_btn_laser, togglePending)

            val nextIntent = Intent(context, WidgetActionReceiver::class.java)
                .setAction(WidgetActionReceiver.ACTION_NEXT_PATTERN)
            val nextPending = PendingIntent.getBroadcast(
                context, widgetId + 100_000, nextIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_btn_next, nextPending)

            return views
        }
    }
}
