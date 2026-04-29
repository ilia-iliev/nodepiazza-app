@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nodepiazza.phase3.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodepiazza.phase3.AppState
import com.nodepiazza.phase3.Peer

@Composable
fun MainScreen(state: AppState) {
    val prompts by state.prompts.collectAsStateWithLifecycle()
    val peers by state.peers.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("nodepiazza") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                item {
                    SectionLabel("What you seek")
                }
                items(prompts, key = { it.id }) { prompt ->
                    PromptRow(prompt.text, onDelete = { state.removePrompt(prompt.id) })
                }
                if (prompts.isEmpty()) {
                    item {
                        Text(
                            "Add a prompt describing what you're looking for.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
                item { SectionLabel("Nearby") }
                val sortedPeers = peers.values.sortedByDescending { it.similarity }
                items(sortedPeers, key = { it.address }) { peer ->
                    PeerRow(peer, onOpen = { if (peer.matched) state.openChat(peer.address) })
                }
                if (sortedPeers.isEmpty()) {
                    item {
                        Text(
                            "Scanning for nearby peers…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            PromptComposer(
                value = draft,
                onValueChange = { draft = it },
                onSubmit = {
                    state.addPrompt(draft)
                    draft = ""
                },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PromptRow(text: String, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = "delete")
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, onOpen: () -> Unit) {
    val color = if (peer.matched) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(peer.label, fontWeight = FontWeight.Medium)
                val status = when {
                    !peer.connected -> "connecting…"
                    peer.matched -> "match — tap to chat"
                    peer.similarity > 0f -> "similarity ${"%.2f".format(peer.similarity)}"
                    else -> "handshaking…"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (peer.similarity > 0f) {
                Text("%.2f".format(peer.similarity), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PromptComposer(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("What do you seek?") },
            singleLine = false,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSubmit, enabled = value.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "add prompt")
        }
    }
}
