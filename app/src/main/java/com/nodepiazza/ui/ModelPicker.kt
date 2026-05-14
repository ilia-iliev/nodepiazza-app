@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nodepiazza.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodepiazza.Services
import com.nodepiazza.mlmodels.ModelCatalog
import com.nodepiazza.mlmodels.ModelDownloadState
import com.nodepiazza.mlmodels.ModelEntry
import com.nodepiazza.mlmodels.ModelSpec
import kotlinx.coroutines.launch

@Composable
fun ModelPickerChip(modifier: Modifier = Modifier) {
    val selected by Services.modelPrefs.selectedModelName.collectAsStateWithLifecycle(initialValue = null)
    val downloads by Services.downloadStates.collectAsStateWithLifecycle()
    val models by Services.models.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }

    TextButton(onClick = { sheetOpen = true }, modifier = modifier) {
        Text(
            chipLabel(selected, models, downloads),
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
            ModelPickerSheet(onModelSelected = { sheetOpen = false })
        }
    }
}

private fun chipLabel(
    selected: String?,
    models: List<ModelEntry>,
    downloads: Map<String, ModelDownloadState>,
): String {
    val installed = ModelCatalog.byFilename(selected)
        ?.takeIf { models.any { m -> m.name == it.filename } }
    if (installed != null) return installed.displayName

    val running = downloads.values.filterIsInstance<ModelDownloadState.Running>().firstOrNull()
    if (running != null) {
        val pct = if (running.total > 0) (running.bytes * 100 / running.total).toInt() else 0
        return "Downloading $pct%"
    }
    if (downloads.values.any { it is ModelDownloadState.WaitingForNetwork }) return "Waiting for Wi-Fi"
    return if (models.isEmpty()) "No model" else "Pick model"
}

/**
 * The model list shown in a bottom sheet. Reads everything it needs from [Services], so both the
 * top-bar chip and the main-screen banner can host it. [onModelSelected] fires when the user
 * activates an installed model (so the host can close the sheet); downloads and deletes leave it
 * open so progress stays visible.
 */
@Composable
internal fun ModelPickerSheet(onModelSelected: () -> Unit) {
    val scope = rememberCoroutineScope()
    val models by Services.models.collectAsStateWithLifecycle()
    val selected by Services.modelPrefs.selectedModelName.collectAsStateWithLifecycle(initialValue = null)
    val downloads by Services.downloadStates.collectAsStateWithLifecycle()
    val onDownload = rememberModelDownloadAction()

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
        items(ModelCatalog.ALL, key = { it.id }) { spec ->
            ModelRow(
                spec = spec,
                installed = models.any { it.name == spec.filename },
                isSelected = selected == spec.filename,
                state = downloads[spec.id] ?: ModelDownloadState.Idle,
                onSelect = {
                    scope.launch {
                        Services.modelPrefs.setSelectedModelName(spec.filename)
                        onModelSelected()
                    }
                },
                onDownload = { onDownload(spec) },
                onDelete = { scope.launch { Services.deleteModel(spec) } },
            )
        }
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    installed: Boolean,
    isSelected: Boolean,
    state: ModelDownloadState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val downloading = state is ModelDownloadState.Running ||
        state is ModelDownloadState.WaitingForNetwork
    val rowModifier = if (installed && !downloading) {
        Modifier.fillMaxWidth().clickable(onClick = onSelect)
    } else {
        Modifier.fillMaxWidth()
    }
    Column(rowModifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    spec.displayName,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    statusLine(spec, installed, state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                installed && isSelected -> {
                    Icon(Icons.Default.Check, contentDescription = "Active model")
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete ${spec.displayName}")
                    }
                }
                installed -> {
                    TextButton(onClick = onSelect) { Text("Use") }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete ${spec.displayName}")
                    }
                }
                downloading -> OutlinedButton(onClick = onDelete) { Text("Cancel") }
                else -> Button(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (state is ModelDownloadState.Failed) "Retry" else "Download")
                }
            }
        }
        if (state is ModelDownloadState.Running) {
            Spacer(Modifier.height(6.dp))
            val pct = if (state.total > 0) state.bytes.toFloat() / state.total else null
            if (pct != null) {
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun statusLine(spec: ModelSpec, installed: Boolean, state: ModelDownloadState): String =
    when {
        installed -> "Installed • ${formatSize(spec.approxBytes)}"
        state is ModelDownloadState.Running ->
            if (state.total > 0) "${formatSize(state.bytes)} / ${formatSize(state.total)}"
            else "Starting…"
        state is ModelDownloadState.WaitingForNetwork -> "Waiting for Wi-Fi"
        state is ModelDownloadState.OutOfSpace -> "Not enough space"
        state is ModelDownloadState.Failed -> "Download failed"
        else -> "~${formatSize(spec.approxBytes)} • ${spec.description}"
    }
