package com.nodepiazza.phase3

import com.nodepiazza.phase3.ble.labelFor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ProtocolTest {

    @Test
    fun embed_isDeterministic() {
        val a = embed("looking for a tennis partner")
        val b = embed("looking for a tennis partner")
        assertArrayEquals(a, b, 0f)
    }

    @Test
    fun embed_producesUnitLengthVector() {
        val v = embed("anyone want espresso?")
        val norm = sqrt(v.fold(0.0) { acc, x -> acc + x * x }).toFloat()
        assertEquals(1.0f, norm, 1e-3f)
    }

    @Test
    fun embed_emptyOrNoTokens_returnsZeroVector() {
        val v = embed("")
        assertEquals(Protocol.EMBEDDING_DIMS, v.size)
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun embed_differentInputsDiffer() {
        val a = embed("selling road bike")
        val b = embed("buying espresso machine")
        assertNotEquals(0f, (a zip b.toList()).sumOf { (x, y) -> ((x - y) * (x - y)).toDouble() })
    }

    @Test
    fun toInt8Bytes_clampsToSignedRange() {
        val over = floatArrayOf(2f, -2f, 0.5f, -0.5f)
        val bytes = over.toInt8Bytes()
        assertEquals(127.toByte(), bytes[0])
        assertEquals((-127).toByte(), bytes[1])
        assertEquals(63.toByte(), bytes[2])
        assertEquals((-63).toByte(), bytes[3])
    }

    @Test
    fun cosineInt8_identicalVectors_isOne() {
        val v = embed("a b c").toInt8Bytes()
        assertEquals(1.0f, cosineInt8(v, v), 1e-3f)
    }

    @Test
    fun cosineInt8_oppositeVectors_isMinusOne() {
        val v = embed("a b c").toInt8Bytes()
        val neg = ByteArray(v.size) { (-v[it].toInt()).coerceIn(-127, 127).toByte() }
        assertEquals(-1.0f, cosineInt8(v, neg), 1e-2f)
    }

    @Test
    fun cosineInt8_zeroVector_returnsZero() {
        val v = embed("hello").toInt8Bytes()
        val zero = ByteArray(v.size)
        assertEquals(0f, cosineInt8(v, zero), 0f)
    }

    @Test
    fun cosineInt8_mismatchedSizes_returnsZero() {
        assertEquals(0f, cosineInt8(ByteArray(4), ByteArray(8)), 0f)
    }

    @Test
    fun embeddingPayload_roundTrips() {
        val devId = java.util.UUID.randomUUID().toString()
        val src = listOf(
            embed("tennis partner").toInt8Bytes(),
            embed("espresso").toInt8Bytes(),
            embed("road bike").toInt8Bytes(),
        )
        val decoded = EmbeddingPayload.decode(EmbeddingPayload.encode(devId, src))
        assertEquals(devId, decoded?.deviceId)
        assertEquals(src.size, decoded?.embeddings?.size)
        for (i in src.indices) assertArrayEquals(src[i], decoded?.embeddings?.get(i))
    }

    @Test
    fun embeddingPayload_capsAtMaxPromptsPerDevice() {
        val devId = java.util.UUID.randomUUID().toString()
        val many = List(Protocol.MAX_PROMPTS_PER_DEVICE + 5) { embed("p$it").toInt8Bytes() }
        val decoded = EmbeddingPayload.decode(EmbeddingPayload.encode(devId, many))
        assertEquals(Protocol.MAX_PROMPTS_PER_DEVICE, decoded?.embeddings?.size)
    }

    @Test
    fun embeddingPayload_rejectsWrongVersion() {
        val devId = java.util.UUID.randomUUID().toString()
        val good = EmbeddingPayload.encode(devId, listOf(embed("x").toInt8Bytes()))
        good[0] = 99
        assertNull(EmbeddingPayload.decode(good))
    }

    @Test
    fun embeddingPayload_rejectsWrongDims() {
        val devId = java.util.UUID.randomUUID().toString()
        val good = EmbeddingPayload.encode(devId, listOf(embed("x").toInt8Bytes()))
        // Header layout: [version:1][deviceId:16][count:1][dims:1]; dims is at index 18.
        good[18] = (Protocol.EMBEDDING_DIMS - 1).toByte()
        assertNull(EmbeddingPayload.decode(good))
    }

    @Test
    fun embeddingPayload_rejectsTruncated() {
        val devId = java.util.UUID.randomUUID().toString()
        val good = EmbeddingPayload.encode(devId, listOf(embed("x").toInt8Bytes()))
        val truncated = good.copyOf(good.size - 10)
        assertNull(EmbeddingPayload.decode(truncated))
    }

    @Test
    fun embeddingPayload_emptyOrTooShort_returnsNull() {
        assertNull(EmbeddingPayload.decode(ByteArray(0)))
        assertNull(EmbeddingPayload.decode(ByteArray(2)))
    }

    @Test
    fun labelFor_takesLastSixHexChars() {
        assertEquals("peer-ABCDEF", labelFor("12:34:56:AB:CD:EF"))
    }

    @Test
    fun promptsPayload_roundTrips() {
        val src = listOf("tennis partner", "espresso", "road bike")
        val decoded = PromptsPayload.decode(PromptsPayload.encode(src))
        assertEquals(src, decoded)
    }

    @Test
    fun promptsPayload_capsAtMaxPromptsPerDevice() {
        val many = List(Protocol.MAX_PROMPTS_PER_DEVICE + 5) { "p$it" }
        val decoded = PromptsPayload.decode(PromptsPayload.encode(many))
        assertEquals(Protocol.MAX_PROMPTS_PER_DEVICE, decoded.size)
    }

    @Test
    fun promptsPayload_truncatesLongPrompt() {
        val long = "x".repeat(Protocol.MAX_PROMPT_BYTES + 50)
        val decoded = PromptsPayload.decode(PromptsPayload.encode(listOf(long)))
        assertEquals(1, decoded.size)
        assertEquals(Protocol.MAX_PROMPT_BYTES, decoded[0].length)
    }

    @Test
    fun promptsPayload_rejectsWrongVersion() {
        val good = PromptsPayload.encode(listOf("hi"))
        good[0] = 99
        assertEquals(0, PromptsPayload.decode(good).size)
    }

    @Test
    fun promptsPayload_truncatedReturnsPartial() {
        val good = PromptsPayload.encode(listOf("alpha", "beta"))
        val truncated = good.copyOf(good.size - 2)
        val decoded = PromptsPayload.decode(truncated)
        assertTrue(decoded.size <= 1)
    }

    @Test
    fun promptsPayload_emptyOrTooShort_returnsEmpty() {
        assertEquals(0, PromptsPayload.decode(ByteArray(0)).size)
        assertEquals(0, PromptsPayload.decode(ByteArray(1)).size)
    }
}
