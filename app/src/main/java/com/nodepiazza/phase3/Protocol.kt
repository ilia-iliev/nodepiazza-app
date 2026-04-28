package com.nodepiazza.phase3

import java.nio.ByteBuffer
import java.util.Random
import java.util.UUID
import kotlin.math.sqrt

object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000001")
    val EMBEDDING_CHAR_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000002")
    val CHAT_CHAR_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000003")

    const val EMBEDDING_DIMS = 128
    const val MATCH_THRESHOLD = 0.55f
    const val MAX_PROMPTS_PER_DEVICE = 8
    const val REQUESTED_MTU = 247
}

/** Deterministic bag-of-words random projection into a unit-length float vector. */
fun embed(text: String): FloatArray {
    val vec = FloatArray(Protocol.EMBEDDING_DIMS)
    val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
    for (word in words) {
        val rand = Random(word.hashCode().toLong())
        for (i in vec.indices) vec[i] += rand.nextGaussian().toFloat()
    }
    val norm = sqrt(vec.fold(0.0) { acc, v -> acc + v * v }).toFloat()
    if (norm > 0f) for (i in vec.indices) vec[i] = vec[i] / norm
    return vec
}

/** Quantize a unit-length float vector to int8 bytes. */
fun FloatArray.toInt8Bytes(): ByteArray {
    val out = ByteArray(size)
    for (i in indices) {
        val v = (this[i] * 127f).coerceIn(-127f, 127f).toInt()
        out[i] = v.toByte()
    }
    return out
}

/** Cosine similarity between two int8-quantized embeddings. */
fun cosineInt8(a: ByteArray, b: ByteArray): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0
    var na = 0
    var nb = 0
    for (i in a.indices) {
        val x = a[i].toInt()
        val y = b[i].toInt()
        dot += x * y
        na += x * x
        nb += y * y
    }
    if (na == 0 || nb == 0) return 0f
    return (dot / (sqrt(na.toDouble()) * sqrt(nb.toDouble()))).toFloat()
}

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
