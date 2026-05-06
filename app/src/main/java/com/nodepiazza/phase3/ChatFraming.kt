package com.nodepiazza.phase3

/**
 * Frame chat messages into MTU-sized fragments. Each fragment is `[index:1][total:1][payload...]`.
 * `index == 0` resets the receiver's buffer for that sender; `total - 1` triggers assembly.
 */
object ChatFraming {
    private const val HEADER_BYTES = 2
    private const val ATT_OPCODE_BYTES = 3

    fun maxPayload(mtu: Int): Int =
        (mtu - ATT_OPCODE_BYTES - HEADER_BYTES).coerceAtLeast(20)

    fun encode(text: String, mtu: Int): List<ByteArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        val payloadSize = maxPayload(mtu)
        val total = ((data.size + payloadSize - 1) / payloadSize).coerceAtLeast(1)
        require(total <= 255) { "message too long for byte-sized fragment count" }
        return List(total) { i ->
            val start = i * payloadSize
            val end = minOf(start + payloadSize, data.size)
            val frame = ByteArray(HEADER_BYTES + (end - start))
            frame[0] = i.toByte()
            frame[1] = total.toByte()
            data.copyInto(frame, HEADER_BYTES, start, end)
            frame
        }
    }
}
