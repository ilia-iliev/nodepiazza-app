package com.nodepiazza.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nodepiazza.Services
import com.nodepiazza.mlmodels.ModelBootstrap
import com.nodepiazza.mlmodels.ModelSpec
import java.io.File

/**
 * Decision returned by [planDownload] — keeps preflight policy out of the composables so the
 * picker and the banner share one implementation.
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
 * Decide what (if anything) to enqueue for [spec] right now. UI surfaces call [confirmAndEnqueue]
 * after the user has acknowledged the relevant dialog.
 */
private fun planDownload(context: Context, spec: ModelSpec): DownloadPlan {
    val folder = Services.modelsFolder
    val needed = spec.approxBytes
    val free = folder.usableSpaceOrZero()
    if (free < needed + SPACE_SAFETY_MARGIN) {
        return DownloadPlan.NotEnoughSpace(free = free, needed = needed)
    }
    return if (isUnmetered(context)) DownloadPlan.EnqueueNow else DownloadPlan.NeedsCellularConsent
}

private fun confirmAndEnqueue(context: Context, spec: ModelSpec, requireUnmetered: Boolean) {
    ModelBootstrap.enqueue(context, Services.modelsFolder, spec, requireUnmetered)
}

private fun isUnmetered(context: Context): Boolean {
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

/**
 * Hosts the cellular-consent and not-enough-space dialogs and returns the lambda that kicks off a
 * model download — running [planDownload] for the chosen [ModelSpec] and either enqueueing
 * directly or surfacing the relevant dialog. Shared by the picker sheet and the main-screen banner
 * so the preflight UX stays identical in both.
 */
@Composable
internal fun rememberModelDownloadAction(): (ModelSpec) -> Unit {
    val ctx = LocalContext.current
    var cellularDialog by remember { mutableStateOf<ModelSpec?>(null) }
    var spaceDialog by remember { mutableStateOf<DownloadPlan.NotEnoughSpace?>(null) }

    cellularDialog?.let { spec ->
        CellularWarningDialog(
            spec = spec,
            onConfirm = {
                cellularDialog = null
                confirmAndEnqueue(ctx, spec, requireUnmetered = false)
            },
            onDismiss = { cellularDialog = null },
        )
    }
    spaceDialog?.let { plan ->
        NotEnoughSpaceDialog(plan = plan, onDismiss = { spaceDialog = null })
    }

    return { spec ->
        when (val plan = planDownload(ctx, spec)) {
            DownloadPlan.EnqueueNow -> confirmAndEnqueue(ctx, spec, requireUnmetered = false)
            DownloadPlan.NeedsCellularConsent -> cellularDialog = spec
            is DownloadPlan.NotEnoughSpace -> spaceDialog = plan
        }
    }
}

@Composable
private fun CellularWarningDialog(spec: ModelSpec, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download over cellular?") },
        text = {
            Text(
                "You're not on Wi-Fi. ${spec.displayName} is about " +
                    formatSize(spec.approxBytes) +
                    " — your carrier may charge you. Download anyway?",
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Download") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Wait for Wi-Fi") } },
    )
}

@Composable
private fun NotEnoughSpaceDialog(plan: DownloadPlan.NotEnoughSpace, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Not enough space") },
        text = {
            Text(
                "That model needs about ${formatSize(plan.needed)} free, but only " +
                    "${formatSize(plan.free)} is available. Free up space, then try again.",
            )
        },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
    )
}
