package com.nodepiazza.phase3

import java.nio.ByteBuffer

/**
 * Pack a list of prompt embeddings into a single characteristic payload.
 * Format: [version:1][count:1][dims:1][embedding_0 ... embedding_n], each embedding is `dims` int8 bytes.
 */
object EmbeddingPayload {
    private const val VERSION: Byte = 1

    fun encode(embeddings: List<ByteArray>): ByteArray {
        val capped = embeddings.take(Protocol.MAX_PROMPTS_PER_DEVICE)
        val dims = Protocol.EMBEDDING_DIMS
        val buf = ByteBuffer.allocate(3 + capped.size * dims)
        buf.put(VERSION)
        buf.put(capped.size.toByte())
        buf.put(dims.toByte())
        for (e in capped) {
            require(e.size == dims) { "embedding size mismatch: ${e.size} != $dims" }
            buf.put(e)
        }
        return buf.array()
    }

    fun decode(bytes: ByteArray): List<ByteArray> {
        if (bytes.size < 3 || bytes[0] != VERSION) return emptyList()
        val count = bytes[1].toInt() and 0xFF
        val dims = bytes[2].toInt() and 0xFF
        if (dims != Protocol.EMBEDDING_DIMS) return emptyList()
        val expected = 3 + count * dims
        if (bytes.size < expected) return emptyList()
        return List(count) { i ->
            bytes.copyOfRange(3 + i * dims, 3 + (i + 1) * dims)
        }
    }
}

/**
 * Pack a list of prompt texts into a single characteristic payload, served via offset-based reads.
 * Format: [version:1][count:1][entry_0 ... entry_n], each entry is `[len:2 BE][utf8 bytes...]`.
 * Each prompt is truncated to MAX_PROMPT_BYTES; list capped at MAX_PROMPTS_PER_DEVICE.
 */
object PromptsPayload {
    private const val VERSION: Byte = 1

    fun encode(prompts: List<String>): ByteArray {
        val capped = prompts.take(Protocol.MAX_PROMPTS_PER_DEVICE).map { p ->
            val bytes = p.toByteArray(Charsets.UTF_8)
            if (bytes.size <= Protocol.MAX_PROMPT_BYTES) bytes
            else bytes.copyOfRange(0, Protocol.MAX_PROMPT_BYTES)
        }
        val total = 2 + capped.sumOf { 2 + it.size }
        val buf = ByteBuffer.allocate(total)
        buf.put(VERSION)
        buf.put(capped.size.toByte())
        for (e in capped) {
            buf.putShort(e.size.toShort())
            buf.put(e)
        }
        return buf.array()
    }

    fun decode(bytes: ByteArray): List<String> {
        if (bytes.size < 2 || bytes[0] != VERSION) return emptyList()
        val count = bytes[1].toInt() and 0xFF
        val out = ArrayList<String>(count)
        var pos = 2
        repeat(count) {
            if (pos + 2 > bytes.size) return out
            val len = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            pos += 2
            if (len < 0 || pos + len > bytes.size) return out
            out += bytes.copyOfRange(pos, pos + len).toString(Charsets.UTF_8)
            pos += len
        }
        return out
    }
}
