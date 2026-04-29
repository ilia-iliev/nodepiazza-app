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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
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
import com.nodepiazza.phase3.ControlSignal
import com.nodepiazza.phase3.EmbeddingPayload
import com.nodepiazza.phase3.Peer
import com.nodepiazza.phase3.Protocol
import com.nodepiazza.phase3.PromptsPayload
import com.nodepiazza.phase3.Services
import com.nodepiazza.phase3.cosineInt8
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "BleCore"

/**
 * Central coordinator for BLE scanning, advertising, GATT server, and per-peer client connections.
 *
 * Each device runs both a GATT server (exposing our embeddings + a chat write sink) and GATT clients
 * (one per discovered peer). Messages are sent by writing to a peer's chat characteristic; incoming
 * messages arrive via onCharacteristicWriteRequest on our own server.
 */
@SuppressLint("MissingPermission")
class BleCore(private val context: Context, private val state: AppState) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertising = false
    private var scanning = false
    private var running = false

    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    private val mtuByAddress = ConcurrentHashMap<String, Int>()
    private val sendQueues = ConcurrentHashMap<String, ArrayDeque<PendingWrite>>()
    private val sendInflight = ConcurrentHashMap<String, Boolean>()
    private val recvBuffers = ConcurrentHashMap<String, ChatRecvBuffer>()
    private val rejectedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun isBluetoothReady(): Boolean = adapter?.isEnabled == true

    fun start() {
        if (running) return
        if (!isBluetoothReady()) {
            Log.w(TAG, "Bluetooth not ready")
            return
        }
        running = true
        startGattServer()
        startAdvertising()
        startScanning()
    }

    fun stop() {
        if (!running) return
        running = false
        stopScanning()
        stopAdvertising()
        for ((_, gatt) in clients) runCatching { gatt.close() }
        clients.clear()
        mtuByAddress.clear()
        sendQueues.clear()
        sendInflight.clear()
        recvBuffers.clear()
        rejectedAddresses.clear()
        gattServer?.close()
        gattServer = null
    }

    fun sendChatMessage(address: String, text: String) {
        if (text.isEmpty()) return
        if (clients[address] == null) return
        val mtu = mtuByAddress[address] ?: 23
        val frames = ChatFraming.encode(text, mtu).map { PendingWrite(Protocol.CHAT_CHAR_UUID, it) }
        state.appendChat(address, ChatMessage(ChatSender.Me, text))
        val q = sendQueues.getOrPut(address) { ArrayDeque() }
        synchronized(q) { q.addAll(frames) }
        pumpSend(address)
    }

    fun rejectPeer(address: String) {
        rejectedAddresses.add(address)
        val gatt = clients[address]
        if (gatt != null) {
            val q = sendQueues.getOrPut(address) { ArrayDeque() }
            synchronized(q) {
                q.addLast(PendingWrite(Protocol.CONTROL_CHAR_UUID, byteArrayOf(ControlSignal.REJECT)))
            }
            pumpSend(address)
        }
        state.markMatchSeen(address)
        state.removePeer(address)
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
        if (!ok) {
            synchronized(q) { sendInflight[address] = false }
            Log.w(TAG, "writeCharacteristic returned false for $address")
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
        val controlChar = BluetoothGattCharacteristic(
            Protocol.CONTROL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(embeddingChar)
        service.addCharacteristic(chatChar)
        service.addCharacteristic(promptsChar)
        service.addCharacteristic(controlChar)
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
            when (characteristic.uuid) {
                Protocol.CHAT_CHAR_UUID -> handleChatFragment(device.address, value)
                Protocol.CONTROL_CHAR_UUID -> handleControlSignal(device.address, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private fun handleControlSignal(address: String, value: ByteArray) {
        if (value.isEmpty()) return
        if (value[0] == ControlSignal.REJECT) {
            rejectedAddresses.add(address)
            state.markMatchSeen(address)
            state.removePeer(address)
            clients[address]?.let { runCatching { it.disconnect() } }
        }
    }

    private fun handleChatFragment(address: String, frame: ByteArray) {
        if (frame.size < 2) return
        val index = frame[0].toInt() and 0xFF
        val total = frame[1].toInt() and 0xFF
        if (total == 0 || index >= total) return
        val payload = if (frame.size > 2) frame.copyOfRange(2, frame.size) else ByteArray(0)

        val buf = if (index == 0) {
            ChatRecvBuffer(total).also { recvBuffers[address] = it }
        } else {
            val existing = recvBuffers[address] ?: return
            if (existing.parts.size != total) return
            existing
        }
        buf.parts[index] = payload

        if (buf.parts.all { it != null }) {
            recvBuffers.remove(address)
            var size = 0
            for (p in buf.parts) size += p!!.size
            val combined = ByteArray(size)
            var pos = 0
            for (p in buf.parts) {
                p!!.copyInto(combined, pos)
                pos += p.size
            }
            val text = combined.toString(Charsets.UTF_8)
            state.appendChat(address, ChatMessage(ChatSender.Them, text))
        }
    }

    // ---------- Advertising ----------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise start failure: $errorCode")
            advertising = false
        }
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
        advertising = true
    }

    private fun stopAdvertising() {
        if (!advertising) return
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
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
        val gatt = device.connectGatt(context, false, clientCallback) ?: return
        clients[address] = gatt
        state.upsertPeer(
            Peer(
                address = address,
                label = labelFor(address),
                similarity = 0f,
                matched = false,
                connected = false,
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
                    gatt.requestMtu(Protocol.REQUESTED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    clients.remove(address)
                    mtuByAddress.remove(address)
                    sendQueues.remove(address)
                    sendInflight.remove(address)
                    recvBuffers.remove(address)
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
            val ch = gatt.getService(Protocol.SERVICE_UUID)?.getCharacteristic(Protocol.EMBEDDING_CHAR_UUID) ?: return
            gatt.readCharacteristic(ch)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val address = gatt.device.address
            val data = characteristic.value ?: ByteArray(0)
            when (characteristic.uuid) {
                Protocol.EMBEDDING_CHAR_UUID -> handleEmbeddingRead(gatt, address, data)
                Protocol.PROMPTS_CHAR_UUID -> handlePromptsRead(address, data)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val uuid = characteristic.uuid
            if (uuid != Protocol.CHAT_CHAR_UUID && uuid != Protocol.CONTROL_CHAR_UUID) return
            val address = gatt.device.address
            val q = sendQueues[address]
            if (q != null) synchronized(q) { sendInflight[address] = false }
            pumpSend(address)
        }
    }

    private fun handleEmbeddingRead(gatt: BluetoothGatt, address: String, data: ByteArray) {
        val peerEmbeds = EmbeddingPayload.decode(data)
        val mine = state.myEmbeddings()
        val best = if (mine.isEmpty() || peerEmbeds.isEmpty()) 0f else {
            var max = 0f
            for (m in mine) for (p in peerEmbeds) {
                val s = cosineInt8(m, p)
                if (s > max) max = s
            }
            max
        }
        val label = labelFor(address)
        state.upsertPeer(
            Peer(
                address = address,
                label = label,
                similarity = best,
                matched = false,
                connected = true,
            ),
        )
        if (best >= Protocol.MATCH_THRESHOLD) {
            val ch = gatt.getService(Protocol.SERVICE_UUID)?.getCharacteristic(Protocol.PROMPTS_CHAR_UUID)
            if (ch != null) gatt.readCharacteristic(ch)
        }
    }

    private fun handlePromptsRead(address: String, data: ByteArray) {
        val peerPrompts = PromptsPayload.decode(data)
        if (peerPrompts.isEmpty()) return
        scope.launch {
            val result = Services.llm.match(peerPrompts)
            if (!result.matched) return@launch
            val label = labelFor(address)
            val existing = state.peers.value[address]
            state.upsertPeer(
                Peer(
                    address = address,
                    label = label,
                    similarity = existing?.similarity ?: 0f,
                    matched = true,
                    connected = true,
                ),
            )
            if (state.markMatchSeen(address)) {
                BleScanService.notifyMatch(context, address, label, result.peerPrompt)
            }
        }
    }
}

private class ChatRecvBuffer(total: Int) {
    val parts: Array<ByteArray?> = arrayOfNulls(total)
}

private data class PendingWrite(val charUuid: UUID, val data: ByteArray)

/** Derive a short stable display label from a BLE address. */
fun labelFor(address: String): String {
    val tail = address.replace(":", "").takeLast(6)
    return "peer-$tail"
}
