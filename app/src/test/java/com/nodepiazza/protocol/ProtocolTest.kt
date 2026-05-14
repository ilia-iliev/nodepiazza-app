package com.nodepiazza.protocol

import com.nodepiazza.ble.labelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun labelFor_takesLastSixHexChars() {
        assertEquals("peer-ABCDEF", labelFor("12:34:56:AB:CD:EF"))
    }

    @Test
    fun interestsPayload_roundTrips() {
        val devId = java.util.UUID.randomUUID().toString()
        val src = listOf("tennis partner", "espresso", "road bike")
        val decoded = InterestsPayload.decode(InterestsPayload.encode(devId, src))
        assertNotNull(decoded)
        assertEquals(devId, decoded!!.deviceId)
        assertEquals(src, decoded.interests)
    }

    @Test
    fun interestsPayload_dropsOverflowingEntries() {
        val devId = java.util.UUID.randomUUID().toString()
        // Each ~100-byte entry costs 102 bytes on the wire. With 494 content bytes available
        // (512 − 18 header), entries 0..3 fit (4 * 102 = 408), entry 4 wouldn't.
        val src = List(8) { "x".repeat(100) }
        val payload = InterestsPayload.encode(devId, src)
        assertTrue("payload exceeds wire cap", payload.size <= Protocol.MAX_PAYLOAD_BYTES)
        val decoded = InterestsPayload.decode(payload)
        assertNotNull(decoded)
        assertTrue("expected some entries dropped", decoded!!.interests.size < src.size)
        // Kept prefix must match the input order.
        assertEquals(src.take(decoded.interests.size), decoded.interests)
    }

    @Test
    fun interestsPayload_evaluateFlagsOverflowChips() {
        val src = List(8) { "x".repeat(100) }
        val budget = InterestsPayload.evaluate(src)
        assertEquals(src.size, budget.fits.size)
        assertTrue("at least one entry should overflow", budget.overflow)
        // Once we drop, every later entry is also dropped.
        val firstDrop = budget.fits.indexOf(false)
        assertTrue(firstDrop > 0)
        assertTrue(budget.fits.subList(firstDrop, budget.fits.size).all { !it })
    }

    @Test
    fun interestsPayload_evaluateNoOverflowWhenAllFit() {
        val src = listOf("tennis partner", "espresso", "road bike")
        val budget = InterestsPayload.evaluate(src)
        assertTrue(budget.fits.all { it })
        assertFalse(budget.overflow)
        assertTrue(budget.usedBytes <= Protocol.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun interestsPayload_rejectsWrongVersion() {
        val devId = java.util.UUID.randomUUID().toString()
        val good = InterestsPayload.encode(devId, listOf("hi"))
        good[0] = 99
        assertNull(InterestsPayload.decode(good))
    }

    @Test
    fun interestsPayload_truncatedReturnsPartial() {
        val devId = java.util.UUID.randomUUID().toString()
        val good = InterestsPayload.encode(devId, listOf("alpha", "beta"))
        val truncated = good.copyOf(good.size - 2)
        val decoded = InterestsPayload.decode(truncated)
        assertNotNull(decoded)
        assertTrue(decoded!!.interests.size <= 1)
    }

    @Test
    fun interestsPayload_emptyOrTooShort_returnsNull() {
        assertNull(InterestsPayload.decode(ByteArray(0)))
        assertNull(InterestsPayload.decode(ByteArray(1)))
    }
}
