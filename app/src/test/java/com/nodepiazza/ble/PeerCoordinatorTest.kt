package com.nodepiazza.ble

import com.nodepiazza.ChatSender
import com.nodepiazza.protocol.InterestsPayload
import com.nodepiazza.llm.LlmMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class PeerCoordinatorTest {

    private val myDeviceId = "00000000-0000-0000-0000-000000000001"
    private val peerDeviceId = "00000000-0000-0000-0000-0000000000aa"
    private val otherPeerDeviceId = "00000000-0000-0000-0000-0000000000bb"
    private val addrA = "AA:AA:AA:AA:AA:01"
    private val addrB = "AA:AA:AA:AA:AA:02"

    private val clock = AtomicLong(1_000_000L)
    private fun newCoord(onMatchDismissed: (String) -> Unit = {}) = PeerCoordinator(
        myDeviceId = myDeviceId,
        clock = { clock.get() },
        onMatchDismissed = onMatchDismissed,
    )

    private fun decoded(deviceId: String, interests: List<String> = emptyList()): InterestsPayload.Decoded =
        InterestsPayload.Decoded(deviceId = deviceId, interests = interests)

    private fun matched(peerInterest: String? = "match") = LlmMatch(matched = true, peerInterest = peerInterest)
    private fun noMatch() = LlmMatch(matched = false, peerInterest = null)

    // ---------- Scan decisions ----------

    @Test
    fun firstScan_returnsConnect() {
        val c = newCoord()
        assertEquals(PeerCoordinator.ScanDecision.Connect, c.onScanResult(addrA))
    }

    @Test
    fun scan_skipsIfClientAlreadyOpen() {
        val c = newCoord()
        c.onClientOpened(addrA)
        assertEquals(PeerCoordinator.ScanDecision.Skip, c.onScanResult(addrA))
    }

    @Test
    fun scan_allowsReconnectAfterNoMatchOnceClientCloses() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, noMatch())
        // While the client is still open, scans skip on the clientAddresses gate.
        assertEquals(PeerCoordinator.ScanDecision.Skip, c.onScanResult(addrA))
        c.onDisconnected(addrA)
        // No per-address no-match TTL anymore: dedupe is handled by the content-keyed LLM cache.
        assertEquals(PeerCoordinator.ScanDecision.Connect, c.onScanResult(addrA))
    }

    @Test
    fun scan_refreshesLastSeenWhenAddressIsKnown() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        clock.addAndGet(PeerCoordinator.DEFAULT_STALE_NO_MESSAGES_MS - 1_000L)
        // Scan refreshes liveness.
        c.onScanResult(addrA)
        clock.addAndGet(2_000L)
        // Now we've passed the original window by 1s, but the scan refresh extended it.
        assertTrue(c.pruneStale().isEmpty())
        assertNotNull(c.peers.value[peerDeviceId])
    }

    // ---------- Interests decode ----------

    @Test
    fun interestsDecode_nullPayload_dropsAsDecodeFailed() {
        val c = newCoord()
        assertEquals(
            PeerCoordinator.InterestsDecodeDecision.DropDecodeFailed,
            c.onInterestsDecoded(addrA, decoded = null),
        )
    }

    @Test
    fun interestsDecode_loopback_drops() {
        val c = newCoord()
        assertEquals(
            PeerCoordinator.InterestsDecodeDecision.DropLoopback,
            c.onInterestsDecoded(addrA, decoded(myDeviceId)),
        )
    }

    @Test
    fun interestsDecode_rejectedDevice_dropsAndPoisonsAddress() {
        val c = newCoord()
        // First seed and reject the peer at a different address.
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.rejectPeer(peerDeviceId)
        // Now the same peer comes in on a new address.
        assertEquals(
            PeerCoordinator.InterestsDecodeDecision.DropRejected,
            c.onInterestsDecoded(addrB, decoded(peerDeviceId)),
        )
        // Future scans of addrB should also skip.
        c.onDisconnected(addrB)
        assertEquals(PeerCoordinator.ScanDecision.Skip, c.onScanResult(addrB))
    }

    @Test
    fun interestsDecode_registered_returnsDeviceIdAndInterests() {
        val c = newCoord()
        c.onClientOpened(addrA)
        val r = c.onInterestsDecoded(addrA, decoded(peerDeviceId, listOf("espresso", "books")))
        assertTrue(r is PeerCoordinator.InterestsDecodeDecision.Registered)
        val reg = r as PeerCoordinator.InterestsDecodeDecision.Registered
        assertEquals(peerDeviceId, reg.deviceId)
        assertEquals(listOf("espresso", "books"), reg.interests)
        assertEquals(peerDeviceId, c.deviceIdFor(addrA))
    }

    // ---------- Address rotation ----------

    @Test
    fun addressRotation_collapsesToOnePeer() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())

        c.onClientOpened(addrB)
        c.onInterestsDecoded(addrB, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrB, matched())

        val peers = c.peers.value
        assertEquals(1, peers.size)
        val p = peers[peerDeviceId]!!
        assertEquals(setOf(addrA, addrB), p.addresses)
    }

    @Test
    fun onDisconnected_keepsPeerWhenOtherAddressRemains() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())

        c.onClientOpened(addrB)
        c.onInterestsDecoded(addrB, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrB, matched())

        c.onDisconnected(addrA)
        val p = c.peers.value[peerDeviceId]
        assertNotNull(p)
        assertEquals(setOf(addrB), p!!.addresses)
    }

    @Test
    fun onDisconnected_removesPeerWhenLastAddressGone() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.onDisconnected(addrA)
        assertNull(c.peers.value[peerDeviceId])
    }

    // ---------- Force-matched flow ----------

    @Test
    fun forceMatched_decodeShortCircuits_andMarksPeerMatched() {
        val c = newCoord()
        // Mimic ensureClient(): mark force-matched, then open client.
        c.markForceMatched(addrA)
        c.onClientOpened(addrA)
        val r = c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        assertTrue(r is PeerCoordinator.InterestsDecodeDecision.ForceMatched)
        assertEquals(peerDeviceId, (r as PeerCoordinator.InterestsDecodeDecision.ForceMatched).deviceId)
        assertTrue(c.peers.value[peerDeviceId]!!.matched)
    }

    // ---------- Asymmetric chat ----------

    @Test
    fun chatFromUnknownAddress_bufferedAndDeliveredAfterInterestsDecode() {
        val c = newCoord()
        val r1 = c.onChatReceived(addrA, "hello")
        assertSame(PeerCoordinator.ChatReceiveDecision.BufferAndEnsureClient, r1)
        // Nothing in chats yet because we don't know the deviceId.
        assertTrue(c.chats.value.isEmpty())
        // BleCore opens the client; interests decode reveals deviceId.
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        val msgs = c.chats.value[peerDeviceId]!!
        assertEquals(1, msgs.size)
        assertEquals("hello", msgs.first().text)
        assertEquals(ChatSender.Them, msgs.first().sender)
        // Peer should be surfaced as matched (asymmetric recovery).
        assertTrue(c.peers.value[peerDeviceId]!!.matched)
    }

    @Test
    fun chatFromKnownDevice_delivered() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        val r = c.onChatReceived(addrA, "yo")
        assertTrue(r is PeerCoordinator.ChatReceiveDecision.Delivered)
        // Client is open, so no ensureClient needed.
        assertFalse((r as PeerCoordinator.ChatReceiveDecision.Delivered).needsEnsureClient)
        assertEquals("yo", c.chats.value[peerDeviceId]!!.single().text)
    }

    @Test
    fun chatFromRejectedDevice_ignored() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.rejectPeer(peerDeviceId)
        // The mapping address→deviceId survives until BleCore reports the disconnect, so an
        // inbound chat on the same address resolves to the rejected deviceId and is ignored.
        val r = c.onChatReceived(addrA, "let me back")
        assertSame(PeerCoordinator.ChatReceiveDecision.Ignore, r)
        assertTrue(c.chats.value.values.flatten().none { it.text == "let me back" })
    }

    // ---------- Send chat ----------

    @Test
    fun sendChat_connected_returnsSendAndRecordsOwnMessage() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        val r = c.onSendChat(peerDeviceId, "hey")
        assertTrue(r is PeerCoordinator.SendChatDecision.Send)
        assertEquals(addrA, (r as PeerCoordinator.SendChatDecision.Send).address)
        val msgs = c.chats.value[peerDeviceId]!!
        assertEquals(1, msgs.size)
        assertEquals(ChatSender.Me, msgs.first().sender)
        assertEquals("hey", msgs.first().text)
    }

    @Test
    fun sendChat_unknownDevice_returnsNoKnownAddress() {
        val c = newCoord()
        val r = c.onSendChat(peerDeviceId, "anyone home?")
        assertSame(PeerCoordinator.SendChatDecision.NoKnownAddress, r)
        // Own message is still recorded so the user sees their attempt.
        assertEquals("anyone home?", c.chats.value[peerDeviceId]!!.single().text)
    }

    // ---------- Reject ----------

    @Test
    fun rejectPeer_returnsAllAddresses_andRemovesPeer() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.onClientOpened(addrB)
        c.onInterestsDecoded(addrB, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrB, matched())

        val toDisconnect = c.rejectPeer(peerDeviceId)
        assertEquals(setOf(addrA, addrB), toDisconnect)
        assertNull(c.peers.value[peerDeviceId])
        assertNull(c.chats.value[peerDeviceId])
    }

    // ---------- Block ----------

    @Test
    fun blockPeer_returnsAddresses_andRemovesPeer() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())

        val toDisconnect = c.blockPeer(peerDeviceId)
        assertEquals(setOf(addrA), toDisconnect)
        assertNull(c.peers.value[peerDeviceId])
        assertNull(c.chats.value[peerDeviceId])
    }

    @Test
    fun blockPeer_survivesBleStop_unlikeReject() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.blockPeer(peerDeviceId)
        // resetOnBleStop clears the session-scoped reject state, but the block must hold.
        c.resetOnBleStop()
        c.onClientOpened(addrA)
        assertEquals(
            PeerCoordinator.InterestsDecodeDecision.DropRejected,
            c.onInterestsDecoded(addrA, decoded(peerDeviceId)),
        )
    }

    @Test
    fun initiallyBlocked_devicesAreGatedFromTheStart() {
        val c = PeerCoordinator(
            myDeviceId = myDeviceId,
            initiallyBlocked = setOf(peerDeviceId),
            clock = { clock.get() },
        )
        c.onClientOpened(addrA)
        assertEquals(
            PeerCoordinator.InterestsDecodeDecision.DropRejected,
            c.onInterestsDecoded(addrA, decoded(peerDeviceId)),
        )
    }

    // ---------- Remove chat (testing helper) ----------

    @Test
    fun removeChat_wipesPeerAndChat_andAllowsFreshMatchOnReconnect() {
        val dismissed = mutableListOf<String>()
        val c = newCoord(onMatchDismissed = { dismissed.add(it) })
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        assertNotNull(c.peers.value[peerDeviceId])

        val toDisconnect = c.removeChat(peerDeviceId)
        assertEquals(setOf(addrA), toDisconnect)
        assertNull(c.peers.value[peerDeviceId])
        assertNull(c.chats.value[peerDeviceId])
        assertTrue(dismissed.contains(peerDeviceId))

        // Unlike rejectPeer, the next encounter is NOT dropped — it goes through the
        // normal Registered path and the LLM match runs again.
        c.onClientOpened(addrA)
        val decision = c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        assertTrue(decision is PeerCoordinator.InterestsDecodeDecision.Registered)

        // And a fresh match fires a notification again (seenMatches was cleared).
        val matchDecision = c.onInterestsMatched(peerDeviceId, addrA, matched("guitars"))
            as PeerCoordinator.InterestsMatchDecision.Matched
        assertNotNull(matchDecision.notifyLabel)
    }

    @Test
    fun prune_dropsPeerIdleBeyondNoMessagesWindow() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        clock.addAndGet(PeerCoordinator.DEFAULT_STALE_NO_MESSAGES_MS + 1)
        val toDisconnect = c.pruneStale()
        assertEquals(setOf(addrA), toDisconnect)
        assertNull(c.peers.value[peerDeviceId])
    }

    @Test
    fun prune_skipsActiveChatEvenWhenStale() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.openChat(peerDeviceId)
        clock.addAndGet(PeerCoordinator.DEFAULT_STALE_NO_MESSAGES_MS + 60_000L)
        assertTrue(c.pruneStale().isEmpty())
        assertNotNull(c.peers.value[peerDeviceId])
    }

    @Test
    fun disconnect_keepsActiveChatPeer_andEmptiesAddresses() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.openChat(peerDeviceId)
        c.onDisconnected(addrA)
        val peer = c.peers.value[peerDeviceId]
        assertNotNull(peer)
        assertTrue(peer!!.addresses.isEmpty())
    }

    @Test
    fun closeChat_dropsPeerWhenOutOfRange() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.openChat(peerDeviceId)
        c.onDisconnected(addrA)
        assertNotNull(c.peers.value[peerDeviceId])
        c.closeChat()
        assertNull(c.peers.value[peerDeviceId])
    }

    @Test
    fun closeChat_keepsPeerWhenStillReachable() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.openChat(peerDeviceId)
        c.closeChat()
        assertNotNull(c.peers.value[peerDeviceId])
    }

    @Test
    fun peerLastSeen_isPublishedAndPruneRemovesInactivePeer() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        val live = c.peers.value[peerDeviceId]!!
        assertEquals(clock.get(), live.lastSeenMs)
        // A later scan bumps lastSeenMs on the published peer.
        clock.addAndGet(5_000L)
        c.onScanResult(addrA)
        assertEquals(clock.get(), c.peers.value[peerDeviceId]!!.lastSeenMs)
        c.onDisconnected(addrA)
        // Not active chat → peer is pruned.
        assertNull(c.peers.value[peerDeviceId])
    }

    @Test
    fun prune_keepsPeerWithMyMessagesUntilLongerWindow() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        // Send a message → switches peer to the longer prune window.
        c.onSendChat(peerDeviceId, "hi")
        clock.addAndGet(PeerCoordinator.DEFAULT_STALE_NO_MESSAGES_MS + 5_000L)
        assertTrue(c.pruneStale().isEmpty())
        assertNotNull(c.peers.value[peerDeviceId])
        clock.addAndGet(PeerCoordinator.DEFAULT_STALE_WITH_MESSAGES_MS)
        val drop = c.pruneStale()
        assertEquals(setOf(addrA), drop)
        assertNull(c.peers.value[peerDeviceId])
    }

    // ---------- Notification dedup ----------

    @Test
    fun interestsMatched_firesNotificationOnce() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        val first = c.onInterestsMatched(peerDeviceId, addrA, matched("espresso"))
        assertTrue(first is PeerCoordinator.InterestsMatchDecision.Matched)
        assertNotNull((first as PeerCoordinator.InterestsMatchDecision.Matched).notifyLabel)

        // Second match attempt for the same device (e.g., a new address joining).
        c.onClientOpened(addrB)
        c.onInterestsDecoded(addrB, decoded(peerDeviceId))
        val second = c.onInterestsMatched(peerDeviceId, addrB, matched("espresso"))
        assertTrue(second is PeerCoordinator.InterestsMatchDecision.Matched)
        assertNull((second as PeerCoordinator.InterestsMatchDecision.Matched).notifyLabel)
    }

    @Test
    fun interestsMatched_notMatched_keepsPeerVisibleAndConnected() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        val r = c.onInterestsMatched(peerDeviceId, addrA, noMatch())
        assertTrue(r is PeerCoordinator.InterestsMatchDecision.NotMatched)
        // Nothing to evict — only one no-match peer, well under the cap.
        assertTrue((r as PeerCoordinator.InterestsMatchDecision.NotMatched).evictedAddresses.isEmpty())
        // Peer stays visible (unmatched) and keeps its address so the user can still open a chat.
        val peer = c.peers.value[peerDeviceId]
        assertNotNull(peer)
        assertFalse(peer!!.matched)
        assertEquals(setOf(addrA), peer.addresses)
    }

    @Test
    fun noMatchCap_evictsLeastRecentlySeenBeyondCap() {
        val c = newCoord()
        // Register four no-match peers, each seen progressively later.
        val peers = (1..4).map { i ->
            val id = "00000000-0000-0000-0000-0000000000c$i"
            val addr = "CC:CC:CC:CC:CC:0$i"
            clock.addAndGet(1_000L)
            c.onClientOpened(addr)
            c.onInterestsDecoded(addr, decoded(id))
            val r = c.onInterestsMatched(id, addr, noMatch())
            id to (addr to r)
        }
        // First three stay; the fourth trips the cap.
        for (i in 0..2) {
            val (_, decisionPair) = peers[i]
            assertTrue((decisionPair.second as PeerCoordinator.InterestsMatchDecision.NotMatched)
                .evictedAddresses.isEmpty())
        }
        val (evictedId, lastPair) = peers[0]
        val lastDecision = peers[3].second.second as PeerCoordinator.InterestsMatchDecision.NotMatched
        // The least-recently-seen no-match peer (the first one) is evicted.
        assertEquals(setOf(lastPair.first), lastDecision.evictedAddresses)
        assertNull(c.peers.value[evictedId])
        assertEquals(3, c.peers.value.values.count { !it.matched })
    }

    @Test
    fun noMatchCap_doesNotCountMatchedPeers() {
        val c = newCoord()
        // One matched peer plus three no-match peers — the matched one doesn't consume a slot.
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        for (i in 1..3) {
            val id = "00000000-0000-0000-0000-0000000000d$i"
            val addr = "DD:DD:DD:DD:DD:0$i"
            clock.addAndGet(1_000L)
            c.onClientOpened(addr)
            c.onInterestsDecoded(addr, decoded(id))
            val r = c.onInterestsMatched(id, addr, noMatch())
            assertTrue((r as PeerCoordinator.InterestsMatchDecision.NotMatched)
                .evictedAddresses.isEmpty())
        }
        assertEquals(4, c.peers.value.size)
    }

    @Test
    fun noMatchCap_doesNotEvictActiveChatPeer() {
        val c = newCoord()
        // Oldest no-match peer is the active chat; it must survive eviction.
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, noMatch())
        c.openChat(peerDeviceId)
        for (i in 1..3) {
            val id = "00000000-0000-0000-0000-0000000000e$i"
            val addr = "EE:EE:EE:EE:EE:0$i"
            clock.addAndGet(1_000L)
            c.onClientOpened(addr)
            c.onInterestsDecoded(addr, decoded(id))
            c.onInterestsMatched(id, addr, noMatch())
        }
        // The active chat peer is kept; the next-oldest no-match peer is evicted instead.
        assertNotNull(c.peers.value[peerDeviceId])
        assertEquals(3, c.peers.value.values.count { !it.matched })
    }

    @Test
    fun interestsMatched_suppressesNotificationWhenUserAlreadyOpenedChat() {
        val c = newCoord()
        // User opens the chat before the LLM verdict lands.
        c.openChat(peerDeviceId)
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        val r = c.onInterestsMatched(peerDeviceId, addrA, matched("espresso"))
        assertTrue(r is PeerCoordinator.InterestsMatchDecision.Matched)
        // Label is still applied to the peer, but no notification fires.
        assertNull((r as PeerCoordinator.InterestsMatchDecision.Matched).notifyLabel)
        assertEquals("espresso", c.peers.value[peerDeviceId]!!.matchReason)
    }

    // ---------- Match notification dismissal ----------

    @Test
    fun pruneStale_firesMatchDismissed() {
        val dismissed = mutableListOf<String>()
        val c = newCoord(onMatchDismissed = { dismissed += it })
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        clock.addAndGet(PeerCoordinator.DEFAULT_STALE_NO_MESSAGES_MS + 1)
        c.pruneStale()
        assertEquals(listOf(peerDeviceId), dismissed)
    }

    @Test
    fun rejectPeer_firesMatchDismissed() {
        val dismissed = mutableListOf<String>()
        val c = newCoord(onMatchDismissed = { dismissed += it })
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.rejectPeer(peerDeviceId)
        assertEquals(listOf(peerDeviceId), dismissed)
    }

    @Test
    fun fullDisconnect_firesMatchDismissed() {
        val dismissed = mutableListOf<String>()
        val c = newCoord(onMatchDismissed = { dismissed += it })
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.onDisconnected(addrA)
        assertEquals(listOf(peerDeviceId), dismissed)
    }

    @Test
    fun openChat_firesMatchDismissed() {
        val dismissed = mutableListOf<String>()
        val c = newCoord(onMatchDismissed = { dismissed += it })
        c.openChat(peerDeviceId)
        assertEquals(listOf(peerDeviceId), dismissed)
    }

    @Test
    fun partialDisconnect_keepsPeer_doesNotFireDismiss() {
        val dismissed = mutableListOf<String>()
        val c = newCoord(onMatchDismissed = { dismissed += it })
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.onClientOpened(addrB)
        c.onInterestsDecoded(addrB, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrB, matched())
        c.onDisconnected(addrA)
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun returningPeerAfterPrune_reNotifies() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        val first = c.onInterestsMatched(peerDeviceId, addrA, matched("espresso"))
        assertNotNull((first as PeerCoordinator.InterestsMatchDecision.Matched).notifyLabel)
        clock.addAndGet(PeerCoordinator.DEFAULT_STALE_NO_MESSAGES_MS + 1)
        c.pruneStale()
        // Peer comes back later.
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        val second = c.onInterestsMatched(peerDeviceId, addrA, matched("espresso"))
        assertNotNull((second as PeerCoordinator.InterestsMatchDecision.Matched).notifyLabel)
    }

    // ---------- Reset ----------

    @Test
    fun resetOnBleStop_clearsConnectionStateButKeepsPeers() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.resetOnBleStop()
        assertNull(c.deviceIdFor(addrA))
        // Peer survives (matches current behavior — natural prune handles the long tail).
        assertNotNull(c.peers.value[peerDeviceId])
    }

    // ---------- Multiple peers don't interfere ----------

    @Test
    fun twoPeers_eachIndependentlyTracked() {
        val c = newCoord()
        c.onClientOpened(addrA)
        c.onInterestsDecoded(addrA, decoded(peerDeviceId))
        c.onInterestsMatched(peerDeviceId, addrA, matched())
        c.onClientOpened(addrB)
        c.onInterestsDecoded(addrB, decoded(otherPeerDeviceId))
        c.onInterestsMatched(otherPeerDeviceId, addrB, matched())

        assertEquals(2, c.peers.value.size)
        c.rejectPeer(peerDeviceId)
        assertNull(c.peers.value[peerDeviceId])
        assertNotNull(c.peers.value[otherPeerDeviceId])
    }
}
