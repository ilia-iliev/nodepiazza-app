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
import com.nodepiazza.phase3.Protocol
import com.nodepiazza.phase3.InterestsPayload
import com.nodepiazza.phase3.Services
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "BleCore"
private const val LIVENESS_CHECK_INTERVAL_MS = 60 * 1000L

/**
 * Android adapter for [PeerCoordinator]. Owns the BLE radio interactions (scanning, advertising,
 * GATT client + server, MTU exchange, frame queueing) and forwards every observation into the
 * coordinator, then applies the coordinator's [PeerCoordinator.ScanDecision]-style replies.
 *
 * Anything that doesn't depend on Android Bluetooth APIs lives in [PeerCoordinator] and is
 * unit-tested there.
 */
@SuppressLint("MissingPermission")
class BleCore(
    private val context: Context,
    private val state: AppState,
    private val coordinator: PeerCoordinator,
) {

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var livenessJob: Job? = null

    fun start() {
        if (running) return
        if (adapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth not ready")
            return
        }
        running = true
        Log.d(TAG, "BleCore.start: deviceId=${state.myDeviceId}")
        startGattServer()
        advertiser.start()
        startScanning()
        livenessJob = scope.launch { runLivenessLoop() }
    }

    fun stop() {
        if (!running) return
        running = false
        livenessJob?.cancel()
        livenessJob = null
        stopScanning()
        advertiser.stop()
        for ((_, gatt) in clients) runCatching { gatt.close() }
        clients.clear()
        mtuByAddress.clear()
        sendQueues.clear()
        sendInflight.clear()
        chatReassembler.clear()
        coordinator.resetOnBleStop()
        gattServer?.close()
        gattServer = null
    }

    fun sendChatMessage(deviceId: String, text: String) {
        if (text.isEmpty()) return
        when (val d = coordinator.onSendChat(deviceId, text)) {
            is PeerCoordinator.SendChatDecision.Send -> {
                enqueueChatFrames(d.address, text)
                pumpSend(d.address)
            }
            is PeerCoordinator.SendChatDecision.QueuedForReconnect -> {
                Log.w(TAG, "sendChatMessage: no client to ${d.address}; queueing and reconnecting")
                ensureClient(d.address)
            }
            PeerCoordinator.SendChatDecision.NoKnownAddress ->
                Log.w(TAG, "sendChatMessage: no known address for device=$deviceId")
        }
    }

    fun rejectPeer(deviceId: String) {
        val addrs = coordinator.rejectPeer(deviceId)
        for (addr in addrs) clients[addr]?.let { runCatching { it.disconnect() } }
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
     * connection). Marks the address force-matched so the interests gate doesn't disconnect us.
     */
    private fun ensureClient(address: String) {
        if (clients.containsKey(address)) return
        val device = adapter?.getRemoteDevice(address) ?: return
        coordinator.markForceMatched(address)
        val gatt = device.connectGatt(context, false, clientCallback) ?: return
        clients[address] = gatt
        coordinator.onClientOpened(address)
        Log.d(TAG, "ensureClient: connecting to $address")
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
        val chatChar = BluetoothGattCharacteristic(
            Protocol.CHAT_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val interestsChar = BluetoothGattCharacteristic(
            Protocol.INTERESTS_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        service.addCharacteristic(chatChar)
        service.addCharacteristic(interestsChar)
        server.addService(service)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "server conn change from=${device.address} status=$status newState=$newState")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val payload = when (characteristic.uuid) {
                Protocol.INTERESTS_CHAR_UUID ->
                    InterestsPayload.encode(state.myDeviceId, state.myInterestTexts())
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
        when (val d = coordinator.onChatReceived(address, text)) {
            PeerCoordinator.ChatReceiveDecision.Ignore -> Unit
            PeerCoordinator.ChatReceiveDecision.BufferAndEnsureClient -> {
                Log.d(TAG, "chat from unknown $address; buffering pending identity")
                ensureClient(address)
            }
            is PeerCoordinator.ChatReceiveDecision.Delivered -> {
                if (d.needsEnsureClient) ensureClient(address)
            }
        }
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
        val decision = coordinator.onScanResult(address)
        Log.d(TAG, "scan hit addr=$address rssi=${result.rssi} decision=$decision openClient=${clients.containsKey(address)}")
        if (decision is PeerCoordinator.ScanDecision.Skip) return
        if (clients.containsKey(address)) return
        val gatt = device.connectGatt(context, false, clientCallback) ?: return
        clients[address] = gatt
        coordinator.onClientOpened(address)
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
                    coordinator.onDisconnected(address)
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
                ?.getCharacteristic(Protocol.INTERESTS_CHAR_UUID) ?: return
            gatt.readCharacteristic(ch)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val address = gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pumpSend(address)
                return
            }
            val data = characteristic.value ?: ByteArray(0)
            if (characteristic.uuid == Protocol.INTERESTS_CHAR_UUID) {
                handleInterestsRead(gatt, address, data)
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
            if (status == BluetoothGatt.GATT_SUCCESS) coordinator.onWriteAcknowledged(address)
            val q = sendQueues[address]
            if (q != null) synchronized(q) { sendInflight[address] = false }
            pumpSend(address)
        }
    }

    private fun handleInterestsRead(gatt: BluetoothGatt, address: String, data: ByteArray) {
        val decoded = InterestsPayload.decode(data)
        when (val decode = coordinator.onInterestsDecoded(address, decoded)) {
            PeerCoordinator.InterestsDecodeDecision.DropDecodeFailed -> {
                Log.w(TAG, "interests decode failed from=$address")
                runCatching { gatt.disconnect() }
            }
            PeerCoordinator.InterestsDecodeDecision.DropLoopback,
            PeerCoordinator.InterestsDecodeDecision.DropRejected -> {
                runCatching { gatt.disconnect() }
            }
            is PeerCoordinator.InterestsDecodeDecision.ForceMatched -> {
                for (t in decode.pendingOutboundTexts) enqueueChatFrames(address, t)
                pumpSend(address)
            }
            is PeerCoordinator.InterestsDecodeDecision.Registered -> {
                val deviceId = decode.deviceId
                val peerInterests = decode.interests
                scope.launch {
                    val match = Services.llm.match(peerInterests)
                    Log.d(TAG, "interests read addr=$address device=$deviceId matched=${match.matched}")
                    when (val d = coordinator.onInterestsMatched(deviceId, address, match)) {
                        PeerCoordinator.InterestsMatchDecision.NotMatched ->
                            runCatching { gatt.disconnect() }
                        is PeerCoordinator.InterestsMatchDecision.Matched -> {
                            for (t in d.pendingOutboundTexts) enqueueChatFrames(address, t)
                            pumpSend(address)
                            if (d.notifyLabel != null) {
                                BleScanService.notifyMatch(context, deviceId, d.notifyLabel, d.peerInterest)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun runLivenessLoop() {
        while (running) {
            delay(LIVENESS_CHECK_INTERVAL_MS)
            if (!running) break
            val toDisconnect = coordinator.pruneStale()
            for (addr in toDisconnect) clients[addr]?.let { runCatching { it.disconnect() } }
        }
    }
}

private data class PendingWrite(val charUuid: UUID, val data: ByteArray)

/** Derive a short stable display label from a BLE address. */
fun labelFor(address: String): String {
    val tail = address.replace(":", "").takeLast(6)
    return "peer-$tail"
}
