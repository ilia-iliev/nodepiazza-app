package com.nodepiazza

import kotlinx.serialization.Serializable

@Serializable
data class Interest(val id: String, val text: String)

/**
 * One match-eligible peer, identified by a stable [deviceId] that persists across BLE address
 * rotations. [addresses] holds every BLE address we've observed for this peer so we can dedupe
 * scan hits and route outbound writes to a known-good link. [connected] is true while we hold
 * at least one open GATT client to the peer; [lastSeenMs] is updated on every fresh signal
 * (scan hit, write ack, inbound chat) and lets the chat UI render an out-of-range indicator.
 */
data class Peer(
    val deviceId: String,
    val addresses: Set<String>,
    val label: String,
    val matched: Boolean,
    val matchReason: String? = null,
    val connected: Boolean = false,
    val lastSeenMs: Long = 0L,
)

enum class ChatSender { Me, Them }

data class ChatMessage(
    val sender: ChatSender,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
)
