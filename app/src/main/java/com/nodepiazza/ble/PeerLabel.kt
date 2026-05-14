package com.nodepiazza.ble

/** Derive a short stable display label from a BLE address. */
fun labelFor(address: String): String {
    val tail = address.replace(":", "").takeLast(6)
    return "peer-$tail"
}
