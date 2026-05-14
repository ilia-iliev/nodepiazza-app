package com.nodepiazza.ble

import androidx.annotation.VisibleForTesting
import com.nodepiazza.ChatMessage
import com.nodepiazza.ChatSender
import com.nodepiazza.LlmMatch
import com.nodepiazza.Peer
import com.nodepiazza.protocol.InterestsPayload
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pure decision layer for BLE peer matching and chat. Owns every piece of state that BleCore would
 * otherwise carry, except for Android-specific objects (BluetoothGatt handles, MTU values, encoded
 * write queues). Every public method is synchronous and side-effect-bounded: it mutates this
 * object's state and returns an explicit [Decision] describing what BleCore should do next.
 *
 * Tests construct an instance with a controllable clock and drive the decision methods directly,
 * without going anywhere near a real Bluetooth stack.
 */
class PeerCoordinator(
    val myDeviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleNoMessagesMs: Long = DEFAULT_STALE_NO_MESSAGES_MS,
    private val staleWithMessagesMs: Long = DEFAULT_STALE_WITH_MESSAGES_MS,
    // Fired when a posted match notification for this device id is no longer actionable:
    // the peer is gone (pruned / fully disconnected / rejected) or the user opened the chat
    // in-app. Callers wire this to NotificationManagerCompat.cancel.
    private val onMatchDismissed: (deviceId: String) -> Unit = {},
) {

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMessage>>> = _chats.asStateFlow()

    private val _activeChatDeviceId = MutableStateFlow<String?>(null)
    val activeChatDeviceId: StateFlow<String?> = _activeChatDeviceId.asStateFlow()

    private val seenMatches: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val rejectedDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val rejectedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val forceMatchedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val deviceIdByAddress = ConcurrentHashMap<String, String>()
    private val pendingTexts = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val pendingInboundChats = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val lastSeenByDeviceId = ConcurrentHashMap<String, Long>()
    private val clientAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val peerOpenedDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingPresenceAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ---------- UI focus ----------

    /**
     * Open the chat with [deviceId]. Returns the set of addresses BleCore should send a presence
     * sentinel to so the peer can flip their UI from "waiting" to "connected".
     */
    fun openChat(deviceId: String): Set<String> {
        _activeChatDeviceId.value = deviceId
        onMatchDismissed(deviceId)
        return _peers.value[deviceId]?.addresses.orEmpty()
    }

    /**
     * Close the active chat. If the closing peer is out of range (no addresses left), drop it
     * now — we kept it around in [onDisconnected] only because the chat was open.
     */
    fun closeChat() {
        val id = _activeChatDeviceId.value
        _activeChatDeviceId.value = null
        if (id != null && _peers.value[id]?.addresses?.isEmpty() == true) {
            lastSeenByDeviceId.remove(id)
            removePeerEntry(id)
        }
    }

    // ---------- Connection bookkeeping ----------

    /** BleCore opened an outbound GATT to [address] (whether or not it has connected yet). */
    fun onClientOpened(address: String) {
        clientAddresses.add(address)
    }

    /** BleCore observed a GATT disconnect for [address]. */
    fun onDisconnected(address: String) {
        clientAddresses.remove(address)
        val deviceId = deviceIdByAddress.remove(address) ?: return
        val peer = _peers.value[deviceId] ?: return
        val remaining = peer.addresses - address
        when {
            remaining.isNotEmpty() ->
                _peers.update { it + (deviceId to peer.copy(addresses = remaining)) }
            // Active chat keeps its entry around so the chat doesn't vanish; the UI shows the
            // out-of-range state from a stale lastSeenMs.
            deviceId == _activeChatDeviceId.value ->
                _peers.update { it + (deviceId to peer.copy(addresses = emptySet())) }
            else -> {
                lastSeenByDeviceId.remove(deviceId)
                removePeerEntry(deviceId)
            }
        }
    }

    /** BleCore got a successful chat write acknowledgement; refresh liveness. */
    fun onWriteAcknowledged(address: String) {
        deviceIdByAddress[address]?.let { markSeen(it) }
    }

    /** Look up the device id for [address] if we've completed the interests handshake on it. */
    @VisibleForTesting
    fun deviceIdFor(address: String): String? = deviceIdByAddress[address]

    // ---------- Scan ----------

    sealed class ScanDecision {
        data object Skip : ScanDecision()
        data object Connect : ScanDecision()
    }

    fun onScanResult(address: String): ScanDecision {
        val knownDeviceId = deviceIdByAddress[address]
        if (knownDeviceId != null) markSeen(knownDeviceId)
        if (clientAddresses.contains(address)) return ScanDecision.Skip
        if (rejectedAddresses.contains(address)) return ScanDecision.Skip
        if (knownDeviceId != null && rejectedDevices.contains(knownDeviceId)) return ScanDecision.Skip
        return ScanDecision.Connect
    }

    // ---------- Interests read ----------

    sealed class InterestsDecodeDecision {
        data object DropDecodeFailed : InterestsDecodeDecision()
        data object DropLoopback : InterestsDecodeDecision()
        data object DropRejected : InterestsDecodeDecision()
        /** Force-matched (we opened this client to chat); flush queued outbound texts. */
        data class ForceMatched(
            val deviceId: String,
            val pendingOutboundTexts: List<String>,
        ) : InterestsDecodeDecision()
        /** Registered the device; caller should now run the LLM match. */
        data class Registered(val deviceId: String, val interests: List<String>) : InterestsDecodeDecision()
    }

    /**
     * Handle a freshly-read interests payload from [address]. Registers the device, drains any
     * inbound chats that arrived before we knew the device id, and either returns the interests to
     * be matched or short-circuits to ForceMatched when we opened the link for outbound chat.
     */
    fun onInterestsDecoded(
        address: String,
        decoded: InterestsPayload.Decoded?,
    ): InterestsDecodeDecision {
        if (decoded == null) return InterestsDecodeDecision.DropDecodeFailed
        val deviceId = decoded.deviceId
        if (deviceId == myDeviceId) return InterestsDecodeDecision.DropLoopback
        if (rejectedDevices.contains(deviceId)) {
            rejectedAddresses.add(address)
            return InterestsDecodeDecision.DropRejected
        }
        deviceIdByAddress[address] = deviceId
        mergePeer(deviceId, address)
        markSeen(deviceId)
        drainPendingInboundChats(address, deviceId)
        if (forceMatchedAddresses.contains(address)) {
            updatePeerEntry(deviceId) { it.copy(matched = true) }
            val drained = drainPendingTexts(address)
            return InterestsDecodeDecision.ForceMatched(deviceId, drained)
        }
        return InterestsDecodeDecision.Registered(deviceId, decoded.interests)
    }

    sealed class InterestsMatchDecision {
        /** LLM match failed; cache the address as no-match. BleCore should disconnect. */
        data object NotMatched : InterestsMatchDecision()
        data class Matched(
            val pendingOutboundTexts: List<String>,
            /** Label to use in the match notification; null if we've already notified this device. */
            val notifyLabel: String?,
            val peerInterest: String?,
        ) : InterestsMatchDecision()
    }

    fun onInterestsMatched(
        deviceId: String,
        address: String,
        match: LlmMatch,
    ): InterestsMatchDecision {
        if (!match.matched) {
            return InterestsMatchDecision.NotMatched
        }
        updatePeerEntry(deviceId) { it.copy(matched = true, matchReason = match.peerInterest) }
        val drained = drainPendingTexts(address)
        val firstTime = seenMatches.add(deviceId)
        val label = if (firstTime) (_peers.value[deviceId]?.label ?: labelFor(address)) else null
        return InterestsMatchDecision.Matched(
            pendingOutboundTexts = drained,
            notifyLabel = label,
            peerInterest = match.peerInterest,
        )
    }

    // ---------- Inbound chat ----------

    sealed class ChatReceiveDecision {
        data object Ignore : ChatReceiveDecision()
        /** Buffered the text; open an outbound client to learn the device's identity. */
        data object BufferAndEnsureClient : ChatReceiveDecision()
        /** Delivered the text. [needsEnsureClient] true if no outbound client exists yet. */
        data class Delivered(val needsEnsureClient: Boolean) : ChatReceiveDecision()
    }

    fun onChatReceived(address: String, text: String): ChatReceiveDecision {
        val deviceId = deviceIdByAddress[address]
        if (deviceId == null) {
            val q = pendingInboundChats.getOrPut(address) { ArrayDeque() }
            synchronized(q) { q.addLast(text) }
            return ChatReceiveDecision.BufferAndEnsureClient
        }
        if (rejectedDevices.contains(deviceId)) return ChatReceiveDecision.Ignore
        deliverChat(deviceId, address, text)
        return ChatReceiveDecision.Delivered(needsEnsureClient = !clientAddresses.contains(address))
    }

    // ---------- Outbound chat ----------

    sealed class SendChatDecision {
        data class Send(val address: String) : SendChatDecision()
        /** Address is known but no client yet; text already queued. BleCore should ensureClient. */
        data class QueuedForReconnect(val address: String) : SendChatDecision()
        data object NoKnownAddress : SendChatDecision()
    }

    fun onSendChat(deviceId: String, text: String): SendChatDecision {
        appendChat(deviceId, ChatMessage(ChatSender.Me, text))
        val peer = _peers.value[deviceId] ?: return SendChatDecision.NoKnownAddress
        val connected = peer.addresses.firstOrNull { clientAddresses.contains(it) }
        if (connected != null) return SendChatDecision.Send(connected)
        val anyAddr = peer.addresses.firstOrNull() ?: return SendChatDecision.NoKnownAddress
        val q = pendingTexts.getOrPut(anyAddr) { ArrayDeque() }
        synchronized(q) { q.addLast(text) }
        return SendChatDecision.QueuedForReconnect(anyAddr)
    }

    // ---------- Force-match (outbound chat to a peer we may not have matched) ----------

    /** Mark [address] as force-matched so the interests gate doesn't disconnect us. */
    fun markForceMatched(address: String) {
        forceMatchedAddresses.add(address)
    }

    // ---------- Peer presence (peer opened chat with us) ----------

    /**
     * The peer at [address] told us they opened a chat with us. If we already know their device
     * id, flip the `peerOpenedChat` flag now; otherwise buffer the address so the next interests
     * decode can apply it.
     */
    fun onPresenceReceived(address: String) {
        val deviceId = deviceIdByAddress[address]
        if (deviceId == null) {
            pendingPresenceAddresses.add(address)
            return
        }
        markPeerOpened(deviceId)
    }

    // ---------- Reject ----------

    /** Reject a peer; returns the set of addresses BleCore should disconnect. */
    fun rejectPeer(deviceId: String): Set<String> {
        rejectedDevices.add(deviceId)
        val peer = _peers.value[deviceId]
        val addrs = peer?.addresses.orEmpty()
        for (addr in addrs) {
            rejectedAddresses.add(addr)
            forceMatchedAddresses.remove(addr)
            pendingTexts.remove(addr)
            pendingInboundChats.remove(addr)
            pendingPresenceAddresses.remove(addr)
        }
        lastSeenByDeviceId.remove(deviceId)
        seenMatches.add(deviceId)
        removePeerEntry(deviceId)
        return addrs
    }

    // ---------- Liveness / pruning ----------

    /**
     * Drop peers we haven't heard from in too long. Returns addresses to disconnect. The active
     * chat's peer is never pruned so the user can see its out-of-range banner instead of having
     * the chat vanish underneath them.
     */
    fun pruneStale(): Set<String> {
        val now = clock()
        val toDisconnect = mutableSetOf<String>()
        val peers = _peers.value
        val chats = _chats.value
        val active = _activeChatDeviceId.value
        for ((deviceId, peer) in peers) {
            if (deviceId == active) continue
            val seen = lastSeenByDeviceId[deviceId] ?: now.also { lastSeenByDeviceId[deviceId] = it }
            val idle = now - seen
            val haveMyMsgs = chats[deviceId]?.any { it.sender == ChatSender.Me } == true
            val limit = if (haveMyMsgs) staleWithMessagesMs else staleNoMessagesMs
            if (idle <= limit) continue
            toDisconnect += peer.addresses
            lastSeenByDeviceId.remove(deviceId)
            removePeerEntry(deviceId)
        }
        return toDisconnect
    }

    // ---------- Reset (on BleCore stop) ----------

    /** Clear connection-scoped state. Peers/chats/seenMatches survive across BLE on/off cycles. */
    fun resetOnBleStop() {
        rejectedDevices.clear()
        rejectedAddresses.clear()
        forceMatchedAddresses.clear()
        deviceIdByAddress.clear()
        pendingTexts.clear()
        pendingInboundChats.clear()
        lastSeenByDeviceId.clear()
        clientAddresses.clear()
        pendingPresenceAddresses.clear()
    }

    // ---------- Internal state mutations ----------

    private fun mergePeer(deviceId: String, address: String) {
        markSeen(deviceId)
        if (pendingPresenceAddresses.remove(address)) {
            peerOpenedDevices.add(deviceId)
        }
        val opened = peerOpenedDevices.contains(deviceId)
        _peers.update { current ->
            val existing = current[deviceId]
            val peer = existing?.copy(
                addresses = existing.addresses + address,
                peerOpenedChat = existing.peerOpenedChat || opened,
            ) ?: newUnmatchedPeer(deviceId, address).copy(peerOpenedChat = opened)
            current + (deviceId to peer.withLastSeen(deviceId))
        }
    }

    private fun deliverChat(deviceId: String, address: String, text: String) {
        markSeen(deviceId)
        appendChat(deviceId, ChatMessage(ChatSender.Them, text))
        // Inbound chat is also definitive proof the peer engaged — covers cases where the
        // presence sentinel was dropped (link race, older build).
        peerOpenedDevices.add(deviceId)
        val existing = _peers.value[deviceId]
        if (existing == null) {
            _peers.update {
                it + (deviceId to newMatchedPeer(deviceId, address)
                    .copy(peerOpenedChat = true)
                    .withLastSeen(deviceId))
            }
        } else if (!existing.matched || !existing.peerOpenedChat) {
            updatePeerEntry(deviceId) {
                it.copy(matched = true, peerOpenedChat = true).withLastSeen(deviceId)
            }
        }
    }

    private fun markPeerOpened(deviceId: String) {
        if (!peerOpenedDevices.add(deviceId)) return
        updatePeerEntry(deviceId) { it.copy(peerOpenedChat = true) }
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

    /** Refresh [Peer.lastSeenMs] from [lastSeenByDeviceId]. */
    private fun Peer.withLastSeen(deviceId: String): Peer {
        val seen = lastSeenByDeviceId[deviceId] ?: lastSeenMs
        return if (lastSeenMs == seen) this else copy(lastSeenMs = seen)
    }

    private fun drainPendingTexts(address: String): List<String> =
        drainQueue(pendingTexts[address])

    private fun drainPendingInboundChats(address: String, deviceId: String) {
        val texts = drainQueue(pendingInboundChats.remove(address))
        for (t in texts) deliverChat(deviceId, address, t)
    }

    private fun drainQueue(q: ArrayDeque<String>?): List<String> {
        if (q == null) return emptyList()
        return synchronized(q) {
            val list = q.toList()
            q.clear()
            list
        }
    }

    private fun appendChat(deviceId: String, message: ChatMessage) {
        _chats.update { current ->
            val existing = current[deviceId].orEmpty()
            current + (deviceId to (existing + message))
        }
    }

    private fun updatePeerEntry(deviceId: String, transform: (Peer) -> Peer) {
        _peers.update { current ->
            val existing = current[deviceId] ?: return@update current
            current + (deviceId to transform(existing))
        }
    }

    private fun removePeerEntry(deviceId: String) {
        _peers.update { it - deviceId }
        _chats.update { it - deviceId }
        if (_activeChatDeviceId.value == deviceId) _activeChatDeviceId.value = null
        // Allow a returning peer to fire a fresh match notification next encounter.
        seenMatches.remove(deviceId)
        peerOpenedDevices.remove(deviceId)
        onMatchDismissed(deviceId)
    }

    private fun markSeen(deviceId: String) {
        lastSeenByDeviceId[deviceId] = clock()
        // Republish so subscribers (chat header, peer list) see the refreshed timestamp.
        updatePeerEntry(deviceId) { it.withLastSeen(deviceId) }
    }

    companion object {
        const val DEFAULT_STALE_NO_MESSAGES_MS = 2 * 60 * 1000L
        const val DEFAULT_STALE_WITH_MESSAGES_MS = 30 * 60 * 1000L
    }
}
