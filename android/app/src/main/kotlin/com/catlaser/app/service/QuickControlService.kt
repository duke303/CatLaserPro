package com.catlaser.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.catlaser.app.MainActivity
import com.catlaser.app.R
import com.catlaser.app.data.SettingsRepository
import com.catlaser.app.network.CatLaserApi
import com.catlaser.app.network.CatLaserStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Keeps a persistent notification with quick access to
 * laser ON/OFF and "next pattern" -- works even when the app itself
 * is closed. Polls /status at a longer interval (5 s) to keep the
 * display current without needlessly loading the ESP32.
 */
class QuickControlService : Service() {

    companion object {
        const val CHANNEL_ID = "catlaser_quick"
        const val NOTIF_ID = 1001
        const val ACTION_TOGGLE_LASER = "com.catlaser.app.TOGGLE_LASER"
        const val ACTION_NEXT_PATTERN = "com.catlaser.app.NEXT_PATTERN"
        const val ACTION_STOP = "com.catlaser.app.STOP_SERVICE"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsRepository
    private lateinit var api: CatLaserApi
    private var pollJob: Job? = null
    private var lastStatus: CatLaserStatus? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(applicationContext)
        var host = SettingsRepository.DEFAULT_HOST
        api = CatLaserApi { host }
        scope.launch {
            settings.hostFlow.collect { host = it }
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_LASER -> scope.launch {
                runCatching {
                    val cur = lastStatus?.laserRequested ?: false
                    api.setLaser(!cur)
                    refreshAndNotify()
                }
            }
            ACTION_NEXT_PATTERN -> scope.launch {
                runCatching {
                    api.playlistNext()
                    refreshAndNotify()
                }
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIF_ID, buildNotification(lastStatus))

        if (pollJob == null) {
            pollJob = scope.launch {
                while (isActive) {
                    refreshAndNotify()
                    delay(5000)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun refreshAndNotify() {
        runCatching { api.getStatus() }.onSuccess {
            lastStatus = it
            val notif = buildNotification(it)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(status: CatLaserStatus?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = Intent(this, QuickControlService::class.java).setAction(ACTION_TOGGLE_LASER)
        val togglePending = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = Intent(this, QuickControlService::class.java).setAction(ACTION_NEXT_PATTERN)
        val nextPending = PendingIntent.getService(
            this, 2, nextIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val laserOn = status?.laserRequested ?: false
        val patternText = status?.patternName() ?: "--"
        val content = if (status == null) "No connection" else "Pattern: $patternText"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (laserOn) "Laser ON" else "Laser OFF")
            .setContentText(content)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, if (laserOn) "Laser OFF" else "Laser ON", togglePending)
            .addAction(0, "Next pattern", nextPending)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
