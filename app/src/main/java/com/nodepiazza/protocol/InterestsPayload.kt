package com.nodepiazza.protocol

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Pack a list of interest texts into a single characteristic payload, served via offset-based reads.
 * Prefixed with a stable per-install device ID so the receiver can dedupe across BLE address
 * rotations.
 *
 * Format: [version:1][deviceId:16][count:1][entry_0 ... entry_n], each entry is
 * `[len:2 BE][utf8 bytes...]`. The whole payload must fit in [Protocol.MAX_PAYLOAD_BYTES]; entries
 * are added greedily in order and the first one that doesn't fit (along with everything after it)
 * is dropped. Per-entry truncation is intentionally absent — clamping at the wire layer would
 * silently mutate the user's input; dropping makes the UX explicit.
 */
object InterestsPayload {
    private const val VERSION: Byte = 2
    private const val DEVICE_ID_BYTES = 16
    const val HEADER_BYTES = 1 + DEVICE_ID_BYTES + 1
    const val ENTRY_OVERHEAD_BYTES = 2

    data class Decoded(val deviceId: String, val interests: List<String>)

    /**
     * Per-list status for the UI: which entries fit, how full the payload is, and whether at least
     * one entry would be dropped.
     */
    data class Budget(
        /** Same length as input; `true` means the entry fits within the payload budget. */
        val fits: List<Boolean>,
        /** Bytes that would be used if all entries were sent (uncapped). */
        val wouldUseBytes: Int,
        /** Bytes actually consumed by the kept prefix (always ≤ MAX_PAYLOAD_BYTES). */
        val usedBytes: Int,
    ) {
        val overflow: Boolean get() = fits.any { !it }
    }

    fun evaluate(interests: List<String>): Budget {
        var used = HEADER_BYTES
        var wouldUse = HEADER_BYTES
        var overflowed = false
        val fits = ArrayList<Boolean>(interests.size)
        for (i in interests) {
            val size = ENTRY_OVERHEAD_BYTES + i.toByteArray(Charsets.UTF_8).size
            wouldUse += size
            if (!overflowed && used + size <= Protocol.MAX_PAYLOAD_BYTES) {
                used += size
                fits.add(true)
            } else {
                overflowed = true
                fits.add(false)
            }
        }
        return Budget(fits = fits, wouldUseBytes = wouldUse, usedBytes = used)
    }

    fun encode(deviceId: String, interests: List<String>): ByteArray {
        val budget = evaluate(interests)
        val kept = interests.filterIndexed { idx, _ -> budget.fits[idx] }
        val total = HEADER_BYTES + kept.sumOf { ENTRY_OVERHEAD_BYTES + it.toByteArray(Charsets.UTF_8).size }
        val buf = ByteBuffer.allocate(total)
        buf.put(VERSION)
        buf.put(uuidToBytes(deviceId))
        buf.put(kept.size.toByte())
        for (e in kept) {
            val bytes = e.toByteArray(Charsets.UTF_8)
            buf.putShort(bytes.size.toShort())
            buf.put(bytes)
        }
        return buf.array()
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < HEADER_BYTES || bytes[0] != VERSION) return null
        val deviceId = bytesToUuid(bytes, 1) ?: return null
        val count = bytes[1 + DEVICE_ID_BYTES].toInt() and 0xFF
        val out = ArrayList<String>(count)
        var pos = HEADER_BYTES
        repeat(count) {
            if (pos + 2 > bytes.size) return Decoded(deviceId, out)
            val len = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            pos += 2
            if (len < 0 || pos + len > bytes.size) return Decoded(deviceId, out)
            out += bytes.copyOfRange(pos, pos + len).toString(Charsets.UTF_8)
            pos += len
        }
        return Decoded(deviceId, out)
    }

    private fun uuidToBytes(id: String): ByteArray {
        val uuid = UUID.fromString(id)
        return ByteBuffer.allocate(DEVICE_ID_BYTES)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
    }

    private fun bytesToUuid(bytes: ByteArray, offset: Int): String? {
        if (bytes.size < offset + DEVICE_ID_BYTES) return null
        val buf = ByteBuffer.wrap(bytes, offset, DEVICE_ID_BYTES)
        val msb = buf.long
        val lsb = buf.long
        return UUID(msb, lsb).toString()
    }
}
