package com.nodepiazza

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Shared notification-channel registration. minSdk is 26, so [NotificationChannel] is always
 * available — no SDK guard needed. Idempotent: a re-registration with the same id is a no-op.
 */
object NotificationChannels {
    fun ensure(
        context: Context,
        id: String,
        name: String,
        importance: Int,
        description: String? = null,
        showBadge: Boolean = true,
    ) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, importance).apply {
            if (description != null) this.description = description
            setShowBadge(showBadge)
        }
        mgr.createNotificationChannel(channel)
    }
}
