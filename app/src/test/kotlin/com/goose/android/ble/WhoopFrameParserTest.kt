package com.goose.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Fixture-driven parser tests. Hex frames from Rust/core/fixtures/synthetic. */
class WhoopFrameParserTest {
    private lateinit var parser: WhoopFrameParser

    @Before
    fun setUp() {
        parser = WhoopFrameParser()
    }

    // ---- GET_HELLO command frame ----
    // hex: aa0108000001e67123019101363e5c8d
    // expected: packet_type=35 (COMMAND), sequence=1, command=0x91=145 (GET_HELLO), CRCs valid

    @Test
    fun parse_getHello_singleChunk() {
        val hex = "aa0108000001e67123019101363e5c8d"
        val frames = parser.push(hex.hexToBytes())

        assertEquals(1, frames.size)
        val frame = frames[0]
        assertTrue(frame is WhoopFrame.Command)
        frame as WhoopFrame.Command

        assertEquals(WhoopFrameConstants.PACKET_TYPE_COMMAND, frame.packetType)
        assertEquals(1.toByte(), frame.sequence)
        assertEquals(WhoopFrameConstants.COMMAND_GET_HELLO, frame.command)
        assertTrue(frame.headerCrcValid)
        assertTrue(frame.payloadCrcValid)
        assertTrue(frame.warnings.isEmpty())
    }

    @Test
    fun parse_getHello_splitAcrossChunks() {
        val hex = "aa0108000001e67123019101363e5c8d"
        val bytes = hex.hexToBytes()
        val mid = bytes.size / 2

        val partial = parser.push(bytes.sliceArray(0 until mid))
        assertEquals(0, partial.size)

        val complete = parser.push(bytes.sliceArray(mid until bytes.size))
        assertEquals(1, complete.size)
        assertTrue(complete[0] is WhoopFrame.Command)
    }

    // ---- HISTORICAL_DATA k18 frame ----
    // hex: aa0118000001e2b12f120104030201443322116655aa4dbbccddeeff475fc4d7
    // expected: packet_type=47 (HISTORICAL_DATA), sequence=18, CRCs valid

    @Test
    fun parse_historicalK18() {
        val hex = "aa0118000001e2b12f120104030201443322116655aa4dbbccddeeff475fc4d7"
        val frames = parser.push(hex.hexToBytes())

        assertEquals(1, frames.size)
        val frame = frames[0]
        assertTrue(frame is WhoopFrame.HistoricalData)
        frame as WhoopFrame.HistoricalData

        assertEquals(WhoopFrameConstants.PACKET_TYPE_HISTORICAL_DATA, frame.packetType)
        assertEquals(18.toByte(), frame.sequence)
        assertEquals(18.toByte(), frame.packetK)        // packet_k = 0x12 = 18
        assertEquals(1.toByte(), frame.statusOrStream)  // 0x01
        assertTrue(frame.headerCrcValid)
        assertTrue(frame.payloadCrcValid)
        assertTrue(frame.warnings.isEmpty())
    }

    // ---- EVENT (temperature) frame ----
    // hex: aa0114000001e1e1300211000403020106050000deadbeef5ba17d0e
    // expected: packet_type=48 (EVENT), sequence=2, event_id=17, CRCs valid

    @Test
    fun parse_temperatureEvent() {
        val hex = "aa0114000001e1e1300211000403020106050000deadbeef5ba17d0e"
        val frames = parser.push(hex.hexToBytes())

        assertEquals(1, frames.size)
        val frame = frames[0]
        assertTrue(frame is WhoopFrame.Event)
        frame as WhoopFrame.Event

        assertEquals(WhoopFrameConstants.PACKET_TYPE_EVENT, frame.packetType)
        assertEquals(2.toByte(), frame.sequence)
        assertEquals(17.toUShort(), frame.eventId)
        assertTrue(frame.headerCrcValid)
        assertTrue(frame.payloadCrcValid)
        assertTrue(frame.warnings.isEmpty())
    }

    // ---- Multiple frames in one chunk ----

    @Test
    fun parse_twoFramesConcatenated() {
        val hello = "aa0108000001e67123019101363e5c8d"
        val event = "aa0114000001e1e1300211000403020106050000deadbeef5ba17d0e"
        val combined = (hello + event).hexToBytes()

        val frames = parser.push(combined)
        assertEquals(2, frames.size)
        assertTrue(frames[0] is WhoopFrame.Command)
        assertTrue(frames[1] is WhoopFrame.Event)
    }

    // ---- Garbage prefix is dropped ----

    @Test
    fun parse_garbagePrefix_droppedBeforeFrame() {
        val garbage = "deadbeef"
        val hello = "aa0108000001e67123019101363e5c8d"
        val bytes = (garbage + hello).hexToBytes()

        val frames = parser.push(bytes)
        assertEquals(1, frames.size)
        assertTrue(parser.droppedFrameCount > 0)
    }

    // ---- Empty input returns empty list ----

    @Test
    fun parse_empty_returnsEmpty() {
        val frames = parser.push(byteArrayOf())
        assertTrue(frames.isEmpty())
    }

    // ---- reset() clears state ----

    @Test
    fun reset_clearsPartialBuffer() {
        val hex = "aa0108000001e67123019101363e5c8d"
        val bytes = hex.hexToBytes()
        parser.push(bytes.sliceArray(0 until 4))

        parser.reset()
        val frames = parser.push(bytes)
        assertEquals(1, frames.size)
    }

    // ---- FrameAccumulator round-trips ----

    @Test
    fun accumulator_tinyChunks_reassembles() {
        val hex = "aa0108000001e67123019101363e5c8d"
        val bytes = hex.hexToBytes()

        val localParser = WhoopFrameParser()
        var frames = emptyList<WhoopFrame>()
        for (byte in bytes) {
            frames = localParser.push(byteArrayOf(byte))
        }
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Command)
    }
}

// ---- helpers ----

private fun String.hexToBytes(): ByteArray {
    val cleaned = replace(" ", "").replace("\n", "")
    return ByteArray(cleaned.length / 2) { i ->
        cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
