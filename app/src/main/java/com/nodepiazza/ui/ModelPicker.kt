@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nodepiazza.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodepiazza.Services
import com.nodepiazza.mlmodels.ModelBootstrap
import com.nodepiazza.mlmodels.ModelDownloadState
import com.nodepiazza.mlmodels.ModelEntry
import kotlinx.coroutines.launch

@Composable
fun ModelPickerChip(modifier: Modifier = Modifier) {
    val selected by Services.modelPrefs.selectedModelName.collectAsStateWithLifecycle(initialValue = null)
    val download by Services.downloadState.collectAsStateWithLifecycle()
    val models by Services.models.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }

    val label = chipLabel(selected, models, download)

    TextButton(onClick = { sheetOpen = true }, modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = "Change model",
            modifier = Modifier.size(18.dp),
        )
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            ModelPickerSheet(
                models = models,
                selectedName = selected,
                download = download,
                onPicked = { sheetOpen = false },
            )
        }
    }
}

private fun chipLabel(
    selected: String?,
    models: List<ModelEntry>,
    download: ModelDownloadState,
): String {
    val name = selected?.takeIf { it.isNotBlank() && models.any { m -> m.name == it } }
    if (name != null) return name.substringBeforeLast('.')
    return when (val d = download) {
        is ModelDownloadState.Running -> {
            val pct = if (d.total > 0) (d.bytes * 100 / d.total).toInt() else 0
            "Downloading $pct%"
        }
        ModelDownloadState.WaitingForNetwork -> "Waiting for Wi-Fi"
        ModelDownloadState.OutOfSpace -> "Not enough space"
        ModelDownloadState.Failed -> "Download failed"
        else -> if (models.isNotEmpty()) "Pick model" else "No model"
    }
}

@Composable
private fun ModelPickerSheet(
    models: List<ModelEntry>,
    selectedName: String?,
    download: ModelDownloadState,
    onPicked: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cellularDialog by remember { mutableStateOf(false) }
    var spaceDialog by remember { mutableStateOf<DownloadPlan.NotEnoughSpace?>(null) }

    val defaultInstalled = models.any { it.name == ModelBootstrap.DEFAULT_MODEL_FILENAME }
    val activeDownload = download is ModelDownloadState.Running ||
        download is ModelDownloadState.WaitingForNetwork

    val onTapDownload = {
        when (val plan = planDefaultDownload(ctx)) {
            DownloadPlan.EnqueueNow -> confirmAndEnqueue(ctx, requireUnmetered = false)
            DownloadPlan.NeedsCellularConsent -> cellularDialog = true
            is DownloadPlan.NotEnoughSpace -> spaceDialog = plan
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(
                "Choose model",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (models.isEmpty() && !activeDownload) {
            item {
                Text(
                    "No models installed yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        items(models, key = { it.path }) { entry ->
            val isSelected = entry.name == selectedName
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            Services.modelPrefs.setSelectedModelName(entry.name)
                            onPicked()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        formatSize(entry.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = "selected")
                }
            }
        }

        if (activeDownload) {
            item { DownloadProgressRow(download) }
        }

        if (download is ModelDownloadState.OutOfSpace) {
            item { OutOfSpaceRow() }
        }

        if (!defaultInstalled && !activeDownload) {
            item { DefaultDownloadRow(onDownload = onTapDownload) }
        }
    }

    if (cellularDialog) {
        CellularWarningDialog(
            onConfirm = {
                cellularDialog = false
                confirmAndEnqueue(ctx, requireUnmetered = false)
            },
            onDismiss = { cellularDialog = false },
        )
    }
    spaceDialog?.let { plan ->
        NotEnoughSpaceDialog(plan = plan, onDismiss = { spaceDialog = null })
    }
}

@Composable
private fun DownloadProgressRow(state: ModelDownloadState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(ModelBootstrap.DEFAULT_MODEL_DISPLAY_NAME, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        when (state) {
            is ModelDownloadState.Running -> {
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
            ModelDownloadState.WaitingForNetwork -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    "Waiting for Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun OutOfSpaceRow() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            "Not enough space",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "Free up some storage and try again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DefaultDownloadRow(onDownload: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(ModelBootstrap.DEFAULT_MODEL_DISPLAY_NAME, style = MaterialTheme.typography.bodyMedium)
        Text(
            "~${formatSize(ModelBootstrap.DEFAULT_MODEL_APPROX_BYTES)} • the recommended on-device model",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Download")
            }
        }
    }
}

@Composable
private fun CellularWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download over cellular?") },
        text = {
            Text(
                "You're not on Wi-Fi. The default model is about " +
                    formatSize(ModelBootstrap.DEFAULT_MODEL_APPROX_BYTES) +
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
                "The default model needs about ${formatSize(plan.needed)} free, but only " +
                    "${formatSize(plan.free)} is available. Free up space, then try again.",
            )
        },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
    )
}
