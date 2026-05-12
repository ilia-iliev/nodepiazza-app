@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nodepiazza.phase3.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nodepiazza.phase3.Services
import com.nodepiazza.phase3.models.ModelEntry
import kotlinx.coroutines.launch

@Composable
fun ModelPickerChip(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val selected by Services.modelPrefs.selectedModelName.collectAsStateWithLifecycle(initialValue = null)
    var sheetOpen by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<ModelEntry>>(emptyList()) }

    LaunchedEffect(sheetOpen) {
        if (sheetOpen) models = Services.modelRegistry.listModels()
    }

    TextButton(onClick = { sheetOpen = true }, modifier = modifier) {
        Text(
            shortModelName(selected) ?: "Pick model",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 110.dp),
        )
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = "Change model",
            modifier = Modifier.size(18.dp),
        )
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            ModelPickerList(
                models = models,
                selectedName = selected,
                onSelect = { name ->
                    scope.launch {
                        Services.modelPrefs.setSelectedModelName(name)
                        sheetOpen = false
                    }
                },
            )
        }
    }
}

private fun shortModelName(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return name.substringBeforeLast('.')
}

@Composable
private fun ModelPickerList(
    models: List<ModelEntry>,
    selectedName: String?,
    onSelect: (String) -> Unit,
) {
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
        if (models.isEmpty()) {
            item {
                Text(
                    "No models in folder yet. Drop a .tflite / .task / .litertlm file in, " +
                        "or wait for the default to finish downloading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(models, key = { it.path }) { entry ->
            val isSelected = entry.name == selectedName
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(entry.name) }
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
    }
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    val mb = bytes / 1_000_000.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else -> "$bytes B"
    }
}
