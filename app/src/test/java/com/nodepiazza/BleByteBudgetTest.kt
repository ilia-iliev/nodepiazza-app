package com.nodepiazza

import com.nodepiazza.protocol.ChatFraming
import com.nodepiazza.protocol.InterestsPayload
import com.nodepiazza.protocol.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Locks the wire-level byte budgets to the actual bytes produced by the encoders so the two can't
 * drift. Two budgets:
 *  - [Protocol.MAX_PAYLOAD_BYTES]: the interests characteristic cap.
 *  - [ChatFraming.maxPayload]: per-fragment chat payload as a function of the negotiated MTU.
 */
class BleByteBudgetTest {

    private val devId: String = UUID.fromString("00000000-0000-0000-0000-0000000000aa").toString()

    // ---------- InterestsPayload ----------

    @Test
    fun interests_headerConstantMatchesActualHeader() {
        // Header = [version:1][deviceId:16][count:1] = 18 bytes. Encoding an empty list yields
        // exactly the header — guards against a silent change to the framing.
        val empty = InterestsPayload.encode(devId, emptyList())
        assertEquals(InterestsPayload.HEADER_BYTES, empty.size)
        assertEquals(18, InterestsPayload.HEADER_BYTES)
    }

    @Test
    fun interests_entryOverheadConstantMatchesActualOverhead() {
        // Adding "" (0 content bytes) must grow the encoded payload by exactly ENTRY_OVERHEAD_BYTES.
        val base = InterestsPayload.encode(devId, emptyList()).size
        val withOne = InterestsPayload.encode(devId, listOf("")).size
        assertEquals(InterestsPayload.ENTRY_OVERHEAD_BYTES, withOne - base)
        assertEquals(2, InterestsPayload.ENTRY_OVERHEAD_BYTES)
    }

    @Test
    fun interests_evaluateUsedBytesEqualsEncodedSize() {
        // The Budget the UI shows must equal the bytes actually written to the characteristic.
        // Sweep across the cap so we catch off-by-ones near the boundary.
        val inputs = listOf(
            emptyList(),
            listOf("hi"),
            listOf("tennis partner", "espresso", "road bike"),
            List(20) { "x".repeat(10) },     // many small entries, all fit
            List(8) { "x".repeat(100) },     // overflows mid-list
            List(50) { "x".repeat(50) },     // overflows aggressively
        )
        for (input in inputs) {
            val budget = InterestsPayload.evaluate(input)
            val encoded = InterestsPayload.encode(devId, input)
            assertEquals(
                "usedBytes must equal encoded size for input=$input",
                budget.usedBytes,
                encoded.size,
            )
            assertTrue(
                "encoded size $${encoded.size} > cap for input=$input",
                encoded.size <= Protocol.MAX_PAYLOAD_BYTES,
            )
        }
    }

    @Test
    fun interests_evaluateWouldUseBytesEqualsUncappedTotal() {
        // wouldUseBytes is the size the payload would have if nothing were dropped. Verify the
        // formula header + sum(2 + utf8Len) holds.
        val input = listOf("alpha", "beta", "γαμμα", "δ".repeat(100))
        val budget = InterestsPayload.evaluate(input)
        val expected = InterestsPayload.HEADER_BYTES + input.sumOf {
            InterestsPayload.ENTRY_OVERHEAD_BYTES + it.toByteArray(Charsets.UTF_8).size
        }
        assertEquals(expected, budget.wouldUseBytes)
    }

    @Test
    fun interests_entriesAreCountedInUtf8BytesNotChars() {
        // "δ" is 2 UTF-8 bytes per char. A 100-char string costs 2 + 200 = 202 bytes on the wire.
        val input = listOf("δ".repeat(100))
        val budget = InterestsPayload.evaluate(input)
        val encoded = InterestsPayload.encode(devId, input)
        assertEquals(InterestsPayload.HEADER_BYTES + 2 + 200, budget.usedBytes)
        assertEquals(budget.usedBytes, encoded.size)
    }

    @Test
    fun interests_fillsExactlyToCapWithoutDropping() {
        // Build an entry that fills the payload to exactly MAX_PAYLOAD_BYTES.
        val contentBudget =
            Protocol.MAX_PAYLOAD_BYTES - InterestsPayload.HEADER_BYTES -
                InterestsPayload.ENTRY_OVERHEAD_BYTES
        val entry = "a".repeat(contentBudget)
        val budget = InterestsPayload.evaluate(listOf(entry))
        val encoded = InterestsPayload.encode(devId, listOf(entry))
        assertEquals(listOf(true), budget.fits)
        assertEquals(Protocol.MAX_PAYLOAD_BYTES, budget.usedBytes)
        assertEquals(Protocol.MAX_PAYLOAD_BYTES, encoded.size)
    }

    @Test
    fun interests_oneByteOverCapDropsEntry() {
        // One byte past the cap must be dropped — never silently truncated.
        val contentBudget =
            Protocol.MAX_PAYLOAD_BYTES - InterestsPayload.HEADER_BYTES -
                InterestsPayload.ENTRY_OVERHEAD_BYTES
        val entry = "a".repeat(contentBudget + 1)
        val budget = InterestsPayload.evaluate(listOf(entry))
        val encoded = InterestsPayload.encode(devId, listOf(entry))
        assertEquals(listOf(false), budget.fits)
        assertEquals(InterestsPayload.HEADER_BYTES, budget.usedBytes)
        assertEquals(InterestsPayload.HEADER_BYTES, encoded.size)
    }

    @Test
    fun interests_droppedEntriesArePreciselyTheOverflowingSuffix() {
        // Greedy fill: the kept prefix matches budget.fits, and decoded count equals true-count.
        val src = List(8) { "x".repeat(100) }
        val budget = InterestsPayload.evaluate(src)
        val encoded = InterestsPayload.encode(devId, src)
        val keptCount = budget.fits.count { it }
        val decoded = InterestsPayload.decode(encoded)
        assertNotNull(decoded)
        assertEquals(keptCount, decoded!!.interests.size)
        assertEquals(src.take(keptCount), decoded.interests)
    }

    // ---------- ChatFraming ----------

    @Test
    fun chat_maxPayloadAtRequestedMtu() {
        // ATT MTU 247 → 247 − 3 (ATT opcode) − 2 (frame header) = 242 bytes of payload per frame.
        assertEquals(242, ChatFraming.maxPayload(Protocol.REQUESTED_MTU))
    }

    @Test
    fun chat_maxPayloadIsFlooredAtTwenty() {
        // Even at the BLE 4.x default MTU (23) the floor of 20 applies.
        assertEquals(20, ChatFraming.maxPayload(23))
        assertEquals(20, ChatFraming.maxPayload(0))
    }

    @Test
    fun chat_singleFrameFitsBelowMtuBudget() {
        val text = "hello"
        val frames = ChatFraming.encode(text, Protocol.REQUESTED_MTU)
        assertEquals(1, frames.size)
        val f = frames[0]
        assertEquals(0, f[0].toInt() and 0xFF)
        assertEquals(1, f[1].toInt() and 0xFF)
        // Frame must not exceed ATT MTU − opcode (=244) on a 247-byte link.
        assertTrue(f.size <= Protocol.REQUESTED_MTU - 3)
        assertEquals(text, f.copyOfRange(2, f.size).toString(Charsets.UTF_8))
    }

    @Test
    fun chat_everyFrameStaysWithinMtuMinusOpcode() {
        // Across realistic MTUs, no fragment exceeds (mtu − ATT opcode) bytes on the wire and the
        // body of each fragment is ≤ maxPayload(mtu). Note: at MTU=23 maxPayload is floored to 20,
        // so frames can be 22 bytes on the wire — that's intentional, see ChatFraming.
        val mtus = listOf(23, 64, 128, Protocol.REQUESTED_MTU, 512)
        for (mtu in mtus) {
            val maxPayload = ChatFraming.maxPayload(mtu)
            // Pick a length that produces several fragments without hitting the 255-frame cap.
            val text = "x".repeat(maxPayload * 10 + 7)
            val frames = ChatFraming.encode(text, mtu)
            for ((i, f) in frames.withIndex()) {
                val body = f.size - 2
                assertTrue(
                    "mtu=$mtu frame $i body=$body > maxPayload=$maxPayload",
                    body <= maxPayload,
                )
            }
            // All-but-last frames are filled to maxPayload; last is the remainder.
            for (i in 0 until frames.size - 1) {
                assertEquals(
                    "mtu=$mtu non-last frame $i must be full",
                    maxPayload + 2,
                    frames[i].size,
                )
            }
        }
    }

    @Test
    fun chat_fragmentationCountMatchesBytes() {
        val mtu = Protocol.REQUESTED_MTU
        val maxPayload = ChatFraming.maxPayload(mtu)
        // Pick a size that doesn't divide evenly to exercise the last-frame remainder.
        val text = "a".repeat(maxPayload * 3 + 17)
        val frames = ChatFraming.encode(text, mtu)
        assertEquals(4, frames.size)
        assertEquals(maxPayload + 2, frames[0].size)
        assertEquals(maxPayload + 2, frames[1].size)
        assertEquals(maxPayload + 2, frames[2].size)
        assertEquals(17 + 2, frames[3].size)
        for ((i, f) in frames.withIndex()) {
            assertEquals("frame $i index byte", i, f[0].toInt() and 0xFF)
            assertEquals("frame $i total byte", frames.size, f[1].toInt() and 0xFF)
        }
    }

    @Test
    fun chat_payloadsConcatenateBackToOriginal() {
        // Reassembling the payload halves of every frame must return exactly the UTF-8 bytes of the
        // original message — i.e., nothing on the wire is ever truncated or padded.
        val text = "café — espresso × 5 ☕".repeat(50)
        val expected = text.toByteArray(Charsets.UTF_8)
        val frames = ChatFraming.encode(text, Protocol.REQUESTED_MTU)
        val reassembled = ByteArray(frames.sumOf { it.size - 2 })
        var pos = 0
        for (f in frames) {
            val body = f.size - 2
            System.arraycopy(f, 2, reassembled, pos, body)
            pos += body
        }
        assertEquals(expected.size, reassembled.size)
        assertArrayEquals(expected, reassembled)
    }

    @Test
    fun chat_emptyMessageStillProducesOneFrame() {
        val frames = ChatFraming.encode("", Protocol.REQUESTED_MTU)
        assertEquals(1, frames.size)
        assertEquals(2, frames[0].size)
        assertEquals(0, frames[0][0].toInt() and 0xFF)
        assertEquals(1, frames[0][1].toInt() and 0xFF)
    }

    @Test
    fun chat_fragmentCountFitsInOneByte() {
        // The frame header reserves a single byte for `total`, so 255 fragments is the hard ceiling.
        // At MTU 247 (242-byte payloads) that's ~61 KiB; pick a size that lands exactly at 255.
        val maxPayload = ChatFraming.maxPayload(Protocol.REQUESTED_MTU)
        val justFits = "a".repeat(maxPayload * 255)
        val frames = ChatFraming.encode(justFits, Protocol.REQUESTED_MTU)
        assertEquals(255, frames.size)
        assertEquals(255, frames[0][1].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun chat_overflowingFragmentCountThrows() {
        // One byte more than the 255-fragment cap must throw, not silently truncate.
        val maxPayload = ChatFraming.maxPayload(Protocol.REQUESTED_MTU)
        val tooLong = "a".repeat(maxPayload * 255 + 1)
        ChatFraming.encode(tooLong, Protocol.REQUESTED_MTU)
    }
}
