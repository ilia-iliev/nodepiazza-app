@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nodepiazza.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodepiazza.Services
import com.nodepiazza.mlmodels.ModelCatalog
import com.nodepiazza.mlmodels.ModelDownloadState
import com.nodepiazza.mlmodels.ModelSpec

/**
 * Surfaces model state at the top of the main screen so a fresh-install user can tell at a glance
 * whether anything is happening. Hidden once a model is installed and selected and nothing is
 * downloading or failed.
 */
@Composable
fun ModelStatusBanner(modifier: Modifier = Modifier) {
    val models by Services.models.collectAsStateWithLifecycle()
    val downloads by Services.downloadStates.collectAsStateWithLifecycle()
    val selected by Services.modelPrefs.selectedModelName.collectAsStateWithLifecycle(initialValue = null)
    val onDownload = rememberModelDownloadAction()
    var sheetOpen by remember { mutableStateOf(false) }

    val hasSelectedModel = selected != null && models.any { it.name == selected }
    fun stateOf(spec: ModelSpec) = downloads[spec.id] ?: ModelDownloadState.Idle
    val active = ModelCatalog.ALL.firstOrNull {
        stateOf(it) is ModelDownloadState.Running || stateOf(it) is ModelDownloadState.WaitingForNetwork
    }
    val problem = ModelCatalog.ALL.firstOrNull {
        stateOf(it) is ModelDownloadState.Failed || stateOf(it) is ModelDownloadState.OutOfSpace
    }
    if (hasSelectedModel && active == null && problem == null) return

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when {
                active != null -> ActiveContent(active, stateOf(active))
                problem != null -> ProblemContent(problem, stateOf(problem), onRetry = { onDownload(problem) })
                else -> NoModelContent(onChoose = { sheetOpen = true })
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            ModelPickerSheet(onModelSelected = { sheetOpen = false })
        }
    }
}

@Composable
private fun ActiveContent(spec: ModelSpec, state: ModelDownloadState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Downloading ${spec.displayName}", style = MaterialTheme.typography.titleSmall)
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
                if (state.total > 0) "${formatSize(state.bytes)} / ${formatSize(state.total)}"
                else "Starting…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                "Will start automatically on Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProblemContent(spec: ModelSpec, state: ModelDownloadState, onRetry: () -> Unit) {
    val isSpace = state is ModelDownloadState.OutOfSpace
    Text(
        if (isSpace) "Not enough space for ${spec.displayName}" else "${spec.displayName} download failed",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        if (isSpace)
            "About ${formatSize(spec.approxBytes)} is required. Free up storage, then retry."
        else
            "Check your connection and try again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Button(onClick = onRetry) { Text("Retry") }
}

@Composable
private fun NoModelContent(onChoose: () -> Unit) {
    Text("No model installed", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Pick a model to download — matching runs on-device once it's ready.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Button(onClick = onChoose) { Text("Choose a model") }
}
