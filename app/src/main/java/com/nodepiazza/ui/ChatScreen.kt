@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nodepiazza.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
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
    var showBlockDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
                title = { Text(peer?.displayTitle ?: "chat") },
                navigationIcon = {
                    IconButton(onClick = { coordinator.closeChat() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "more options")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Report user") },
                            onClick = {
                                menuOpen = false
                                showReportDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Block user") },
                            onClick = {
                                menuOpen = false
                                showBlockDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove chat (testing)") },
                            onClick = {
                                menuOpen = false
                                ble.removeChat(deviceId)
                                coordinator.closeChat()
                            },
                        )
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
    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = { Text("Block ${peer?.label ?: "this user"}?") },
            text = {
                Text(
                    "You won't see or be matched with this user again, even after " +
                        "restarting the app.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBlockDialog = false
                    ble.blockPeer(deviceId)
                    coordinator.closeChat()
                }) { Text("Block") }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) { Text("Cancel") }
            },
        )
    }
    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Report ${peer?.label ?: "this user"}?") },
            text = {
                Text(
                    "This opens an email to our moderation team with the recent conversation " +
                        "attached for review. The user will also be blocked.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showReportDialog = false
                    sendReportEmail(context, deviceId, peer?.label, messages)
                    ble.blockPeer(deviceId)
                    coordinator.closeChat()
                }) { Text("Report & block") }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
            },
        )
    }
    }
}

/** Where abuse reports are sent. Triaged manually — there is no server in this app. */
private const val REPORT_EMAIL = "ilia.agentov@gmail.com"

/**
 * Compose an abuse report email pre-filled with the peer's device id and the recent transcript,
 * then hand off to the user's email app. No data leaves the device until the user hits send.
 */
private fun sendReportEmail(
    context: Context,
    deviceId: String,
    label: String?,
    messages: List<ChatMessage>,
) {
    val transcript = messages.takeLast(20).joinToString("\n") { msg ->
        val who = if (msg.sender == ChatSender.Me) "me" else "them"
        "[$who] ${msg.text}"
    }.ifBlank { "(no messages exchanged)" }
    val body = buildString {
        append("User reported: ${label ?: "unknown"}\n")
        append("Device id: $deviceId\n\n")
        append("Reason (please describe what happened):\n\n\n")
        append("--- Recent conversation ---\n")
        append(transcript)
    }
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "nodepiazza abuse report")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Report user")) }
}

private sealed class ConnectionStatus {
    data object Connected : ConnectionStatus()
    data class WaitingForPeer(val label: String) : ConnectionStatus()
    data class OutOfRange(val idleMs: Long) : ConnectionStatus()
    data object Gone : ConnectionStatus()

    /** Placeholder shown when sending is disabled — only the non-sendable states reach this. */
    fun inputHint(): String = when (this) {
        is OutOfRange -> "Peer is out of range"
        Gone -> "Peer is no longer reachable"
        else -> "Message"
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
