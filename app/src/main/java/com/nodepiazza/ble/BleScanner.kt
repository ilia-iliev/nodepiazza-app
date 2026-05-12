@file:Suppress("DEPRECATION")

package com.nodepiazza.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import com.nodepiazza.protocol.Protocol

private const val TAG = "BleScanner"

/** Wraps the BLE scanner lifecycle so [BleCore] doesn't carry scanning state. */
@SuppressLint("MissingPermission")
internal class BleScanner(
    private val adapter: BluetoothAdapter?,
    private val onResult: (ScanResult) -> Unit,
) {

    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) onResult(r)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
        }
    }

    fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        scanner.startScan(listOf(filter), settings, callback)
        scanning = true
    }

    fun stop() {
        if (!scanning) return
        adapter?.bluetoothLeScanner?.stopScan(callback)
        scanning = false
    }
}
