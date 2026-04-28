package com.nodepiazza.phase3

import com.nodepiazza.phase3.ble.labelFor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        val src = listOf(
            embed("tennis partner").toInt8Bytes(),
            embed("espresso").toInt8Bytes(),
            embed("road bike").toInt8Bytes(),
        )
        val decoded = EmbeddingPayload.decode(EmbeddingPayload.encode(src))
        assertEquals(src.size, decoded.size)
        for (i in src.indices) assertArrayEquals(src[i], decoded[i])
    }

    @Test
    fun embeddingPayload_capsAtMaxPromptsPerDevice() {
        val many = List(Protocol.MAX_PROMPTS_PER_DEVICE + 5) { embed("p$it").toInt8Bytes() }
        val decoded = EmbeddingPayload.decode(EmbeddingPayload.encode(many))
        assertEquals(Protocol.MAX_PROMPTS_PER_DEVICE, decoded.size)
    }

    @Test
    fun embeddingPayload_rejectsWrongVersion() {
        val good = EmbeddingPayload.encode(listOf(embed("x").toInt8Bytes()))
        good[0] = 99
        assertEquals(0, EmbeddingPayload.decode(good).size)
    }

    @Test
    fun embeddingPayload_rejectsWrongDims() {
        val good = EmbeddingPayload.encode(listOf(embed("x").toInt8Bytes()))
        good[2] = (Protocol.EMBEDDING_DIMS - 1).toByte()
        assertEquals(0, EmbeddingPayload.decode(good).size)
    }

    @Test
    fun embeddingPayload_rejectsTruncated() {
        val good = EmbeddingPayload.encode(listOf(embed("x").toInt8Bytes()))
        val truncated = good.copyOf(good.size - 10)
        assertEquals(0, EmbeddingPayload.decode(truncated).size)
    }

    @Test
    fun embeddingPayload_emptyOrTooShort_returnsEmpty() {
        assertEquals(0, EmbeddingPayload.decode(ByteArray(0)).size)
        assertEquals(0, EmbeddingPayload.decode(ByteArray(2)).size)
    }

    @Test
    fun labelFor_takesLastSixHexChars() {
        assertEquals("peer-ABCDEF", labelFor("12:34:56:AB:CD:EF"))
    }
}
