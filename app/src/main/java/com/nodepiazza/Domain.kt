package com.nodepiazza

import kotlinx.serialization.Serializable

@Serializable
data class Interest(val id: String, val text: String)

/**
 * One match-eligible peer, identified by a stable [deviceId] that persists across BLE address
 * rotations. [addresses] holds every BLE address we've observed for this peer so we can dedupe
 * scan hits and route outbound writes to a known-good link. [lastSeenMs] is bumped on every
 * fresh signal (scan hit, write ack, inbound chat); the chat UI derives "connected" from how
 * recent it is, because Android's GATT stack happily reports an open link long after the peer
 * has actually disappeared.
 */
data class Peer(
    val deviceId: String,
    val addresses: Set<String>,
    val label: String,
    val matched: Boolean,
    val matchReason: String? = null,
    val lastSeenMs: Long = 0L,
    /**
     * True once the peer has opened a chat with us — signalled by a presence sentinel on the chat
     * char or, as a fallback, any inbound chat frame. Until then, ChatScreen shows "waiting" even
     * if the BLE link itself is up, because our LLM matching them doesn't mean theirs has matched
     * us yet.
     */
    val peerOpenedChat: Boolean = false,
)

enum class ChatSender { Me, Them }

data class ChatMessage(
    val sender: ChatSender,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
)
