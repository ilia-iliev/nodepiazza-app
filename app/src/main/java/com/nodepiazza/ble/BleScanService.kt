package com.nodepiazza.ble

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
import com.nodepiazza.AppForeground
import com.nodepiazza.AppState
import com.nodepiazza.Services
import com.nodepiazza.ui.MainActivity

class BleScanService : Service() {

    override fun onCreate() {
        super.onCreate()
        val state = AppState.get(this)
        Services.init(this, state)
        ensureChannel(
            id = CHANNEL_ID,
            name = "Nearby scanning",
            importance = NotificationManager.IMPORTANCE_LOW,
            description = "Keeps Bluetooth scanning active in the background",
            showBadge = false,
        )
        ensureChannel(
            id = MATCH_CHANNEL_ID,
            name = "Matches",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Notifies you when a nearby device matches one of your interests",
        )
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

    private fun ensureChannel(
        id: String,
        name: String,
        importance: Int,
        description: String,
        showBadge: Boolean = true,
    ) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, importance).apply {
            this.description = description
            setShowBadge(showBadge)
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
            .setContentText("scanning nearby...")
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
            // A user with the app open already sees the peer in their list; only ping in background.
            if (AppForeground.isForeground) return
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
                "Someone wants to chat about \"$peerInterest\""
            } else {
                "Someone wants to chat"
            }
            val notification = NotificationCompat.Builder(context, MATCH_CHANNEL_ID)
                .setContentTitle("match")
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

        fun cancelMatch(context: Context, deviceId: String) {
            runCatching {
                NotificationManagerCompat.from(context)
                    .cancel(deviceId, MATCH_NOTIFICATION_ID)
            }
        }
    }
}
