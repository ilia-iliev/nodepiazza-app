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
import com.nodepiazza.phase3.EmbeddingPayload
import com.nodepiazza.phase3.Peer
import com.nodepiazza.phase3.Protocol
import com.nodepiazza.phase3.Services
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
    private val sendQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val sendInflight = ConcurrentHashMap<String, Boolean>()
    private val recvBuffers = ConcurrentHashMap<String, ChatRecvBuffer>()
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
        gattServer?.close()
        gattServer = null
    }

    fun sendChatMessage(address: String, text: String) {
        if (text.isEmpty()) return
        if (clients[address] == null) return
        val mtu = mtuByAddress[address] ?: 23
        val frames = ChatFraming.encode(text, mtu)
        state.appendChat(address, ChatMessage(ChatSender.Me, text))
        val q = sendQueues.getOrPut(address) { ArrayDeque() }
        synchronized(q) { q.addAll(frames) }
        pumpSend(address)
    }

    private fun pumpSend(address: String) {
        val gatt = clients[address] ?: return
        val ch = gatt.getService(Protocol.SERVICE_UUID)?.getCharacteristic(Protocol.CHAT_CHAR_UUID) ?: return
        val q = sendQueues[address] ?: return
        val next: ByteArray
        synchronized(q) {
            if (sendInflight[address] == true) return
            if (q.isEmpty()) return
            next = q.removeFirst()
            sendInflight[address] = true
        }
        ch.value = next
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
        service.addCharacteristic(embeddingChar)
        service.addCharacteristic(chatChar)
        server.addService(service)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == Protocol.EMBEDDING_CHAR_UUID) {
                val payload = EmbeddingPayload.encode(state.myEmbeddings())
                val tail = if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, tail)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
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
                handleChatFragment(device.address, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
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
            if (characteristic.uuid != Protocol.EMBEDDING_CHAR_UUID) return
            val peerEmbeds = EmbeddingPayload.decode(characteristic.value ?: ByteArray(0))
            val address = gatt.device.address
            scope.launch {
                val result = Services.embedder.match(peerEmbeds)
                val display = result.pairScores.flatten().maxOrNull() ?: 0f
                val label = labelFor(address)
                state.upsertPeer(
                    Peer(
                        address = address,
                        label = label,
                        similarity = display,
                        matched = result.matched,
                        connected = true,
                    ),
                )
                if (result.matched && state.markMatchSeen(address)) {
                    BleScanService.notifyMatch(context, address, label)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != Protocol.CHAT_CHAR_UUID) return
            val address = gatt.device.address
            val q = sendQueues[address]
            if (q != null) synchronized(q) { sendInflight[address] = false }
            pumpSend(address)
        }
    }
}

private class ChatRecvBuffer(total: Int) {
    val parts: Array<ByteArray?> = arrayOfNulls(total)
}

/** Derive a short stable display label from a BLE address. */
fun labelFor(address: String): String {
    val tail = address.replace(":", "").takeLast(6)
    return "peer-$tail"
}
