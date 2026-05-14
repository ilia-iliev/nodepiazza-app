package com.nodepiazza.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodepiazza.Services
import com.nodepiazza.mlmodels.ModelBootstrap
import com.nodepiazza.mlmodels.ModelDownloadState

/**
 * Surfaces model state at the top of the main screen so a fresh-install user can tell at a
 * glance whether anything is happening. Hidden once a model is selected and no download is active.
 */
@Composable
fun ModelStatusBanner(modifier: Modifier = Modifier) {
    val models by Services.models.collectAsStateWithLifecycle()
    val download by Services.downloadState.collectAsStateWithLifecycle()
    val selected by Services.modelPrefs.selectedModelName.collectAsStateWithLifecycle(initialValue = null)
    val ctx = LocalContext.current
    var cellularDialog by remember { mutableStateOf(false) }
    var spaceDialog by remember { mutableStateOf<DownloadPlan.NotEnoughSpace?>(null) }

    val hasSelectedModel = selected != null && models.any { it.name == selected }
    val activeDownload = download is ModelDownloadState.Running ||
        download is ModelDownloadState.WaitingForNetwork
    val needsAttention = download is ModelDownloadState.Failed ||
        download is ModelDownloadState.OutOfSpace
    if (hasSelectedModel && !activeDownload && !needsAttention) return

    val onTapDownload = {
        when (val plan = planDefaultDownload(ctx)) {
            DownloadPlan.EnqueueNow -> confirmAndEnqueue(ctx, requireUnmetered = false)
            DownloadPlan.NeedsCellularConsent -> cellularDialog = true
            is DownloadPlan.NotEnoughSpace -> spaceDialog = plan
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (val d = download) {
                is ModelDownloadState.Running -> RunningContent(d)
                ModelDownloadState.WaitingForNetwork -> WaitingContent(onDownloadNow = onTapDownload)
                ModelDownloadState.OutOfSpace -> OutOfSpaceContent()
                ModelDownloadState.Failed -> FailedContent(onRetry = onTapDownload)
                else -> NoModelContent(onDownload = onTapDownload)
            }
        }
    }

    if (cellularDialog) {
        AlertDialog(
            onDismissRequest = { cellularDialog = false },
            title = { Text("Download over cellular?") },
            text = {
                Text(
                    "You're not on Wi-Fi. The default model is about " +
                        formatSize(ModelBootstrap.DEFAULT_MODEL_APPROX_BYTES) +
                        " — your carrier may charge you. Download anyway?",
                )
            },
            confirmButton = {
                Button(onClick = {
                    cellularDialog = false
                    confirmAndEnqueue(ctx, requireUnmetered = false)
                }) { Text("Download") }
            },
            dismissButton = {
                OutlinedButton(onClick = { cellularDialog = false }) { Text("Wait for Wi-Fi") }
            },
        )
    }
    spaceDialog?.let { plan ->
        AlertDialog(
            onDismissRequest = { spaceDialog = null },
            title = { Text("Not enough space") },
            text = {
                Text(
                    "The default model needs about ${formatSize(plan.needed)} free, but only " +
                        "${formatSize(plan.free)} is available. Free up space, then try again.",
                )
            },
            confirmButton = { Button(onClick = { spaceDialog = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun RunningContent(state: ModelDownloadState.Running) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Downloading model", style = MaterialTheme.typography.titleSmall)
    }
    Spacer(Modifier.height(6.dp))
    val pct = if (state.total > 0) state.bytes.toFloat() / state.total else null
    if (pct != null) {
        LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(4.dp))
    Text(
        if (state.total > 0)
            "${formatSize(state.bytes)} / ${formatSize(state.total)}"
        else
            "Starting…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WaitingContent(onDownloadNow: () -> Unit) {
    Text("Default model queued", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Will start automatically on Wi-Fi (~" +
            formatSize(ModelBootstrap.DEFAULT_MODEL_APPROX_BYTES) + ").",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onDownloadNow) { Text("Download now") }
    }
}

@Composable
private fun NoModelContent(onDownload: () -> Unit) {
    Text("No model installed", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Get " + ModelBootstrap.DEFAULT_MODEL_DISPLAY_NAME +
            " (~" + formatSize(ModelBootstrap.DEFAULT_MODEL_APPROX_BYTES) + ").",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Button(onClick = onDownload) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text("Download")
    }
}

@Composable
private fun FailedContent(onRetry: () -> Unit) {
    Text(
        "Model download failed",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(6.dp))
    Button(onClick = onRetry) { Text("Retry") }
}

@Composable
private fun OutOfSpaceContent() {
    Text(
        "Not enough space for the model",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "About ${formatSize(ModelBootstrap.DEFAULT_MODEL_APPROX_BYTES)} is required. " +
            "Free up storage and tap the model picker to retry.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
