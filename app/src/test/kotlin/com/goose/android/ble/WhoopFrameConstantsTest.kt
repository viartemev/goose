package com.goose.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoopFrameConstantsTest {

    // ---- packetTypeName: known types ----

    @Test
    fun packetTypeName_knownTypes_returnNames() {
        assertEquals("COMMAND", WhoopFrameConstants.packetTypeName(35))
        assertEquals("COMMAND_RESPONSE", WhoopFrameConstants.packetTypeName(36))
        assertEquals("PUFFIN_COMMAND", WhoopFrameConstants.packetTypeName(37))
        assertEquals("PUFFIN_COMMAND_RESPONSE", WhoopFrameConstants.packetTypeName(38))
        assertEquals("REALTIME_DATA", WhoopFrameConstants.packetTypeName(40))
        assertEquals("REALTIME_RAW_DATA", WhoopFrameConstants.packetTypeName(43))
        assertEquals("HISTORICAL_DATA", WhoopFrameConstants.packetTypeName(47))
        assertEquals("EVENT", WhoopFrameConstants.packetTypeName(48))
        assertEquals("METADATA", WhoopFrameConstants.packetTypeName(49))
        assertEquals("CONSOLE_LOGS", WhoopFrameConstants.packetTypeName(50))
        assertEquals("REALTIME_IMU_DATA_STREAM", WhoopFrameConstants.packetTypeName(51))
        assertEquals("HISTORICAL_IMU_DATA_STREAM", WhoopFrameConstants.packetTypeName(52))
        assertEquals("RELATIVE_PUFFIN_EVENTS", WhoopFrameConstants.packetTypeName(53))
        assertEquals("PUFFIN_EVENTS_FROM_STRAP", WhoopFrameConstants.packetTypeName(54))
        assertEquals("RELATIVE_BATTERY_PACK_CONSOLE_LOGS", WhoopFrameConstants.packetTypeName(55))
        assertEquals("PUFFIN_METADATA", WhoopFrameConstants.packetTypeName(56))
    }

    // ---- packetTypeName: unknown types ----

    @Test
    fun packetTypeName_unknownType_returnsNull() {
        assertNull(WhoopFrameConstants.packetTypeName(0))
        assertNull(WhoopFrameConstants.packetTypeName(100))
        assertNull(WhoopFrameConstants.packetTypeName(255.toByte()))
    }

    // ---- packetTypeName: edge cases ----

    @Test
    fun packetTypeName_negativeByte_returnsNull() {
        // Bytes with MSB set (>127) that aren't in the map
        assertNull(WhoopFrameConstants.packetTypeName(0x80.toByte()))
        assertNull(WhoopFrameConstants.packetTypeName(0xff.toByte()))
    }

    // ---- isPartialDataPacketTypeAllowed ----

    @Test
    fun isPartialDataPacketTypeAllowed_streamingTypes_returnTrue() {
        assertTrue(WhoopFrameConstants.isPartialDataPacketTypeAllowed(40)) // REALTIME_DATA
        assertTrue(WhoopFrameConstants.isPartialDataPacketTypeAllowed(43)) // REALTIME_RAW_DATA
        assertTrue(WhoopFrameConstants.isPartialDataPacketTypeAllowed(47)) // HISTORICAL_DATA
        assertTrue(WhoopFrameConstants.isPartialDataPacketTypeAllowed(51)) // REALTIME_IMU_DATA_STREAM
        assertTrue(WhoopFrameConstants.isPartialDataPacketTypeAllowed(52)) // HISTORICAL_IMU_DATA_STREAM
    }

    @Test
    fun isPartialDataPacketTypeAllowed_nonStreamingTypes_returnFalse() {
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(35)) // COMMAND
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(36)) // COMMAND_RESPONSE
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(37)) // PUFFIN_COMMAND
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(38)) // PUFFIN_COMMAND_RESPONSE
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(48)) // EVENT
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(49)) // METADATA
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(50)) // CONSOLE_LOGS
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(53)) // RELATIVE_PUFFIN_EVENTS
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(54)) // PUFFIN_EVENTS_FROM_STRAP
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(55)) // RELATIVE_BATTERY_PACK_CONSOLE_LOGS
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(56)) // PUFFIN_METADATA
    }

    @Test
    fun isPartialDataPacketTypeAllowed_unknownType_returnsFalse() {
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(0))
        assertFalse(WhoopFrameConstants.isPartialDataPacketTypeAllowed(100))
    }

    // ---- FRAME_START constant ----

    @Test
    fun frameStart_is0xAA() {
        assertEquals(0xaa.toByte(), WhoopFrameConstants.FRAME_START)
    }

    // ---- COMMAND_GET_HELLO constant ----

    @Test
    fun commandGetHello_is0x91() {
        assertEquals(145.toByte(), WhoopFrameConstants.COMMAND_GET_HELLO)
    }
}
