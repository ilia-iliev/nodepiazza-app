package com.nodepiazza.ble

import com.nodepiazza.protocol.ChatFraming
import java.util.concurrent.ConcurrentHashMap

/**
 * Reassembles multi-fragment chat messages keyed by peer address. Each fragment is
 * `[index:1][total:1][payload...]`; index 0 resets the buffer, the final fragment triggers
 * delivery via [onMessage]. The reserved `[0xFF][0x00]` sentinel is forwarded to [onPresence]
 * to signal that the peer has opened a chat with us.
 */
internal class ChatReassembler(
    private val onMessage: (address: String, text: String) -> Unit,
    private val onPresence: (address: String) -> Unit = {},
) {
    private val buffers = ConcurrentHashMap<String, ChatRecvBuffer>()

    fun clear() = buffers.clear()

    fun forget(address: String) {
        buffers.remove(address)
    }

    fun onFragment(address: String, frame: ByteArray) {
        if (frame.size < 2) return
        if (ChatFraming.isPresenceFrame(frame)) {
            onPresence(address)
            return
        }
        val index = frame[0].toInt() and 0xFF
        val total = frame[1].toInt() and 0xFF
        if (total == 0 || index >= total) return
        val payload = if (frame.size > 2) frame.copyOfRange(2, frame.size) else ByteArray(0)

        val buf = if (index == 0) {
            ChatRecvBuffer(total).also { buffers[address] = it }
        } else {
            val existing = buffers[address] ?: return
            if (existing.parts.size != total) return
            existing
        }
        buf.parts[index] = payload

        if (buf.parts.all { it != null }) {
            buffers.remove(address)
            var size = 0
            for (p in buf.parts) size += p!!.size
            val combined = ByteArray(size)
            var pos = 0
            for (p in buf.parts) {
                p!!.copyInto(combined, pos)
                pos += p.size
            }
            onMessage(address, combined.toString(Charsets.UTF_8))
        }
    }
}

private class ChatRecvBuffer(total: Int) {
    val parts: Array<ByteArray?> = arrayOfNulls(total)
}
