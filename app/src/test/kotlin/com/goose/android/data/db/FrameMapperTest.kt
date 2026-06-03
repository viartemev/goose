package com.goose.android.data.db

import com.goose.android.ble.WhoopFrame
import com.goose.android.data.db.FrameMapper.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameMapperTest {
    private val capturedAtMs = 1_700_000_000_000L

    private fun realtimeFrame(
        packetType: Byte = 0x28,
        sequence: Byte? = 0x01,
        timestampSeconds: UInt? = 1_700_000_000u,
        timestampSubseconds: UShort? = 512u,
        warnings: List<String> = emptyList(),
    ) = WhoopFrame.RealtimeData(
        rawBytes = byteArrayOf(0xAA.toByte(), 0x01),
        packetType = packetType,
        sequence = sequence,
        packetK = 0x01,
        statusOrStream = 0x00,
        counterOrPage = 0u,
        timestampSeconds = timestampSeconds,
        timestampSubseconds = timestampSubseconds,
        bodyHex = "deadbeef",
        headerCrcValid = true,
        payloadCrcValid = true,
        warnings = warnings,
    )

    @Test
    fun `realtime frame maps kind correctly`() {
        val entity = realtimeFrame().toEntity(capturedAtMs)
        assertEquals("realtime", entity.frameKind)
    }

    @Test
    fun `realtime frame maps timestamps`() {
        val entity = realtimeFrame(timestampSeconds = 1_700_000_000u, timestampSubseconds = 512u).toEntity(capturedAtMs)
        assertEquals(1_700_000_000L, entity.timestampSeconds)
        assertEquals(512, entity.timestampSubseconds)
    }

    @Test
    fun `realtime frame maps bodyHex to body_hex`() {
        val entity = realtimeFrame().toEntity(capturedAtMs)
        assertEquals("deadbeef", entity.bodyHex)
    }

    @Test
    fun `realtime frame maps packetType as unsigned`() {
        val entity = realtimeFrame(packetType = 0xFF.toByte()).toEntity(capturedAtMs)
        assertEquals(0xFF, entity.packetType)
    }

    @Test
    fun `sequence null is preserved`() {
        val entity = realtimeFrame(sequence = null).toEntity(capturedAtMs)
        assertNull(entity.sequence)
    }

    @Test
    fun `sequence byte maps as unsigned`() {
        val entity = realtimeFrame(sequence = 0xFF.toByte()).toEntity(capturedAtMs)
        assertEquals(0xFF, entity.sequence)
    }

    @Test
    fun `historical frame maps kind and timestamps`() {
        val frame =
            WhoopFrame.HistoricalData(
                rawBytes = byteArrayOf(0x2F),
                packetType = 0x2F,
                sequence = 0x05,
                packetK = null,
                statusOrStream = null,
                counterOrPage = null,
                timestampSeconds = 100u,
                timestampSubseconds = 0u,
                bodyHex = "aabb",
                headerCrcValid = true,
                payloadCrcValid = true,
                warnings = emptyList(),
            )
        val entity = frame.toEntity(capturedAtMs)
        assertEquals("historical", entity.frameKind)
        assertEquals(100L, entity.timestampSeconds)
        assertEquals("aabb", entity.bodyHex)
    }

    @Test
    fun `command_response frame has null timestamps`() {
        val frame =
            WhoopFrame.CommandResponse(
                rawBytes = byteArrayOf(0x24),
                packetType = 0x24,
                sequence = null,
                responseToCommand = 0x01,
                originSequence = null,
                resultCode = 0x00,
                dataHex = "0000",
                headerCrcValid = true,
                payloadCrcValid = true,
                warnings = emptyList(),
            )
        val entity = frame.toEntity(capturedAtMs)
        assertEquals("command_response", entity.frameKind)
        assertNull(entity.timestampSeconds)
        assertNull(entity.timestampSubseconds)
        assertEquals("0000", entity.bodyHex)
    }

    @Test
    fun `event frame maps kind and timestamps`() {
        val frame =
            WhoopFrame.Event(
                rawBytes = byteArrayOf(0x30),
                packetType = 0x30,
                sequence = 0x02,
                eventId = 0x11u,
                eventName = "temperature",
                timestampSeconds = 999u,
                timestampSubseconds = 100u,
                dataHex = "cafebabe",
                headerCrcValid = true,
                payloadCrcValid = true,
                warnings = emptyList(),
            )
        val entity = frame.toEntity(capturedAtMs)
        assertEquals("event", entity.frameKind)
        assertEquals(999L, entity.timestampSeconds)
        assertEquals("cafebabe", entity.bodyHex)
    }

    @Test
    fun `metadata frame has null timestamps`() {
        val frame =
            WhoopFrame.Metadata(
                rawBytes = byteArrayOf(0x31),
                packetType = 0x31,
                sequence = null,
                dataHex = "1234",
                headerCrcValid = false,
                payloadCrcValid = false,
                warnings = listOf("bad crc"),
            )
        val entity = frame.toEntity(capturedAtMs)
        assertEquals("metadata", entity.frameKind)
        assertNull(entity.timestampSeconds)
        assertEquals("1234", entity.bodyHex)
    }

    @Test
    fun `unknown frame maps payloadHex to body_hex`() {
        val frame =
            WhoopFrame.Unknown(
                rawBytes = byteArrayOf(0x99.toByte()),
                packetType = 0x99.toByte(),
                sequence = null,
                payloadHex = "ff00",
                headerCrcValid = true,
                payloadCrcValid = true,
                warnings = emptyList(),
            )
        val entity = frame.toEntity(capturedAtMs)
        assertEquals("unknown", entity.frameKind)
        assertEquals("ff00", entity.bodyHex)
    }

    @Test
    fun `rawBytes are hex-encoded correctly`() {
        val frame = realtimeFrame().copy(rawBytes = byteArrayOf(0xAA.toByte(), 0x01, 0xFF.toByte()))
        val entity = frame.toEntity(capturedAtMs)
        assertEquals("aa01ff", entity.rawHex)
    }

    @Test
    fun `empty warnings encodes to empty json array`() {
        val entity = realtimeFrame(warnings = emptyList()).toEntity(capturedAtMs)
        assertEquals("[]", entity.warnings)
    }

    @Test
    fun `warnings are json-encoded`() {
        val entity = realtimeFrame(warnings = listOf("bad crc", "short frame")).toEntity(capturedAtMs)
        assertEquals("[\"bad crc\",\"short frame\"]", entity.warnings)
    }

    @Test
    fun `warnings with quotes are escaped`() {
        val entity = realtimeFrame(warnings = listOf("say \"hello\"")).toEntity(capturedAtMs)
        assertEquals("[\"say \\\"hello\\\"\"]", entity.warnings)
    }

    @Test
    fun `capturedAtMs is preserved`() {
        val entity = realtimeFrame().toEntity(capturedAtMs)
        assertEquals(capturedAtMs, entity.capturedAtMs)
    }

    @Test
    fun `crc validity flags are mapped`() {
        val frame =
            WhoopFrame.Unknown(
                rawBytes = byteArrayOf(0x01),
                packetType = 0x01,
                sequence = null,
                payloadHex = "",
                headerCrcValid = false,
                payloadCrcValid = true,
                warnings = emptyList(),
            )
        val entity = frame.toEntity(capturedAtMs)
        assertEquals(false, entity.headerCrcValid)
        assertEquals(true, entity.payloadCrcValid)
    }
}
