package com.nodepiazza.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nodepiazza.Services
import com.nodepiazza.mlmodels.ModelBootstrap
import java.io.File

/**
 * Decision returned by [planDefaultDownload] — keeps preflight policy out of the composables so
 * the picker and the banner share one implementation.
 */
sealed interface DownloadPlan {
    /** Caller should enqueue directly. */
    object EnqueueNow : DownloadPlan
    /** Caller should show the cellular-confirmation dialog. */
    object NeedsCellularConsent : DownloadPlan
    /** Not enough free space — caller should show the [free] / [needed] dialog. */
    data class NotEnoughSpace(val free: Long, val needed: Long) : DownloadPlan
}

/**
 * Decide what (if anything) to enqueue for the default model right now. UI surfaces call
 * [confirmAndEnqueue] after the user has acknowledged the relevant dialog.
 */
fun planDefaultDownload(context: Context): DownloadPlan {
    val folder = Services.modelsFolder
    val needed = ModelBootstrap.DEFAULT_MODEL_APPROX_BYTES
    val free = folder.usableSpaceOrZero()
    if (free < needed + SPACE_SAFETY_MARGIN) {
        return DownloadPlan.NotEnoughSpace(free = free, needed = needed)
    }
    return if (isUnmetered(context)) DownloadPlan.EnqueueNow else DownloadPlan.NeedsCellularConsent
}

fun confirmAndEnqueue(context: Context, requireUnmetered: Boolean) {
    ModelBootstrap.enqueueDefault(context, Services.modelsFolder, requireUnmetered)
}

fun isUnmetered(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

fun formatSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    val mb = bytes / 1_000_000.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else -> "$bytes B"
    }
}

private fun File.usableSpaceOrZero(): Long =
    runCatching { mkdirs(); usableSpace }.getOrDefault(0L)

private const val SPACE_SAFETY_MARGIN = 64L * 1024 * 1024
