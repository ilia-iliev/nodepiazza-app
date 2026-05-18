package com.nodepiazza.ble

import androidx.annotation.VisibleForTesting
import com.nodepiazza.ChatSender
import com.nodepiazza.llm.LlmMatch
import com.nodepiazza.protocol.InterestsPayload
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure decision layer for BLE peer matching and chat. Owns every piece of state that BleCore would
 * otherwise carry, except for Android-specific objects (BluetoothGatt handles, MTU values, encoded
 * write queues). Every public method is synchronous and side-effect-bounded: it mutates this
 * object's state and returns an explicit [Decision] describing what BleCore should do next.
 *
 * Tests construct an instance with a controllable clock and drive the decision methods directly,
 * without going anywhere near a real Bluetooth stack.
 *
 * Peer/chat state (the public flows) and per-device liveness timestamps live in [PeerStateStore],
 * which this class delegates to. Everything in here is decision metadata: exclusion sets,
 * queued-while-disconnected text, and address-keyed presence buffering.
 */
class PeerCoordinator(
    val myDeviceId: String,
    // Device ids the user has permanently blocked, restored from persistent storage at startup.
    initiallyBlocked: Set<String> = emptySet(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleNoMessagesMs: Long = DEFAULT_STALE_NO_MESSAGES_MS,
    private val staleWithMessagesMs: Long = DEFAULT_STALE_WITH_MESSAGES_MS,
    // Fired when a posted match notification for this device id is no longer actionable:
    // the peer is gone (pruned / fully disconnected / rejected) or the user opened the chat
    // in-app. Callers wire this to NotificationManagerCompat.cancel.
    private val onMatchDismissed: (deviceId: String) -> Unit = {},
) {

    private val store = PeerStateStore(clock)
    val peers = store.peers
    val chats = store.chats
    val activeChatDeviceId = store.activeChatDeviceId

    private val seenMatches: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Addresses observed to belong to a blocked device; faster gate than re-decoding interests.
    // Cleared on [resetOnBleStop] since BLE addresses rotate across BLE on/off cycles.
    private val rejectedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Permanently blocked device ids. Never cleared by [resetOnBleStop]; cross-restart persistence
    // is owned by AppState, which seeds it here.
    private val blockedDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val forceMatchedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val deviceIdByAddress = ConcurrentHashMap<String, String>()
    private val pendingTexts = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val pendingInboundChats = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val clientAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val peerOpenedDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val userOpenedDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingPresenceAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        blockedDevices.addAll(initiallyBlocked)
    }

    /** A peer the user has permanently blocked. */
    private fun isExcluded(deviceId: String): Boolean = blockedDevices.contains(deviceId)

    // ---------- UI focus ----------

    /**
     * Open the chat with [deviceId]. Returns the set of addresses BleCore should send a presence
     * sentinel to so the peer can flip their UI from "waiting" to "connected".
     */
    fun openChat(deviceId: String): Set<String> {
        store.setActiveChat(deviceId)
        // Remember the user engaged this peer in-app: a later LLM match should still label the
        // chat but must not raise a notification for a conversation they're already in.
        userOpenedDevices.add(deviceId)
        onMatchDismissed(deviceId)
        return store.peer(deviceId)?.addresses.orEmpty()
    }

    /**
     * Close the active chat. If the closing peer is out of range (no addresses left), drop it
     * now — we kept it around in [onDisconnected] only because the chat was open.
     */
    fun closeChat() {
        val id = store.activeChat()
        store.setActiveChat(null)
        if (id != null && store.peer(id)?.addresses?.isEmpty() == true) {
            store.forgetLastSeen(id)
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
        val peer = store.peer(deviceId) ?: return
        val remaining = peer.addresses - address
        when {
            remaining.isNotEmpty() ->
                store.shrinkAddresses(deviceId, address)
            // Active chat keeps its entry around so the chat doesn't vanish; the UI shows the
            // out-of-range state from a stale lastSeenMs.
            deviceId == store.activeChat() ->
                store.clearAddresses(deviceId)
            else -> {
                store.forgetLastSeen(deviceId)
                removePeerEntry(deviceId)
            }
        }
    }

    /** BleCore got a successful chat write acknowledgement; refresh liveness. */
    fun onWriteAcknowledged(address: String) {
        deviceIdByAddress[address]?.let { store.markSeen(it) }
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
        if (knownDeviceId != null) store.markSeen(knownDeviceId)
        if (clientAddresses.contains(address)) return ScanDecision.Skip
        if (rejectedAddresses.contains(address)) return ScanDecision.Skip
        if (knownDeviceId != null && isExcluded(knownDeviceId)) return ScanDecision.Skip
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
        if (isExcluded(deviceId)) {
            rejectedAddresses.add(address)
            return InterestsDecodeDecision.DropRejected
        }
        deviceIdByAddress[address] = deviceId
        mergePeer(deviceId, address)
        store.markSeen(deviceId)
        drainPendingInboundChats(address, deviceId)
        if (forceMatchedAddresses.contains(address)) {
            store.markForceMatched(deviceId)
            val drained = drainPendingTexts(address)
            return InterestsDecodeDecision.ForceMatched(deviceId, drained)
        }
        return InterestsDecodeDecision.Registered(deviceId, decoded.interests)
    }

    sealed class InterestsMatchDecision {
        /**
         * LLM match failed. The peer stays visible and connected so the user can still open a
         * chat with it — it just keeps its `peer-XXXXX` label. [evictedAddresses] are other
         * no-match peers booted to honour [NO_MATCH_CAP]; BleCore should disconnect them.
         */
        data class NotMatched(val evictedAddresses: Set<String>) : InterestsMatchDecision()
        data class Matched(
            val pendingOutboundTexts: List<String>,
            /**
             * Label to use in the match notification; null if we've already notified this device
             * or the user has already opened the chat in-app.
             */
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
            return InterestsMatchDecision.NotMatched(enforceNoMatchCap(keepDeviceId = deviceId))
        }
        store.markMatched(deviceId, match.peerInterest)
        val drained = drainPendingTexts(address)
        val firstTime = seenMatches.add(deviceId)
        val notify = firstTime && !userOpenedDevices.contains(deviceId)
        val label = if (notify) store.peer(deviceId)?.label else null
        return InterestsMatchDecision.Matched(
            pendingOutboundTexts = drained,
            notifyLabel = label,
            peerInterest = match.peerInterest,
        )
    }

    /**
     * Cap how many no-match peers we keep connected at once. Matched peers are exempt, as are the
     * active chat and [keepDeviceId] (the peer that just produced this verdict). Evicts the
     * least-recently-seen no-match peers over the cap and returns their addresses to disconnect.
     */
    private fun enforceNoMatchCap(keepDeviceId: String): Set<String> {
        val noMatch = store.allPeers().values.filter { !it.matched }
        if (noMatch.size <= NO_MATCH_CAP) return emptySet()
        val active = store.activeChat()
        val evictable = noMatch
            .filter { it.deviceId != keepDeviceId && it.deviceId != active }
            .sortedBy { store.lastSeen(it.deviceId) ?: 0L }
        val toEvict = evictable.take(noMatch.size - NO_MATCH_CAP)
        val addresses = mutableSetOf<String>()
        for (peer in toEvict) {
            addresses += peer.addresses
            for (addr in peer.addresses) deviceIdByAddress.remove(addr)
            store.forgetLastSeen(peer.deviceId)
            removePeerEntry(peer.deviceId)
        }
        return addresses
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
        if (isExcluded(deviceId)) return ChatReceiveDecision.Ignore
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
        store.appendOutboundChat(deviceId, text)
        val peer = store.peer(deviceId) ?: return SendChatDecision.NoKnownAddress
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

    // ---------- Block / Remove ----------

    /**
     * Permanently block a peer; returns the set of addresses BleCore should disconnect. The device
     * id is added to [blockedDevices], which [resetOnBleStop] never clears — so the block survives
     * BLE on/off cycles. Cross-restart persistence is the caller's job (see AppState).
     */
    fun blockPeer(deviceId: String): Set<String> {
        blockedDevices.add(deviceId)
        val peer = store.peer(deviceId)
        val addrs = peer?.addresses.orEmpty()
        for (addr in addrs) {
            cleanupAddressState(addr)
            rejectedAddresses.add(addr)
        }
        store.forgetLastSeen(deviceId)
        seenMatches.add(deviceId)
        removePeerEntry(deviceId)
        return addrs
    }

    /**
     * Wipe all local state for [deviceId] without adding it to any exclusion set. The next scan
     * hit will reconnect, re-read interests, and re-run the LLM match — producing a fresh chat
     * and a fresh match notification. Intended as a testing helper for the match flow.
     */
    fun removeChat(deviceId: String): Set<String> {
        val peer = store.peer(deviceId)
        val addrs = peer?.addresses.orEmpty()
        for (addr in addrs) {
            cleanupAddressState(addr)
            deviceIdByAddress.remove(addr)
        }
        store.forgetLastSeen(deviceId)
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
        val peers = store.allPeers()
        val active = store.activeChat()
        for ((deviceId, peer) in peers) {
            if (deviceId == active) continue
            val seen = store.lastSeen(deviceId)
            if (seen == null) {
                // Defensive: missing lastSeen means we never observed this peer through the normal
                // path. Seed it now so it gets a fair window before the next prune.
                store.markSeen(deviceId)
                continue
            }
            val idle = now - seen
            val haveMyMsgs = store.chatHistory(deviceId).any { it.sender == ChatSender.Me }
            val limit = if (haveMyMsgs) staleWithMessagesMs else staleNoMessagesMs
            if (idle <= limit) continue
            toDisconnect += peer.addresses
            store.forgetLastSeen(deviceId)
            removePeerEntry(deviceId)
        }
        return toDisconnect
    }

    // ---------- Reset (on BleCore stop) ----------

    /** Clear connection-scoped state. Peers/chats/seenMatches survive across BLE on/off cycles. */
    fun resetOnBleStop() {
        rejectedAddresses.clear()
        forceMatchedAddresses.clear()
        deviceIdByAddress.clear()
        pendingTexts.clear()
        pendingInboundChats.clear()
        store.clearLastSeen()
        clientAddresses.clear()
        pendingPresenceAddresses.clear()
    }

    // ---------- Internal coordination helpers ----------

    private fun mergePeer(deviceId: String, address: String) {
        if (pendingPresenceAddresses.remove(address)) {
            peerOpenedDevices.add(deviceId)
        }
        val opened = peerOpenedDevices.contains(deviceId)
        store.addOrMergeUnmatched(deviceId, address, peerOpenedNow = opened)
    }

    private fun deliverChat(deviceId: String, address: String, text: String) {
        // Inbound chat is also definitive proof the peer engaged — covers cases where the
        // presence sentinel was dropped (link race, older build).
        peerOpenedDevices.add(deviceId)
        store.deliverInboundChat(deviceId, address, text)
    }

    private fun markPeerOpened(deviceId: String) {
        if (!peerOpenedDevices.add(deviceId)) return
        store.markPeerOpened(deviceId)
    }

    private fun cleanupAddressState(address: String) {
        forceMatchedAddresses.remove(address)
        pendingTexts.remove(address)
        pendingInboundChats.remove(address)
        pendingPresenceAddresses.remove(address)
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

    private fun removePeerEntry(deviceId: String) {
        store.removePeer(deviceId)
        // Allow a returning peer to fire a fresh match notification next encounter.
        seenMatches.remove(deviceId)
        peerOpenedDevices.remove(deviceId)
        userOpenedDevices.remove(deviceId)
        onMatchDismissed(deviceId)
    }

    companion object {
        const val DEFAULT_STALE_NO_MESSAGES_MS = 2 * 60 * 1000L
        const val DEFAULT_STALE_WITH_MESSAGES_MS = 30 * 60 * 1000L
        /** Max no-match peers kept connected at once; matched peers are uncapped. */
        const val NO_MATCH_CAP = 3
    }
}
