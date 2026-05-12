package com.nodepiazza.phase3.ble

import com.nodepiazza.phase3.ChatMessage
import com.nodepiazza.phase3.ChatSender
import com.nodepiazza.phase3.LlmMatch
import com.nodepiazza.phase3.Peer
import com.nodepiazza.phase3.InterestsPayload
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
    private val noMatchTtlMs: Long = DEFAULT_NO_MATCH_TTL_MS,
    private val staleNoMessagesMs: Long = DEFAULT_STALE_NO_MESSAGES_MS,
    private val staleWithMessagesMs: Long = DEFAULT_STALE_WITH_MESSAGES_MS,
) {

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMessage>>> = _chats.asStateFlow()

    private val _activeChatDeviceId = MutableStateFlow<String?>(null)
    val activeChatDeviceId: StateFlow<String?> = _activeChatDeviceId.asStateFlow()

    private val seenMatches: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val noMatchUntilMs = ConcurrentHashMap<String, Long>()
    private val rejectedDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val rejectedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val forceMatchedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val deviceIdByAddress = ConcurrentHashMap<String, String>()
    private val pendingTexts = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val pendingInboundChats = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val lastSeenByDeviceId = ConcurrentHashMap<String, Long>()
    private val clientAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ---------- UI focus ----------

    fun openChat(deviceId: String) { _activeChatDeviceId.value = deviceId }

    fun closeChat() { _activeChatDeviceId.value = null }

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
        if (remaining.isEmpty()) {
            lastSeenByDeviceId.remove(deviceId)
            removePeerEntry(deviceId)
        } else {
            _peers.update { it + (deviceId to peer.copy(addresses = remaining)) }
        }
    }

    /** BleCore got a successful chat write acknowledgement; refresh liveness. */
    fun onWriteAcknowledged(address: String) {
        deviceIdByAddress[address]?.let { markSeen(it) }
    }

    /** Look up the device id for [address] if we've completed the interests handshake on it. */
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
        val until = noMatchUntilMs[address]
        if (until != null) {
            if (until > clock()) return ScanDecision.Skip
            noMatchUntilMs.remove(address)
        }
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
            noMatchUntilMs[address] = clock() + noMatchTtlMs
            return InterestsMatchDecision.NotMatched
        }
        updatePeerEntry(deviceId) { it.copy(matched = true) }
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
        noMatchUntilMs.remove(address)
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
        }
        lastSeenByDeviceId.remove(deviceId)
        seenMatches.add(deviceId)
        removePeerEntry(deviceId)
        return addrs
    }

    // ---------- Liveness / pruning ----------

    /** Drop peers we haven't heard from in too long. Returns addresses to disconnect. */
    fun pruneStale(): Set<String> {
        val now = clock()
        val toDisconnect = mutableSetOf<String>()
        val peers = _peers.value
        val chats = _chats.value
        for ((deviceId, peer) in peers) {
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
        noMatchUntilMs.clear()
        forceMatchedAddresses.clear()
        deviceIdByAddress.clear()
        pendingTexts.clear()
        pendingInboundChats.clear()
        lastSeenByDeviceId.clear()
        clientAddresses.clear()
    }

    // ---------- Internal state mutations ----------

    private fun mergePeer(deviceId: String, address: String) {
        markSeen(deviceId)
        _peers.update { current ->
            val existing = current[deviceId]
            val peer = if (existing == null) {
                Peer(
                    deviceId = deviceId,
                    addresses = setOf(address),
                    label = labelFor(address),
                    matched = false,
                )
            } else {
                existing.copy(addresses = existing.addresses + address)
            }
            current + (deviceId to peer)
        }
    }

    private fun deliverChat(deviceId: String, address: String, text: String) {
        markSeen(deviceId)
        appendChat(deviceId, ChatMessage(ChatSender.Them, text))
        val existing = _peers.value[deviceId]
        if (existing == null) {
            _peers.update {
                it + (deviceId to Peer(
                    deviceId = deviceId,
                    addresses = setOf(address),
                    label = labelFor(address),
                    matched = true,
                ))
            }
        } else if (!existing.matched) {
            updatePeerEntry(deviceId) { it.copy(matched = true) }
        }
    }

    private fun drainPendingTexts(address: String): List<String> {
        val q = pendingTexts[address] ?: return emptyList()
        return synchronized(q) {
            val list = q.toList()
            q.clear()
            list
        }
    }

    private fun drainPendingInboundChats(address: String, deviceId: String) {
        val q = pendingInboundChats.remove(address) ?: return
        val texts = synchronized(q) {
            val list = q.toList()
            q.clear()
            list
        }
        for (t in texts) deliverChat(deviceId, address, t)
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
    }

    private fun markSeen(deviceId: String) {
        lastSeenByDeviceId[deviceId] = clock()
    }

    companion object {
        const val DEFAULT_NO_MATCH_TTL_MS = 10 * 60 * 1000L
        const val DEFAULT_STALE_NO_MESSAGES_MS = 2 * 60 * 1000L
        const val DEFAULT_STALE_WITH_MESSAGES_MS = 30 * 60 * 1000L
    }
}
