package com.goose.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoopFrameTest {

    // ---- equality: same bytes = equal ----

    @Test
    fun realtimeData_sameBytes_areEqual() {
        val raw = byteArrayOf(0xaa.toByte(), 0x01)
        val a = WhoopFrame.RealtimeData(
            rawBytes = raw,
            packetType = 40,
            sequence = 1,
            packetK = 2,
            statusOrStream = 3,
            counterOrPage = 4u,
            timestampSeconds = 5u,
            timestampSubseconds = 6u,
            bodyHex = "ff",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        val b = WhoopFrame.RealtimeData(
            rawBytes = raw,
            packetType = 40,
            sequence = 1,
            packetK = 2,
            statusOrStream = 3,
            counterOrPage = 4u,
            timestampSeconds = 5u,
            timestampSubseconds = 6u,
            bodyHex = "ff",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun realtimeData_differentBytes_notEqual() {
        val a = WhoopFrame.RealtimeData(
            rawBytes = byteArrayOf(0xaa.toByte()),
            packetType = 40,
            sequence = 1,
            packetK = null,
            statusOrStream = null,
            counterOrPage = null,
            timestampSeconds = null,
            timestampSubseconds = null,
            bodyHex = "",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        val b = WhoopFrame.RealtimeData(
            rawBytes = byteArrayOf(0xbb.toByte()),
            packetType = 40,
            sequence = 1,
            packetK = null,
            statusOrStream = null,
            counterOrPage = null,
            timestampSeconds = null,
            timestampSubseconds = null,
            bodyHex = "",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        assertNotEquals(a, b)
    }

    // ---- equality: different subclasses not equal ----

    @Test
    fun command_vs_unknown_differentSubclasses_notEqual() {
        val raw = byteArrayOf(0xaa.toByte(), 0x01)
        val cmd = WhoopFrame.Command(
            rawBytes = raw,
            packetType = 35,
            sequence = 1,
            command = 0x91.toByte(),
            dataHex = "01",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        val unk = WhoopFrame.Unknown(
            rawBytes = raw,
            packetType = 35,
            sequence = 1,
            payloadHex = "01",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        assertNotEquals(cmd, unk)
    }

    // ---- historical data equality ----

    @Test
    fun historicalData_sameBytes_areEqual() {
        val raw = "aa0118000001e2b12f120104030201443322116655aa4dbbccddeeff475fc4d7".hexToBytes()
        val a = WhoopFrame.HistoricalData(
            rawBytes = raw,
            packetType = 47,
            sequence = 18,
            packetK = 18,
            statusOrStream = 1,
            counterOrPage = null,
            timestampSeconds = null,
            timestampSubseconds = null,
            bodyHex = "aa4dbbccddeeff",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        val b = WhoopFrame.HistoricalData(
            rawBytes = raw,
            packetType = 47,
            sequence = 18,
            packetK = 18,
            statusOrStream = 1,
            counterOrPage = null,
            timestampSeconds = null,
            timestampSubseconds = null,
            bodyHex = "aa4dbbccddeeff",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ---- event equality ----

    @Test
    fun event_sameBytes_areEqual() {
        val raw = "aa0114000001e1e1300211000403020106050000deadbeef5ba17d0e".hexToBytes()
        val a = WhoopFrame.Event(
            rawBytes = raw,
            packetType = 48,
            sequence = 2,
            eventId = 17u,
            eventName = null,
            timestampSeconds = null,
            timestampSubseconds = null,
            dataHex = "deadbeef",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        val b = WhoopFrame.Event(
            rawBytes = raw,
            packetType = 48,
            sequence = 2,
            eventId = 17u,
            eventName = null,
            timestampSeconds = null,
            timestampSubseconds = null,
            dataHex = "deadbeef",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        assertEquals(a, b)
    }

    // ---- command response equality ----

    @Test
    fun commandResponse_sameBytes_areEqual() {
        val raw = byteArrayOf(0xaa.toByte(), 0x02)
        val a = WhoopFrame.CommandResponse(
            rawBytes = raw,
            packetType = 36,
            sequence = 1,
            responseToCommand = 0x91.toByte(),
            originSequence = 1,
            resultCode = 0,
            dataHex = "",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        val b = WhoopFrame.CommandResponse(
            rawBytes = raw,
            packetType = 36,
            sequence = 1,
            responseToCommand = 0x91.toByte(),
            originSequence = 1,
            resultCode = 0,
            dataHex = "",
            headerCrcValid = true,
            payloadCrcValid = true,
            warnings = emptyList(),
        )
        assertEquals(a, b)
    }

    // ---- metadata equality ----

    @Test
    fun metadata_sameBytes_areEqual() {
        val raw = byteArrayOf(0xaa.toByte(), 0x03)
        val a = WhoopFrame.Metadata(
            rawBytes = raw,
            packetType = 49,
            sequence = null,
            dataHex = "01",
            headerCrcValid = true,
            payloadCrcValid = false,
            warnings = listOf("payload_crc_mismatch"),
        )
        val b = WhoopFrame.Metadata(
            rawBytes = raw,
            packetType = 49,
            sequence = null,
            dataHex = "01",
            headerCrcValid = true,
            payloadCrcValid = false,
            warnings = listOf("payload_crc_mismatch"),
        )
        assertEquals(a, b)
    }

    // ---- helper ----

    private fun String.hexToBytes(): ByteArray {
        val cleaned = replace(" ", "").replace("\n", "")
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
