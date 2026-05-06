@file:Suppress("DEPRECATION")

package com.nodepiazza.phase3.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.nodepiazza.phase3.AppState
import com.nodepiazza.phase3.ChatFraming
import com.nodepiazza.phase3.ChatMessage
import com.nodepiazza.phase3.ChatSender
import com.nodepiazza.phase3.EmbeddingPayload
import com.nodepiazza.phase3.Peer
import com.nodepiazza.phase3.Protocol
import com.nodepiazza.phase3.PromptsPayload
import com.nodepiazza.phase3.Services
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "BleCore"
private const val NO_MATCH_TTL_MS = 10 * 60 * 1000L

/**
 * Central coordinator for BLE scanning, advertising, GATT server, and per-peer client connections.
 *
 * Each connection runs a single handshake: read peer embedding → if cosine ≥ MATCH_THRESHOLD,
 * read peer prompts → run LLM match. No-match peers are cached for 10 min and disconnected so we
 * don't keep reconnecting to the same rotating address.
 */
@SuppressLint("MissingPermission")
class BleCore(private val context: Context, private val state: AppState) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var scanning = false
    private var running = false

    private val advertiser = BleAdvertiser(adapter)
    private val chatReassembler = ChatReassembler(::onChatMessageReceived)

    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    private val mtuByAddress = ConcurrentHashMap<String, Int>()
    private val sendQueues = ConcurrentHashMap<String, ArrayDeque<PendingWrite>>()
    private val sendInflight = ConcurrentHashMap<String, Boolean>()
    private val noMatchUntilMs = ConcurrentHashMap<String, Long>()
    private val rejectedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingTexts = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val forceMatchedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        if (running) return
        if (adapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth not ready")
            return
        }
        running = true
        startGattServer()
        advertiser.start()
        startScanning()
    }

    fun stop() {
        if (!running) return
        running = false
        stopScanning()
        advertiser.stop()
        for ((_, gatt) in clients) runCatching { gatt.close() }
        clients.clear()
        mtuByAddress.clear()
        sendQueues.clear()
        sendInflight.clear()
        chatReassembler.clear()
        rejectedAddresses.clear()
        noMatchUntilMs.clear()
        pendingTexts.clear()
        forceMatchedAddresses.clear()
        gattServer?.close()
        gattServer = null
    }

    fun sendChatMessage(address: String, text: String) {
        if (text.isEmpty()) return
        // Always show the user their own message, even if outbound delivery is queued/lost.
        state.appendChat(address, ChatMessage(ChatSender.Me, text))
        val gatt = clients[address]
        if (gatt == null) {
            Log.w(TAG, "sendChatMessage: no client to $address; queueing and reconnecting")
            val q = pendingTexts.getOrPut(address) { ArrayDeque() }
            synchronized(q) { q.addLast(text) }
            ensureClient(address)
            return
        }
        enqueueChatFrames(address, text)
        pumpSend(address)
    }

    fun rejectPeer(address: String) {
        rejectedAddresses.add(address)
        forceMatchedAddresses.remove(address)
        pendingTexts.remove(address)
        state.markMatchSeen(address)
        state.removePeer(address)
        clients[address]?.let { runCatching { it.disconnect() } }
    }

    private fun enqueueChatFrames(address: String, text: String) {
        val mtu = mtuByAddress[address] ?: 23
        val frames = ChatFraming.encode(text, mtu).map { PendingWrite(Protocol.CHAT_CHAR_UUID, it) }
        Log.d(TAG, "enqueue chat to=$address frames=${frames.size} mtu=$mtu")
        val q = sendQueues.getOrPut(address) { ArrayDeque() }
        synchronized(q) { q.addAll(frames) }
    }

    /**
     * Open an outbound GATT to a peer we want to chat with. Used both when sending while
     * disconnected and when receiving from a peer we don't have an outbound link to (asymmetric
     * connection). Marks the address force-matched so the embedding gate doesn't disconnect us.
     */
    private fun ensureClient(address: String) {
        if (clients.containsKey(address)) return
        val device = adapter?.getRemoteDevice(address) ?: return
        forceMatchedAddresses.add(address)
        noMatchUntilMs.remove(address)
        val gatt = device.connectGatt(context, false, clientCallback) ?: return
        clients[address] = gatt
        Log.d(TAG, "ensureClient: connecting to $address")
    }

    private fun drainPendingTexts(address: String) {
        val q = pendingTexts[address] ?: return
        val texts = synchronized(q) {
            val list = q.toList()
            q.clear()
            list
        }
        if (texts.isEmpty()) return
        Log.d(TAG, "drainPendingTexts: ${texts.size} for $address")
        for (t in texts) enqueueChatFrames(address, t)
        pumpSend(address)
    }

    private fun pumpSend(address: String) {
        val gatt = clients[address] ?: return
        val service = gatt.getService(Protocol.SERVICE_UUID) ?: return
        val q = sendQueues[address] ?: return
        val next: PendingWrite
        synchronized(q) {
            if (sendInflight[address] == true) return
            if (q.isEmpty()) return
            next = q.removeFirst()
            sendInflight[address] = true
        }
        val ch = service.getCharacteristic(next.charUuid)
        if (ch == null) {
            synchronized(q) { sendInflight[address] = false }
            return
        }
        ch.value = next.data
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = gatt.writeCharacteristic(ch)
        Log.d(TAG, "pumpSend to=$address bytes=${next.data.size} ok=$ok")
        if (!ok) {
            synchronized(q) {
                q.addFirst(next)
                sendInflight[address] = false
            }
        }
    }

    // ---------- GATT server ----------

    private fun startGattServer() {
        val server = manager?.openGattServer(context, serverCallback) ?: return
        gattServer = server
        val service = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val embeddingChar = BluetoothGattCharacteristic(
            Protocol.EMBEDDING_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val chatChar = BluetoothGattCharacteristic(
            Protocol.CHAT_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val promptsChar = BluetoothGattCharacteristic(
            Protocol.PROMPTS_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        service.addCharacteristic(embeddingChar)
        service.addCharacteristic(chatChar)
        service.addCharacteristic(promptsChar)
        server.addService(service)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val payload = when (characteristic.uuid) {
                Protocol.EMBEDDING_CHAR_UUID -> EmbeddingPayload.encode(state.myEmbeddings())
                Protocol.PROMPTS_CHAR_UUID -> PromptsPayload.encode(state.myPromptTexts())
                else -> null
            }
            if (payload == null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            val tail = if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, tail)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == Protocol.CHAT_CHAR_UUID) {
                Log.d(TAG, "server write from=${device.address} bytes=${value.size}")
                chatReassembler.onFragment(device.address, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private fun onChatMessageReceived(address: String, text: String) {
        Log.d(TAG, "chat received from=$address text='$text'")
        state.appendChat(address, ChatMessage(ChatSender.Them, text))
        // Asymmetric-match recovery: a chat message proves the peer matched us. Surface them in
        // the matched list (creating the entry if our own match check disconnected them) so the
        // user can open the conversation.
        val existing = state.peers.value[address]
        if (existing == null) {
            state.upsertPeer(Peer(address, labelFor(address), 0f, matched = true))
        } else if (!existing.matched) {
            state.updatePeer(address) { it.copy(matched = true) }
        }
        // If we have no outbound GATT (peer connected to us as server but our own outbound
        // never came up or got dropped), open one now so the user can reply.
        if (!clients.containsKey(address)) ensureClient(address)
    }

    // ---------- Scanning ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handleScanResult(r)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        if (clients.containsKey(address)) return
        if (rejectedAddresses.contains(address)) return
        val until = noMatchUntilMs[address]
        if (until != null) {
            if (until > System.currentTimeMillis()) return
            noMatchUntilMs.remove(address)
        }
        val gatt = device.connectGatt(context, false, clientCallback) ?: return
        clients[address] = gatt
        state.upsertPeer(
            Peer(
                address = address,
                label = labelFor(address),
                similarity = 0f,
                matched = false,
            ),
        )
    }

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
    }

    private fun stopScanning() {
        if (!scanning) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    // ---------- GATT client ----------

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "connected to=$address")
                    gatt.requestMtu(Protocol.REQUESTED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "disconnected from=$address status=$status")
                    gatt.close()
                    clients.remove(address)
                    mtuByAddress.remove(address)
                    sendQueues.remove(address)
                    sendInflight.remove(address)
                    chatReassembler.forget(address)
                    state.removePeer(address)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuByAddress[gatt.device.address] = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val ch = gatt.getService(Protocol.SERVICE_UUID)
                ?.getCharacteristic(Protocol.EMBEDDING_CHAR_UUID) ?: return
            gatt.readCharacteristic(ch)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pumpSend(gatt.device.address)
                return
            }
            val address = gatt.device.address
            val data = characteristic.value ?: ByteArray(0)
            when (characteristic.uuid) {
                Protocol.EMBEDDING_CHAR_UUID -> handleEmbeddingRead(gatt, address, data)
                Protocol.PROMPTS_CHAR_UUID -> handlePromptsRead(address, data)
            }
            pumpSend(address)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != Protocol.CHAT_CHAR_UUID) return
            val address = gatt.device.address
            Log.d(TAG, "onCharacteristicWrite to=$address status=$status")
            val q = sendQueues[address]
            if (q != null) synchronized(q) { sendInflight[address] = false }
            pumpSend(address)
        }
    }

    private fun handleEmbeddingRead(gatt: BluetoothGatt, address: String, data: ByteArray) {
        val peerEmbeds = EmbeddingPayload.decode(data)
        scope.launch {
            val result = Services.embedder.match(peerEmbeds)
            Log.d(TAG, "embedding read from=$address best=${result.bestSimilarity} forced=${forceMatchedAddresses.contains(address)}")
            state.updatePeer(address) { it.copy(similarity = result.bestSimilarity) }
            if (forceMatchedAddresses.contains(address)) {
                // We reconnected specifically to chat with this peer (asymmetric match or send-after-
                // disconnect). Skip the embedding gate and any pending texts can flow now.
                state.updatePeer(address) { it.copy(matched = true) }
                drainPendingTexts(address)
                return@launch
            }
            if (result.matched) {
                val ch = gatt.getService(Protocol.SERVICE_UUID)?.getCharacteristic(Protocol.PROMPTS_CHAR_UUID)
                if (ch != null) gatt.readCharacteristic(ch)
            } else {
                // Cache as no-match so we don't keep reconnecting to the same rotating address. Disconnect
                // to free the slot for new peers; we'll re-handshake when the address rotates or the TTL expires.
                noMatchUntilMs[address] = System.currentTimeMillis() + NO_MATCH_TTL_MS
                runCatching { gatt.disconnect() }
            }
        }
    }

    private fun handlePromptsRead(address: String, data: ByteArray) {
        val peerPrompts = PromptsPayload.decode(data)
        if (peerPrompts.isEmpty()) return
        scope.launch {
            val result = Services.llm.match(peerPrompts)
            if (!result.matched) return@launch
            state.updatePeer(address) { it.copy(matched = true) }
            drainPendingTexts(address)
            if (state.markMatchSeen(address)) {
                val label = state.peers.value[address]?.label ?: labelFor(address)
                BleScanService.notifyMatch(context, address, label, result.peerPrompt)
            }
        }
    }
}

private data class PendingWrite(val charUuid: UUID, val data: ByteArray)

/** Derive a short stable display label from a BLE address. */
fun labelFor(address: String): String {
    val tail = address.replace(":", "").takeLast(6)
    return "peer-$tail"
}
