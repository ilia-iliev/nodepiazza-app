@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nodepiazza.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodepiazza.ChatMessage
import com.nodepiazza.ChatSender
import com.nodepiazza.Peer
import com.nodepiazza.ble.BleCore
import com.nodepiazza.ble.PeerCoordinator
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(coordinator: PeerCoordinator, ble: BleCore, deviceId: String) {
    val chats by coordinator.chats.collectAsStateWithLifecycle()
    val messages = chats[deviceId] ?: emptyList()
    val peers by coordinator.peers.collectAsStateWithLifecycle()
    val peer = peers[deviceId]
    var draft by remember { mutableStateOf("") }
    var showRejectDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val status = peer?.let { connectionStatus(it, nowMs) } ?: ConnectionStatus.Gone
    // Allow sending while waiting too — peer hasn't engaged but the BLE link is up, so the write
    // succeeds and the message lands in their pending chat ready for when they open it.
    val canSend = status == ConnectionStatus.Connected || status is ConnectionStatus.WaitingForPeer

    AppBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(peer?.label ?: "chat") },
                navigationIcon = {
                    IconButton(onClick = { coordinator.closeChat() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRejectDialog = true }) {
                        Icon(Icons.Default.Block, contentDescription = "reject")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ConnectionStatusRow(status)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(messages) { MessageBubble(it) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (canSend) "Message" else status.inputHint()) },
                    singleLine = false,
                    enabled = canSend,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        ble.sendChatMessage(deviceId, draft)
                        draft = ""
                    },
                    enabled = canSend && draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "send")
                }
            }
        }
    }
    if (showRejectDialog) {
        AlertDialog(
            onDismissRequest = { showRejectDialog = false },
            title = { Text("Reject ${peer?.label ?: "this peer"}?") },
            text = {
                Text("You won't be matched with this user for the time being")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRejectDialog = false
                    ble.rejectPeer(deviceId)
                    coordinator.closeChat()
                }) { Text("Reject") }
            },
            dismissButton = {
                TextButton(onClick = { showRejectDialog = false }) { Text("Cancel") }
            },
        )
    }
    }
}

private sealed class ConnectionStatus {
    data object Connected : ConnectionStatus()
    data class WaitingForPeer(val label: String) : ConnectionStatus()
    data class OutOfRange(val idleMs: Long) : ConnectionStatus()
    data object Gone : ConnectionStatus()

    fun inputHint(): String = when (this) {
        Connected -> "Message"
        is WaitingForPeer -> "Message"
        is OutOfRange -> "Peer is out of range"
        Gone -> "Peer is no longer reachable"
    }
}

/**
 * Threshold past which a peer is considered out of range. Android's GATT stack happily reports
 * an open link long after the peer has actually disappeared, so we infer liveness from how
 * recently we've observed the peer (scan hit, write ack, inbound chat).
 */
private const val CONNECTED_STALENESS_MS = 15_000L

private fun connectionStatus(peer: Peer, nowMs: Long): ConnectionStatus {
    val staleness = (nowMs - peer.lastSeenMs).coerceAtLeast(0)
    return when {
        staleness >= CONNECTED_STALENESS_MS -> ConnectionStatus.OutOfRange(idleMs = staleness)
        !peer.peerOpenedChat -> ConnectionStatus.WaitingForPeer(peer.label)
        else -> ConnectionStatus.Connected
    }
}

@Composable
private fun ConnectionStatusRow(status: ConnectionStatus) {
    val (label, dotColor) = when (status) {
        ConnectionStatus.Connected ->
            "Connected" to MaterialTheme.colorScheme.primary
        is ConnectionStatus.WaitingForPeer ->
            "Waiting for ${status.label} to open chat" to MaterialTheme.colorScheme.tertiary
        is ConnectionStatus.OutOfRange ->
            "Last visible — ${formatIdle(status.idleMs)} ago" to
                MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionStatus.Gone ->
            "No longer reachable" to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatIdle(idleMs: Long): String {
    val s = idleMs / 1_000L
    return when {
        s < 60 -> "${s}s"
        s < 3_600 -> "${s / 60}m"
        else -> "${s / 3_600}h"
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isMe = message.sender == ChatSender.Me
    val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bubbleColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(message.text, color = textColor)
        }
    }
}
