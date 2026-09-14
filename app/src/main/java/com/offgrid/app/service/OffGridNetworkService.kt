package com.offgrid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.offgrid.app.MainActivity
import com.offgrid.app.R
import com.offgrid.app.data.runtime.OffGridRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the local OffGrid mesh alive while the UI is backgrounded or the screen is locked. */
class OffGridNetworkService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        OffGridRuntime.initialize(applicationContext)
        serviceScope.launch {
            runCatching {
                val runtime = OffGridRuntime
                runtime.transport.start(runtime.identity.nodeId, runtime.identity.displayName)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { OffGridRuntime.transport.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OffGridNetworkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OffGrid mesh active")
            .setContentText("Discovering nearby devices and relaying messages")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop mesh", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OffGrid mesh",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps OffGrid offline discovery and messaging active"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.offgrid.app.service.STOP_MESH"
        private const val CHANNEL_ID = "offgrid_mesh"
        private const val NOTIFICATION_ID = 4101
    }
}
