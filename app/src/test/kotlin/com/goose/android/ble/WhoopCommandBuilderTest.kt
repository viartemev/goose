package com.goose.android.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WhoopCommandBuilderTest {
    // ---- Frame structure ----

    @Test
    fun frame_startsWithFrameStart() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0)
        assertEquals(WhoopFrameConstants.FRAME_START, frame[0])
    }

    @Test
    fun frame_secondByteIsVersion0x01() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0)
        assertEquals(0x01.toByte(), frame[1])
    }

    @Test
    fun frame_headerCrcValid() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0)
        val expected = WhoopCrc.crc16Modbus(frame, fromIndex = 0, toIndex = 6)
        val actual = (frame[6].toInt() and 0xff) or ((frame[7].toInt() and 0xff) shl 8)
        assertEquals(expected.toInt(), actual)
    }

    @Test
    fun frame_payloadCrcValid() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0)
        val declaredLen = (frame[2].toInt() and 0xff) or ((frame[3].toInt() and 0xff) shl 8)
        val payloadEnd = frame.size - 4
        val payload = frame.sliceArray(8 until payloadEnd)
        val storedCrc = frame.sliceArray(payloadEnd until frame.size)
        val expectedCrc = WhoopCrc.crc32(payload).toLittleEndianBytes()
        assertArrayEquals(expectedCrc, storedCrc)
        assertEquals(declaredLen, payload.size + 4)
    }

    @Test
    fun frame_payloadPaddedTo4ByteAlignment() {
        // GetHello: payload = [COMMAND(35), seq, commandNumber(145)] = 3 bytes → padded to 4
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0)
        val declaredLen = (frame[2].toInt() and 0xff) or ((frame[3].toInt() and 0xff) shl 8)
        val payloadLen = declaredLen - 4 // minus CRC
        assertEquals(0, payloadLen % 4)
    }

    // ---- Payload content ----

    @Test
    fun getHello_payloadFirstByteIsCommandPacketType() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0)
        assertEquals(WhoopFrameConstants.PACKET_TYPE_COMMAND, frame[8])
    }

    @Test
    fun getHello_payloadSecondByteIsSequence() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 42)
        assertEquals(42.toByte(), frame[9])
    }

    @Test
    fun getHello_payloadThirdByteIsCommandNumber() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0)
        assertEquals(WhoopFrameConstants.COMMAND_GET_HELLO, frame[10])
    }

    @Test
    fun getDataRange_commandNumber34() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetDataRange, 0)
        assertEquals(34.toByte(), frame[10])
    }

    @Test
    fun toggleRealtimeHROn_commandNumber3_payload1() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.ToggleRealtimeHROn, 0)
        assertEquals(3.toByte(), frame[10])
        assertEquals(1.toByte(), frame[11])
    }

    @Test
    fun toggleRealtimeHROff_payload0() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.ToggleRealtimeHROff, 0)
        assertEquals(3.toByte(), frame[10])
        assertEquals(0.toByte(), frame[11])
    }

    @Test
    fun startHighFrequencySync_commandNumber96() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.StartHighFrequencySync, 0)
        assertEquals(96.toByte(), frame[10])
    }

    @Test
    fun stopHighFrequencySync_commandNumber97() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.StopHighFrequencySync, 0)
        assertEquals(97.toByte(), frame[10])
    }

    // ---- Sequence byte ----

    @Test
    fun sequence_0_encodedCorrectly() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0)
        assertEquals(0.toByte(), frame[9])
    }

    @Test
    fun sequence_255_encodedCorrectly() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0xff.toByte())
        assertEquals(0xff.toByte(), frame[9])
    }

    // ---- SetClock ----

    @Test
    fun setClock_payload8Bytes_correctLEEncoding() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.SetClock(0x12345678U, 0x9ABCDEF0U), 0)
        // payload[3..10] = clock bytes (LE u32 seconds, LE u32 subseconds)
        assertEquals(0x78.toByte(), frame[11]) // seconds low byte
        assertEquals(0x56.toByte(), frame[12])
        assertEquals(0x34.toByte(), frame[13])
        assertEquals(0x12.toByte(), frame[14])
        assertEquals(0xF0.toByte(), frame[15]) // subseconds low byte
        assertEquals(0xDE.toByte(), frame[16])
        assertEquals(0xBC.toByte(), frame[17])
        assertEquals(0x9A.toByte(), frame[18])
    }

    // ---- HistoricalDataResult ----

    @Test
    fun historicalDataResult_9BytePayload_firstByte1() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.HistoricalDataResult, 0)
        // command payload starts at frame[11]
        assertEquals(1.toByte(), frame[11])
        assertEquals(0.toByte(), frame[12])
    }

    // ---- Total frame size sanity ----

    @Test
    fun getHello_frameSizeIs16() {
        // header(8) + payload(4 after pad) + crc32(4) = 16
        val frame = WhoopCommandBuilder.build(WhoopCommand.GetHello, 0)
        assertEquals(16, frame.size)
    }

    @Test
    fun rawCommand_noPayload_frameSizeIs16() {
        val frame = WhoopCommandBuilder.build(WhoopCommand.Raw(0x50, byteArrayOf()), 0)
        assertEquals(16, frame.size)
    }

    @Test
    fun rawCommand_4BytePayload_frameSizeIs20() {
        // payload: [35, seq, cmd, b0, b1, b2, b3] = 7 bytes → padded to 8 → total 8+8+4=20
        val frame = WhoopCommandBuilder.build(WhoopCommand.Raw(0x50, byteArrayOf(1, 2, 3, 4)), 0)
        assertEquals(20, frame.size)
    }
}
