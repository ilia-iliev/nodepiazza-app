package com.nodepiazza.ble

import com.nodepiazza.ChatMessage
import com.nodepiazza.ChatSender
import com.nodepiazza.Peer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the published peer/chat state and per-device liveness timestamps. Pure state container: no
 * decision logic, no exclusion sets, no pending queues — those live in [PeerCoordinator]. Every
 * mutation republishes via the StateFlow, and [Peer.lastSeenMs] is kept in sync with [markSeen]
 * so subscribers don't need to read [lastSeen] separately.
 */
internal class PeerStateStore(private val clock: () -> Long) {

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMessage>>> = _chats.asStateFlow()

    private val _activeChatDeviceId = MutableStateFlow<String?>(null)
    val activeChatDeviceId: StateFlow<String?> = _activeChatDeviceId.asStateFlow()

    private val lastSeenByDeviceId = ConcurrentHashMap<String, Long>()

    fun peer(deviceId: String): Peer? = _peers.value[deviceId]
    fun allPeers(): Map<String, Peer> = _peers.value
    fun chatHistory(deviceId: String): List<ChatMessage> = _chats.value[deviceId].orEmpty()
    fun activeChat(): String? = _activeChatDeviceId.value
    fun setActiveChat(deviceId: String?) { _activeChatDeviceId.value = deviceId }
    fun lastSeen(deviceId: String): Long? = lastSeenByDeviceId[deviceId]
    fun forgetLastSeen(deviceId: String) { lastSeenByDeviceId.remove(deviceId) }
    fun clearLastSeen() { lastSeenByDeviceId.clear() }

    /**
     * Register [address] for [deviceId] — extend an existing entry's address set, or create a
     * fresh unmatched peer. [peerOpenedNow] OR-folds into the entry's `peerOpenedChat` flag (the
     * caller knows whether this address corresponds to a buffered presence sentinel).
     */
    fun addOrMergeUnmatched(deviceId: String, address: String, peerOpenedNow: Boolean) {
        markSeen(deviceId)
        _peers.update { current ->
            val existing = current[deviceId]
            val peer = existing?.copy(
                addresses = existing.addresses + address,
                peerOpenedChat = existing.peerOpenedChat || peerOpenedNow,
            ) ?: newUnmatchedPeer(deviceId, address).copy(peerOpenedChat = peerOpenedNow)
            current + (deviceId to peer.withLastSeen(deviceId))
        }
    }

    fun markMatched(deviceId: String, matchReason: String?) {
        update(deviceId) { it.copy(matched = true, matchReason = matchReason) }
    }

    fun markForceMatched(deviceId: String) {
        update(deviceId) { it.copy(matched = true) }
    }

    fun markPeerOpened(deviceId: String) {
        update(deviceId) { it.copy(peerOpenedChat = true) }
    }

    fun appendOutboundChat(deviceId: String, text: String) {
        appendChat(deviceId, ChatMessage(ChatSender.Me, text))
    }

    /**
     * Append an inbound chat and flip matched/peerOpenedChat = true. Inbound chat is definitive
     * proof the peer engaged, so this covers the case where the presence sentinel was dropped
     * (link race, older build). Creates the peer entry if [deviceId] is unknown.
     */
    fun deliverInboundChat(deviceId: String, address: String, text: String) {
        markSeen(deviceId)
        appendChat(deviceId, ChatMessage(ChatSender.Them, text))
        val existing = _peers.value[deviceId]
        if (existing == null) {
            _peers.update {
                it + (deviceId to newMatchedPeer(deviceId, address)
                    .copy(peerOpenedChat = true)
                    .withLastSeen(deviceId))
            }
        } else if (!existing.matched || !existing.peerOpenedChat) {
            update(deviceId) {
                it.copy(matched = true, peerOpenedChat = true).withLastSeen(deviceId)
            }
        }
    }

    /** Drop [address] from the peer's address set. Caller decides whether to also remove the peer. */
    fun shrinkAddresses(deviceId: String, address: String) {
        val peer = _peers.value[deviceId] ?: return
        _peers.update { it + (deviceId to peer.copy(addresses = peer.addresses - address)) }
    }

    /** Empty the address set but keep the entry — used to hold the active-chat peer around. */
    fun clearAddresses(deviceId: String) {
        val peer = _peers.value[deviceId] ?: return
        _peers.update { it + (deviceId to peer.copy(addresses = emptySet())) }
    }

    fun removePeer(deviceId: String) {
        _peers.update { it - deviceId }
        _chats.update { it - deviceId }
        if (_activeChatDeviceId.value == deviceId) _activeChatDeviceId.value = null
    }

    fun markSeen(deviceId: String) {
        lastSeenByDeviceId[deviceId] = clock()
        update(deviceId) { it.withLastSeen(deviceId) }
    }

    private fun appendChat(deviceId: String, message: ChatMessage) {
        _chats.update { current ->
            val existing = current[deviceId].orEmpty()
            current + (deviceId to (existing + message))
        }
    }

    private fun update(deviceId: String, transform: (Peer) -> Peer) {
        _peers.update { current ->
            val existing = current[deviceId] ?: return@update current
            current + (deviceId to transform(existing))
        }
    }

    private fun Peer.withLastSeen(deviceId: String): Peer {
        val seen = lastSeenByDeviceId[deviceId] ?: lastSeenMs
        return if (lastSeenMs == seen) this else copy(lastSeenMs = seen)
    }

    private fun newUnmatchedPeer(deviceId: String, address: String) = Peer(
        deviceId = deviceId,
        addresses = setOf(address),
        label = labelFor(address),
        matched = false,
    )

    private fun newMatchedPeer(deviceId: String, address: String) = Peer(
        deviceId = deviceId,
        addresses = setOf(address),
        label = labelFor(address),
        matched = true,
    )
}
