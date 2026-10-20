package com.catlaser.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.catlaser.app.data.SettingsRepository
import com.catlaser.app.network.CatLaserApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Receives taps on the widget buttons. Runs in a short background
 * thread (goAsync), because a BroadcastReceiver does not allow network
 * calls on the main thread and has no coroutine scope of its own.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TOGGLE_LASER = "com.catlaser.app.widget.TOGGLE_LASER"
        const val ACTION_NEXT_PATTERN = "com.catlaser.app.widget.NEXT_PATTERN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val settings = SettingsRepository(context.applicationContext)

        Thread {
            try {
                val host = runBlocking { settings.hostFlow.first() }
                val api = CatLaserApi { host }
                runBlocking {
                    when (intent.action) {
                        ACTION_TOGGLE_LASER -> {
                            val status = runCatching { api.getStatus() }.getOrNull()
                            val currentlyOn = status?.laserRequested ?: false
                            api.setLaser(!currentlyOn)
                        }
                        ACTION_NEXT_PATTERN -> api.playlistNext()
                    }
                }
            } catch (_: Exception) {
                // Connection failed -- the widget shows the actual state again
                // at the next regular update.
            } finally {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, LaserWidgetProvider::class.java))
                for (id in ids) LaserWidgetProvider.updateWidget(context, manager, id)
                pendingResult.finish()
            }
        }.start()
    }
}
