package com.offgrid.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.offgrid.app.MainActivity
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageType
import com.offgrid.app.data.runtime.OffGridRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the local OffGrid mesh alive while the UI is backgrounded or the screen is locked. */
class OffGridNetworkService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var explicitStop = false
    private var incomingCollectorStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildServiceNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else 0,
        )

        OffGridRuntime.initialize(applicationContext)
        startIncomingCollector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            explicitStop = true
            stopSelf()
            return START_NOT_STICKY
        }

        OffGridRuntime.initialize(applicationContext)
        serviceScope.launch {
            runCatching {
                val runtime = OffGridRuntime
                runtime.transport.start(runtime.identity.nodeId, runtime.identity.displayName)
                runtime.transport.discoverDevices()
            }
        }

        // Android may reclaim a service process. START_STICKY asks the system to recreate it;
        // on recreation onCreate() rebuilds the notification and reattaches the mesh collector.
        return START_STICKY
    }

    override fun onDestroy() {
        // Do not tear down the process-wide transport merely because Android recreated/destroyed
        // the service instance. The explicit stop action is the only intentional shutdown path.
        if (explicitStop) runCatching { OffGridRuntime.transport.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startIncomingCollector() {
        if (incomingCollectorStarted) return
        incomingCollectorStarted = true
        serviceScope.launch {
            runCatching {
                OffGridRuntime.transport.incomingMessages.collect { message ->
                    showMessageNotification(message)
                }
            }
        }
    }

    private fun showMessageNotification(message: Message) {
        if (message.senderId == OffGridRuntime.identity.nodeId) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val peerName = OffGridRuntime.transport.discoveredNodes.value
            .firstOrNull { it.logicalId == message.senderId || it.id == message.senderId }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: message.senderId

        val title = when (message.type) {
            MessageType.EMERGENCY -> "$peerName is in an emergency"
            else -> "$peerName texted you"
        }

        val openIntent = PendingIntent.getActivity(
            this,
            message.id.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.content))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (message.type == MessageType.EMERGENCY) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
            .also { notification ->
                getSystemService(NotificationManager::class.java).notify(message.id.hashCode(), notification)
            }
    }

    private fun buildServiceNotification(): Notification =
        NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OffGrid mesh active")
            .setContentText("Offline discovery and message relay are active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL_ID, "Mesh status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Required system status for the active OffGrid mesh"
                setSound(null, null)
                enableVibration(false)
            },
        )

        // v2 deliberately uses a fresh channel so a previously-disabled/badly configured
        // Messages channel cannot silently keep suppressing notifications after an app update.
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL_ID, "OffGrid messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming OffGrid messages and emergency alerts"
                enableVibration(true)
                setShowBadge(true)
            },
        )
    }

    companion object {
        const val ACTION_STOP = "com.offgrid.app.service.STOP_MESH"
        private const val SERVICE_CHANNEL_ID = "offgrid_mesh_status"
        private const val MESSAGE_CHANNEL_ID = "offgrid_messages_v2"
        private const val NOTIFICATION_ID = 4101
    }
}
