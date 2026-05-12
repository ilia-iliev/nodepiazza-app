@file:Suppress("DEPRECATION")

package com.nodepiazza.phase3.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import android.util.Log
import com.nodepiazza.phase3.Protocol

private const val TAG = "BleAdvertiser"

/** Wraps the BLE advertiser lifecycle so [BleCore] doesn't carry advertising state. */
@SuppressLint("MissingPermission")
internal class BleAdvertiser(private val adapter: BluetoothAdapter?) {

    private var advertising = false

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings?) {
            Log.d(TAG, "advertise started: $settingsInEffect")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise start failure: $errorCode")
            advertising = false
        }
    }

    fun start() {
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
        advertiser.startAdvertising(settings, data, callback)
        advertising = true
    }

    fun stop() {
        if (!advertising) return
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        advertising = false
    }
}
