package com.nodepiazza.phase3.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nodepiazza.phase3.AppState
import com.nodepiazza.phase3.Services
import com.nodepiazza.phase3.ui.MainActivity

class BleScanService : Service() {

    override fun onCreate() {
        super.onCreate()
        val state = AppState.get(this)
        Services.init(this, state)
        createChannel()
        ensureMatchChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Services.ble.start()
        return START_STICKY
    }

    override fun onDestroy() {
        Services.ble.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nearby scanning",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Bluetooth scanning active in the background"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun ensureMatchChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(MATCH_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            MATCH_CHANNEL_ID,
            "Matches",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifies you when a nearby device matches one of your interests"
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("nodepiazza")
            .setContentText("Scanning for nearby peers")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ble_scan"
        private const val MATCH_CHANNEL_ID = "ble_matches"
        private const val NOTIFICATION_ID = 1
        private const val MATCH_NOTIFICATION_ID = 100

        fun start(context: Context) {
            val intent = Intent(context, BleScanService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleScanService::class.java)
            context.stopService(intent)
        }

        fun notifyMatch(context: Context, deviceId: String, label: String, peerInterest: String?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_CHAT_DEVICE_ID, deviceId)
            }
            val pi = PendingIntent.getActivity(
                context,
                deviceId.hashCode(),
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val body = if (!peerInterest.isNullOrBlank()) {
                "$label: \"$peerInterest\" — tap to chat"
            } else {
                "$label is looking for similar things — tap to chat"
            }
            val notification = NotificationCompat.Builder(context, MATCH_CHANNEL_ID)
                .setContentTitle("New match")
                .setContentText(body)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(deviceId, MATCH_NOTIFICATION_ID, notification)
            }
        }
    }
}
