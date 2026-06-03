package com.goose.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FrameAccumulatorTest {
    private lateinit var accumulator: FrameAccumulator

    @Before
    fun setUp() {
        accumulator = FrameAccumulator()
    }

    // ---- empty feed ----

    @Test
    fun emptyFeed_returnsEmptyFrames() {
        val result = accumulator.feed(byteArrayOf())
        assertTrue(result.frames.isEmpty())
        assertEquals(0, result.bufferedLen)
        assertEquals(0, result.droppedPrefixLen)
    }

    // ---- single complete frame ----

    @Test
    fun singleCompleteFrame_emitsOneFrame() {
        val hex = "aa0108000001e67123019101363e5c8d"
        val result = accumulator.feed(hex.hexToBytes())
        assertEquals(1, result.frames.size)
        assertEquals(0, result.bufferedLen)
        assertEquals(0, result.droppedPrefixLen)
    }

    // ---- garbage prefix is dropped ----

    @Test
    fun garbagePrefix_droppedAndFrameEmitted() {
        val garbage = byteArrayOf(0xde.toByte(), 0xad.toByte())
        val frame = "aa0108000001e67123019101363e5c8d".hexToBytes()
        val input = garbage + frame

        val result = accumulator.feed(input)

        assertEquals(1, result.frames.size)
        assertEquals(0, result.bufferedLen)
        assertEquals(2, result.droppedPrefixLen)
    }

    // ---- partial frame is buffered ----

    @Test
    fun partialFrame_bufferedWithoutEmitting() {
        val bytes = "aa0108000001e67123019101363e5c8d".hexToBytes()
        val firstChunk = bytes.sliceArray(0 until 6)

        val result = accumulator.feed(firstChunk)

        assertTrue(result.frames.isEmpty())
        assertEquals(6, result.bufferedLen)
        assertEquals(0, result.droppedPrefixLen)
    }

    // ---- frame split in two chunks reassembled ----

    @Test
    fun splitFrame_reassembledAcrossFeeds() {
        val bytes = "aa0108000001e67123019101363e5c8d".hexToBytes()
        val mid = bytes.size / 2

        val first = accumulator.feed(bytes.sliceArray(0 until mid))
        assertTrue(first.frames.isEmpty())

        val second = accumulator.feed(bytes.sliceArray(mid until bytes.size))
        assertEquals(1, second.frames.size)
        assertEquals(0, second.bufferedLen)
    }

    // ---- two concatenated frames ----

    @Test
    fun twoFramesConcatenated_bothEmitted() {
        val hello = "aa0108000001e67123019101363e5c8d"
        val event = "aa0114000001e1e1300211000403020106050000deadbeef5ba17d0e"
        val combined = (hello + event).hexToBytes()

        val result = accumulator.feed(combined)
        assertEquals(2, result.frames.size)
        assertEquals(0, result.bufferedLen)
    }

    // ---- two frames with garbage between ----

    @Test
    fun garbageBetweenFrames_droppedAndBothFramesEmitted() {
        val garbage = byteArrayOf(0xbb.toByte(), 0xcc.toByte())
        val hello = "aa0108000001e67123019101363e5c8d".hexToBytes()
        val event = "aa0114000001e1e1300211000403020106050000deadbeef5ba17d0e".hexToBytes()
        val input = hello + garbage + event

        val result = accumulator.feed(input)

        assertEquals(2, result.frames.size)
        // inter-frame garbage (2 bytes) counted in droppedPrefixLen
        assertTrue(result.droppedPrefixLen >= 2)
        assertEquals(0, result.bufferedLen)
    }

    // ---- multiple garbage prefixes across feeds ----

    @Test
    fun multipleGarbagePrefixes_droppedAndFirstFrameEmitted() {
        val g1 = byteArrayOf(0x01, 0x02)
        val g2 = byteArrayOf(0x03)
        val frame = "aa0108000001e67123019101363e5c8d".hexToBytes()

        // No 0xAA in g1 → dropUntilFrameStart clears all 2 bytes
        val r1 = accumulator.feed(g1)
        assertEquals(2, r1.droppedPrefixLen)

        // No 0xAA in g2 → buffer [0x03] cleared → dropped 1
        val r2 = accumulator.feed(g2)
        assertEquals(1, r2.droppedPrefixLen)

        // Frame starts with 0xAA → emitted
        val r3 = accumulator.feed(frame)
        assertEquals(1, r3.frames.size)
    }

    // ---- reset clears buffer ----

    @Test
    fun reset_clearsPartialBuffer() {
        val bytes = "aa0108000001e67123019101363e5c8d".hexToBytes()
        accumulator.feed(bytes.sliceArray(0 until 4))

        accumulator.reset()
        val result = accumulator.feed(bytes)
        assertEquals(1, result.frames.size)
    }

    // ---- byte-by-byte feed ----

    @Test
    fun byteByByteFeed_reassemblesFrame() {
        val bytes = "aa0108000001e67123019101363e5c8d".hexToBytes()
        var totalFrames = 0

        for (i in bytes.indices) {
            val result = accumulator.feed(byteArrayOf(bytes[i]))
            totalFrames += result.frames.size
        }

        assertEquals(1, totalFrames)
    }

    // ---- declared length larger than actual ----

    @Test
    fun declaredLengthTooLarge_bufferedUntilEnoughData() {
        // Frame with declared_len=100 (0x0064), but only 8 bytes available
        val bytes =
            byteArrayOf(
                0xaa.toByte(),
                0x01,
                0x64,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            )
        val result = accumulator.feed(bytes)
        assertTrue(result.frames.isEmpty())
        assertEquals(8, result.bufferedLen)
    }

    @Test
    fun declaredLengthAboveMaxFrameLen_droppedAndResynchronizes() {
        val oversizedHeader =
            byteArrayOf(
                0xaa.toByte(),
                0x01,
                0xff.toByte(),
                0xff.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
            )
        val validFrame = "aa0108000001e67123019101363e5c8d".hexToBytes()

        val result = accumulator.feed(oversizedHeader + validFrame)

        assertEquals(1, result.frames.size)
        assertEquals(validFrame.toList(), result.frames[0].toList())
        assertTrue(result.droppedPrefixLen >= 8)
        assertEquals(0, result.bufferedLen)
    }

    // ---- helper ----

    private fun String.hexToBytes(): ByteArray {
        val cleaned = replace(" ", "").replace("\n", "")
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
