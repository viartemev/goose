package com.goose.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Additional parser tests: CRC mismatch, unknown types, truncated frames, metadata. */
class WhoopFrameParserEdgeCaseTest {
    private lateinit var parser: WhoopFrameParser

    @Before
    fun setUp() {
        parser = WhoopFrameParser()
    }

    // ---- Header CRC mismatch ----

    @Test
    fun parse_headerCrcMismatch_warns() {
        val hex = "aa0108000001e67123019101363e5c8d"
        val bytes = hex.hexToBytes()
        // Corrupt header CRC byte at [6]
        bytes[6] = (bytes[6].toInt() xor 0xff).toByte()

        val frames = parser.push(bytes)
        assertEquals(1, frames.size)
        val frame = frames[0]
        assertFalse(frame.headerCrcValid)
        assertTrue(frame.warnings.contains("header_crc_mismatch"))
    }

    // ---- Payload CRC mismatch ----

    @Test
    fun parse_payloadCrcMismatch_warns() {
        val hex = "aa0108000001e67123019101363e5c8d"
        val bytes = hex.hexToBytes()
        // Corrupt the last byte (payload CRC)
        val last = bytes.size - 1
        bytes[last] = (bytes[last].toInt() xor 0xff).toByte()

        val frames = parser.push(bytes)
        assertEquals(1, frames.size)
        val frame = frames[0]
        assertTrue(frame.headerCrcValid)
        assertFalse(frame.payloadCrcValid)
        assertTrue(frame.warnings.contains("payload_crc_mismatch"))
    }

    // ---- Both CRCs invalid ----

    @Test
    fun parse_bothCrcsInvalid_stillParsesHeader() {
        val hex = "aa0108000001e67123019101363e5c8d"
        val bytes = hex.hexToBytes()
        bytes[6] = (bytes[6].toInt() xor 0xff).toByte()
        val last = bytes.size - 1
        bytes[last] = (bytes[last].toInt() xor 0xff).toByte()

        val frames = parser.push(bytes)
        assertEquals(1, frames.size)
        val frame = frames[0]

        assertTrue(frame is WhoopFrame.Command)
        frame as WhoopFrame.Command
        assertEquals(WhoopFrameConstants.PACKET_TYPE_COMMAND, frame.packetType)
        assertEquals(1.toByte(), frame.sequence)
        assertFalse(frame.headerCrcValid)
        assertFalse(frame.payloadCrcValid)
        assertEquals(2, frame.warnings.size)
        assertTrue(frame.warnings.contains("header_crc_mismatch"))
        assertTrue(frame.warnings.contains("payload_crc_mismatch"))
    }

    // ---- Truncated frame (too short for header) ----

    @Test
    fun parse_tooShort_returnsEmpty() {
        val frames = parser.push(byteArrayOf(0xaa.toByte(), 0x01))
        assertTrue(frames.isEmpty())
    }

    // ---- Frame without 0xAA start ----

    @Test
    fun parse_noFrameStart_returnsEmpty() {
        val frames = parser.push(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
        assertTrue(frames.isEmpty())
    }

    // ---- Unknown packet type ----

    @Test
    fun parse_unknownPacketType_returnsUnknownFrame() {
        // Build frame with packet type 0x99 (unknown)
        val payload = byteArrayOf(0x99.toByte(), 0x01, 0x02, 0x03)
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Unknown)
        val unk = frames[0] as WhoopFrame.Unknown
        assertEquals(0x99.toByte(), unk.packetType)
    }

    // ---- CommandResponse frame ----

    @Test
    fun parse_commandResponse() {
        // Build a valid CommandResponse frame:
        // payload: [packetType=36, sequence, responseToCmd=0x91, originSeq=1, resultCode=0, data...]
        val payload =
            byteArrayOf(
                36.toByte(), // PACKET_TYPE_COMMAND_RESPONSE
                9.toByte(), // sequence
                0x91.toByte(), // COMMAND_GET_HELLO
                1.toByte(), // origin_sequence
                0.toByte(), // result_code (success)
                0xaa.toByte(),
                0xbb.toByte(),
                0xcc.toByte(),
            )
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.CommandResponse)
        val resp = frames[0] as WhoopFrame.CommandResponse
        assertEquals(WhoopFrameConstants.PACKET_TYPE_COMMAND_RESPONSE, resp.packetType)
        assertEquals(9.toByte(), resp.sequence)
        assertEquals(0x91.toByte(), resp.responseToCommand)
        assertEquals(1.toByte(), resp.originSequence)
        assertEquals(0.toByte(), resp.resultCode)
        assertEquals("aabbcc", resp.dataHex)
        assertTrue(resp.headerCrcValid)
        assertTrue(resp.payloadCrcValid)
    }

    // ---- Metadata frame ----

    @Test
    fun parse_metadata() {
        val payload =
            byteArrayOf(
                49.toByte(), // PACKET_TYPE_METADATA
                1.toByte(), // sequence
                0xde.toByte(),
                0xad.toByte(),
                0xbe.toByte(),
                0xef.toByte(),
            )
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Metadata)
        val meta = frames[0] as WhoopFrame.Metadata
        assertEquals(WhoopFrameConstants.PACKET_TYPE_METADATA, meta.packetType)
        assertEquals(1.toByte(), meta.sequence)
        // Metadata dataHex includes packetType (49=0x31) + sequence (1=0x01) + data = "3101deadbeef"
        assertEquals("3101deadbeef", meta.dataHex)
        assertTrue(meta.headerCrcValid)
        assertTrue(meta.payloadCrcValid)
    }

    // ---- Command with extra data ----

    @Test
    fun parse_commandWithExtraData() {
        val payload =
            byteArrayOf(
                35.toByte(), // PACKET_TYPE_COMMAND
                1.toByte(), // sequence
                0x92.toByte(), // some command (not GET_HELLO)
                0x01.toByte(),
                0x02.toByte(),
                0x03.toByte(),
            )
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Command)
        val cmd = frames[0] as WhoopFrame.Command
        assertEquals(35.toByte(), cmd.packetType)
        assertEquals(0x92.toByte(), cmd.command)
        assertEquals("010203", cmd.dataHex)
    }

    // ---- Short command (no data, exactly 3 bytes payload) ----

    @Test
    fun parse_commandMinimalPayload() {
        val payload =
            byteArrayOf(
                35.toByte(), // PACKET_TYPE_COMMAND
                1.toByte(), // sequence
                0x91.toByte(), // COMMAND_GET_HELLO
            )
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Command)
        val cmd = frames[0] as WhoopFrame.Command
        assertEquals(0x91.toByte(), cmd.command)
        assertEquals("", cmd.dataHex)
        assertTrue(cmd.warnings.isEmpty()) // 3 bytes = not too short
    }

    // ---- Short command payload (< 3 bytes) ----

    @Test
    fun parse_commandTooShort_warns() {
        val payload =
            byteArrayOf(
                35.toByte(), // PACKET_TYPE_COMMAND
                1.toByte(), // sequence (only 2 bytes, no command byte)
            )
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Command)
        val cmd = frames[0] as WhoopFrame.Command
        assertTrue(cmd.warnings.contains("command_payload_too_short"))
    }

    // ---- Short command response payload ----

    @Test
    fun parse_commandResponseTooShort_warns() {
        val payload =
            byteArrayOf(
                36.toByte(), // PACKET_TYPE_COMMAND_RESPONSE
                1.toByte(), // sequence
                0x91.toByte(), // responseToCommand
                1.toByte(), // originSequence (only 4 bytes, missing resultCode)
            )
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.CommandResponse)
        val resp = frames[0] as WhoopFrame.CommandResponse
        assertTrue(resp.warnings.contains("command_response_payload_too_short"))
    }

    // ---- Short event payload ----

    @Test
    fun parse_eventTooShort_warns() {
        val payload =
            byteArrayOf(
                48.toByte(), // PACKET_TYPE_EVENT
                2.toByte(), // sequence
                17.toByte(),
                0.toByte(), // event ID (only 4 bytes total)
            )
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Event)
        val event = frames[0] as WhoopFrame.Event
        assertTrue(event.warnings.contains("event_payload_header_too_short"))
    }

    // ---- Short data packet ----

    @Test
    fun parse_realtimeDataTooShort_warns() {
        val payload =
            byteArrayOf(
                40.toByte(), // PACKET_TYPE_REALTIME_DATA
                1.toByte(), // sequence + packetK (only 2 bytes, too short)
            )
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.RealtimeData)
        val data = frames[0] as WhoopFrame.RealtimeData
        assertTrue(data.warnings.contains("data_packet_header_too_short"))
    }

    // ---- Puffin command type (37) ----

    @Test
    fun parse_puffinCommand_parsedAsCommand() {
        val payload =
            byteArrayOf(
                37.toByte(), // PACKET_TYPE_PUFFIN_COMMAND
                1.toByte(), // sequence
                0x91.toByte(), // COMMAND_GET_HELLO
            )
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Command)
        assertEquals(37.toByte(), frames[0].packetType)
    }

    // ---- droppedFrameCount increments with garbage ----

    @Test
    fun droppedFrameCount_incrementsOnGarbagePrefix() {
        val garbage = "deadbeef"
        val hello = "aa0108000001e67123019101363e5c8d"
        parser.push((garbage + hello).hexToBytes())
        assertTrue(parser.droppedFrameCount > 0)
    }

    // ---- droppedFrameCount reset with reset() ----

    @Test
    fun reset_clearsDroppedFrameCount() {
        val garbage = "deadbeef"
        val hello = "aa0108000001e67123019101363e5c8d"
        parser.push((garbage + hello).hexToBytes())
        assertTrue(parser.droppedFrameCount > 0)

        parser.reset()
        assertEquals(0, parser.droppedFrameCount)
    }

    // ---- RealtimeData with full header ----

    @Test
    fun parse_realtimeDataFullHeader() {
        // Build realtime data with valid header
        val payload = ByteArray(32)
        payload[0] = 40 // PACKET_TYPE_REALTIME_DATA
        payload[1] = 10 // packet_k
        payload[2] = 1 // status_or_stream
        // counter: LE u32 = 0x01020304 at offset 3
        payload[3] = 0x04
        payload[4] = 0x03
        payload[5] = 0x02
        payload[6] = 0x01
        // timestamp: LE u32 = 0x44332211 at offset 7
        payload[7] = 0x11
        payload[8] = 0x22
        payload[9] = 0x33
        payload[10] = 0x44
        // subseconds: LE u16 = 0x6655 at offset 11
        payload[11] = 0x55
        payload[12] = 0x66
        // body: put some bytes
        payload[13] = 0xde.toByte()
        payload[14] = 0xad.toByte()

        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.RealtimeData)
        val data = frames[0] as WhoopFrame.RealtimeData
        assertEquals(10.toByte(), data.packetK)
        assertEquals(1.toByte(), data.statusOrStream)
        assertEquals(0x01020304u, data.counterOrPage)
        assertEquals(0x44332211u, data.timestampSeconds)
        assertEquals(0x6655.toUShort(), data.timestampSubseconds)
        // body: bytes [13..32] = 0xde, 0xad, then zeros → hex includes trailing zeros
        assertTrue(data.bodyHex.startsWith("dead"))
        assertTrue(data.warnings.isEmpty())
    }

    // ---- RealtimeData partial stream allowed ----

    @Test
    fun parse_realtimeData_fullFrame_streamingType_parsedWithCrcValid() {
        val payload = ByteArray(32)
        payload[0] = 40 // PACKET_TYPE_REALTIME_DATA
        val fullFrame = buildFrame(payload)

        val frames = parser.push(fullFrame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.RealtimeData)
        assertTrue(frames[0].payloadCrcValid)
    }

    @Test
    fun parse_realtimeData_truncated_frameAccumulator_holdsUntilFullDeclaredLength() {
        // FrameAccumulator uses declared_len from header (bytes [2..3]) + 8.
        // For a 32-byte payload: declared_len = 36, expected total = 44 bytes.
        // A truncated chunk (e.g. 34 bytes) stays in the buffer, no frame emitted.
        val payload = ByteArray(32)
        payload[0] = 40 // PACKET_TYPE_REALTIME_DATA
        val fullFrame = buildFrame(payload)
        val truncated = fullFrame.sliceArray(0 until fullFrame.size - 10) // 34 bytes

        val partial = parser.push(truncated)
        assertEquals(0, partial.size) // not enough for FrameAccumulator to emit yet

        // Feeding the rest completes the frame with valid CRC (it's a full frame)
        val remaining = fullFrame.sliceArray(truncated.size until fullFrame.size)
        val complete = parser.push(remaining)
        assertEquals(1, complete.size)
        assertTrue(complete[0] is WhoopFrame.RealtimeData)
        assertTrue(complete[0].payloadCrcValid)
    }

    // ---- Non-streaming type truncated: no frame emitted ----

    @Test
    fun parse_command_truncated_nonStreamingType_returnsEmpty() {
        val payload =
            byteArrayOf(
                35.toByte(), // PACKET_TYPE_COMMAND
                1.toByte(), // sequence
                0x91.toByte(), // COMMAND_GET_HELLO
                0x01.toByte(),
                0x02.toByte(),
                0x03.toByte(),
                0x04.toByte(),
                0x05.toByte(),
                0x06.toByte(),
                0x07.toByte(),
                0x08.toByte(),
            )
        val fullFrame = buildFrame(payload)
        // Truncate significantly
        val truncated = fullFrame.sliceArray(0 until fullFrame.size / 2)

        // Minimum header is 8 bytes, declaredLen = payload.size + 4 = 14 + 4 = 18
        // truncated is ~14 bytes, less than 8 + 18 = 26
        val frames = parser.push(truncated)
        // Frame might be buffered or dropped depending on len vs declaredLen
        // If truncated < declared + header, and not streaming, extractPayload returns null
        assertTrue(frames.isEmpty())
    }

    // ---- Multiple different frame types mixed ----

    @Test
    fun parse_threeDifferentFrameTypes() {
        val hello = buildFrame(byteArrayOf(35, 1, 0x91.toByte()))
        val event =
            buildFrame(
                byteArrayOf(
                    48,
                    2,
                    17,
                    0, // event ID = 17 LE
                    4,
                    3,
                    2,
                    1, // timestamp seconds LE
                    6,
                    5, // timestamp subseconds LE
                    0xde.toByte(),
                    0xad.toByte(),
                ),
            )
        val metadata = buildFrame(byteArrayOf(49, 3, 0xbe.toByte(), 0xef.toByte()))

        val combined = hello + event + metadata
        val frames = parser.push(combined)
        assertEquals(3, frames.size)
        assertTrue(frames[0] is WhoopFrame.Command)
        assertTrue(frames[1] is WhoopFrame.Event)
        assertTrue(frames[2] is WhoopFrame.Metadata)
    }

    // ---- Puffin metadata type 56 ----

    @Test
    fun parse_puffinMetadata_parsedAsMetadata() {
        val payload = byteArrayOf(56.toByte(), 1.toByte(), 0x01.toByte(), 0x02.toByte())
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Metadata)
        assertEquals(56.toByte(), frames[0].packetType)
    }

    // ---- Console logs type 50 parsed as Metadata ----

    @Test
    fun parse_consoleLogs_parsedAsMetadata() {
        val payload = byteArrayOf(50.toByte(), 1.toByte(), 0x01.toByte(), 0x02.toByte())
        val frame = buildFrame(payload)

        val frames = parser.push(frame)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WhoopFrame.Metadata)
        assertEquals(50.toByte(), frames[0].packetType)
    }

    // ---- buildFrame helper: builds valid WHOOP 5 frame with CRC ---

    private fun buildFrame(payload: ByteArray): ByteArray {
        val declaredLen = payload.size + 4 // payload + 4-byte CRC-32
        val header = ByteArray(8)
        header[0] = 0xaa.toByte()
        header[1] = 0x01
        header[2] = (declaredLen and 0xff).toByte()
        header[3] = ((declaredLen shr 8) and 0xff).toByte()
        // bytes 4,5 reserved
        val crc16 = WhoopCrc.crc16Modbus(header, toIndex = 6)
        header[6] = crc16.toByte()
        header[7] = (crc16.toInt() shr 8).toByte()

        val payloadCrc = WhoopCrc.crc32(payload)
        val crcBytes = payloadCrc.toLittleEndianBytes()

        return header + payload + crcBytes
    }

    // ---- hex helper ----

    private fun String.hexToBytes(): ByteArray {
        val cleaned = replace(" ", "").replace("\n", "")
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
