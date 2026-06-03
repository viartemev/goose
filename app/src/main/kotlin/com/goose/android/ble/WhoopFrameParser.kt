package com.goose.android.ble

import com.goose.android.ble.WhoopFrameConstants.FRAME_START
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_COMMAND
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_COMMAND_RESPONSE
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_CONSOLE_LOGS
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_EVENT
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_HISTORICAL_DATA
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_HISTORICAL_IMU_DATA_STREAM
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_METADATA
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_PUFFIN_COMMAND
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_PUFFIN_COMMAND_RESPONSE
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_PUFFIN_EVENTS_FROM_STRAP
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_PUFFIN_METADATA
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_REALTIME_DATA
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_REALTIME_IMU_DATA_STREAM
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_REALTIME_RAW_DATA
import com.goose.android.ble.WhoopFrameConstants.PACKET_TYPE_RELATIVE_PUFFIN_EVENTS
import com.goose.android.ble.WhoopFrameConstants.isPartialDataPacketTypeAllowed

/**
 * Parses raw BLE notification bytes into [WhoopFrame] instances.
 *
 * Usage:
 *  1. Call [push] for each BLE notification chunk.
 *  2. Collect the returned list of complete [WhoopFrame]s.
 *
 * Ported from Rust/core/src/protocol.rs (FrameAccumulator + parse_frame).
 * Thread-unsafe — wrap in a single-threaded coroutine scope.
 */
class WhoopFrameParser {
    private val accumulator = FrameAccumulator()

    var droppedFrameCount = 0
        private set

    /** Feed a raw BLE notification chunk. Returns any newly complete frames. */
    fun push(chunk: ByteArray): List<WhoopFrame> {
        val result = accumulator.feed(chunk)
        droppedFrameCount += result.droppedPrefixLen
        return result.frames.mapNotNull { parseFrame(it) }
    }

    fun reset() {
        accumulator.reset()
        droppedFrameCount = 0
    }

    private fun parseFrame(raw: ByteArray): WhoopFrame? {
        if (raw.isEmpty() || raw[0] != FRAME_START) return null
        if (raw.size < 8) return null

        val headerCrcValid = validateHeaderCrc(raw)
        val (payload, payloadCrcValid) = extractPayload(raw)
        if (payload == null) return null

        val warnings = mutableListOf<String>()
        if (!headerCrcValid) warnings.add("header_crc_mismatch")
        if (!payloadCrcValid) warnings.add("payload_crc_mismatch")

        val packetType = payload.getOrNull(0) ?: return buildUnknown(raw, null, headerCrcValid, payloadCrcValid, warnings, "")
        val sequence = payload.getOrNull(1)

        return when (packetType) {
            PACKET_TYPE_REALTIME_DATA,
            PACKET_TYPE_REALTIME_RAW_DATA,
            PACKET_TYPE_REALTIME_IMU_DATA_STREAM,
            -> parseRealtimeData(raw, payload, packetType, sequence, headerCrcValid, payloadCrcValid, warnings)

            PACKET_TYPE_HISTORICAL_DATA,
            PACKET_TYPE_HISTORICAL_IMU_DATA_STREAM,
            -> parseHistoricalData(raw, payload, packetType, sequence, headerCrcValid, payloadCrcValid, warnings)

            PACKET_TYPE_COMMAND_RESPONSE,
            PACKET_TYPE_PUFFIN_COMMAND_RESPONSE,
            -> parseCommandResponse(raw, payload, packetType, sequence, headerCrcValid, payloadCrcValid, warnings)

            PACKET_TYPE_EVENT,
            PACKET_TYPE_RELATIVE_PUFFIN_EVENTS,
            PACKET_TYPE_PUFFIN_EVENTS_FROM_STRAP,
            -> parseEvent(raw, payload, packetType, sequence, headerCrcValid, payloadCrcValid, warnings)

            PACKET_TYPE_METADATA,
            PACKET_TYPE_PUFFIN_METADATA,
            PACKET_TYPE_CONSOLE_LOGS,
            -> parseMetadata(raw, payload, packetType, sequence, headerCrcValid, payloadCrcValid, warnings)

            PACKET_TYPE_COMMAND,
            PACKET_TYPE_PUFFIN_COMMAND,
            -> parseCommand(raw, payload, packetType, sequence, headerCrcValid, payloadCrcValid, warnings)

            else -> buildUnknown(raw, packetType, headerCrcValid, payloadCrcValid, warnings, payload.toHex())
        }
    }

    private fun validateHeaderCrc(raw: ByteArray): Boolean {
        val actual = (raw[6].toInt() and 0xff) or ((raw[7].toInt() and 0xff) shl 8)
        return WhoopCrc.crc16Modbus(raw, fromIndex = 0, toIndex = 6).toInt() == actual
    }

    private fun extractPayload(raw: ByteArray): Pair<ByteArray?, Boolean> {
        val headerLen = 8
        if (raw.size < headerLen + 4) return null to false

        val declaredLen = (raw[2].toInt() and 0xff) or ((raw[3].toInt() and 0xff) shl 8)
        val expectedTotal = headerLen + declaredLen
        val truncated = raw.size < expectedTotal

        if (truncated) {
            val partialPayload = raw.sliceArray(headerLen until raw.size)
            val packetType = partialPayload.getOrNull(0)
            val allowed = packetType != null && isPartialDataPacketTypeAllowed(packetType)
            return if (allowed) partialPayload to false else null to false
        }

        val payloadEnd = raw.size - 4
        val payload = raw.sliceArray(headerLen until payloadEnd)
        val storedCrc = raw.sliceArray(payloadEnd until raw.size)
        val expectedCrc = WhoopCrc.crc32(payload).toLittleEndianBytes()
        return payload to (storedCrc.contentEquals(expectedCrc))
    }

    private fun parseRealtimeData(
        raw: ByteArray,
        payload: ByteArray,
        packetType: Byte,
        sequence: Byte?,
        headerCrcValid: Boolean,
        payloadCrcValid: Boolean,
        warnings: MutableList<String>,
    ): WhoopFrame.RealtimeData {
        if (payload.size < 13) warnings.add("data_packet_header_too_short")
        return WhoopFrame.RealtimeData(
            rawBytes = raw,
            packetType = packetType,
            sequence = sequence,
            packetK = payload.getOrNull(1),
            statusOrStream = payload.getOrNull(2),
            counterOrPage = readU32Le(payload, 3),
            timestampSeconds = readU32Le(payload, 7),
            timestampSubseconds = readU16Le(payload, 11),
            bodyHex = payload.sliceArray(minOf(13, payload.size) until payload.size).toHex(),
            headerCrcValid = headerCrcValid,
            payloadCrcValid = payloadCrcValid,
            warnings = warnings,
        )
    }

    private fun parseHistoricalData(
        raw: ByteArray,
        payload: ByteArray,
        packetType: Byte,
        sequence: Byte?,
        headerCrcValid: Boolean,
        payloadCrcValid: Boolean,
        warnings: MutableList<String>,
    ): WhoopFrame.HistoricalData {
        if (payload.size < 13) warnings.add("data_packet_header_too_short")
        return WhoopFrame.HistoricalData(
            rawBytes = raw,
            packetType = packetType,
            sequence = sequence,
            packetK = payload.getOrNull(1),
            statusOrStream = payload.getOrNull(2),
            counterOrPage = readU32Le(payload, 3),
            timestampSeconds = readU32Le(payload, 7),
            timestampSubseconds = readU16Le(payload, 11),
            bodyHex = payload.sliceArray(minOf(13, payload.size) until payload.size).toHex(),
            headerCrcValid = headerCrcValid,
            payloadCrcValid = payloadCrcValid,
            warnings = warnings,
        )
    }

    private fun parseCommandResponse(
        raw: ByteArray,
        payload: ByteArray,
        packetType: Byte,
        sequence: Byte?,
        headerCrcValid: Boolean,
        payloadCrcValid: Boolean,
        warnings: MutableList<String>,
    ): WhoopFrame.CommandResponse {
        if (payload.size < 5) warnings.add("command_response_payload_too_short")
        return WhoopFrame.CommandResponse(
            rawBytes = raw,
            packetType = packetType,
            sequence = sequence,
            responseToCommand = payload.getOrNull(2),
            originSequence = payload.getOrNull(3),
            resultCode = payload.getOrNull(4),
            dataHex = payload.sliceArray(minOf(5, payload.size) until payload.size).toHex(),
            headerCrcValid = headerCrcValid,
            payloadCrcValid = payloadCrcValid,
            warnings = warnings,
        )
    }

    private fun parseEvent(
        raw: ByteArray,
        payload: ByteArray,
        packetType: Byte,
        sequence: Byte?,
        headerCrcValid: Boolean,
        payloadCrcValid: Boolean,
        warnings: MutableList<String>,
    ): WhoopFrame.Event {
        if (payload.size < 12) warnings.add("event_payload_header_too_short")
        val eventId = readU16Le(payload, 2)
        return WhoopFrame.Event(
            rawBytes = raw,
            packetType = packetType,
            sequence = sequence,
            eventId = eventId,
            eventName = null,
            timestampSeconds = readU32Le(payload, 4),
            timestampSubseconds = readU16Le(payload, 8),
            dataHex = payload.sliceArray(minOf(12, payload.size) until payload.size).toHex(),
            headerCrcValid = headerCrcValid,
            payloadCrcValid = payloadCrcValid,
            warnings = warnings,
        )
    }

    private fun parseMetadata(
        raw: ByteArray,
        payload: ByteArray,
        packetType: Byte,
        sequence: Byte?,
        headerCrcValid: Boolean,
        payloadCrcValid: Boolean,
        warnings: MutableList<String>,
    ): WhoopFrame.Metadata =
        WhoopFrame.Metadata(
            rawBytes = raw,
            packetType = packetType,
            sequence = sequence,
            dataHex = payload.toHex(),
            headerCrcValid = headerCrcValid,
            payloadCrcValid = payloadCrcValid,
            warnings = warnings,
        )

    private fun parseCommand(
        raw: ByteArray,
        payload: ByteArray,
        packetType: Byte,
        sequence: Byte?,
        headerCrcValid: Boolean,
        payloadCrcValid: Boolean,
        warnings: MutableList<String>,
    ): WhoopFrame.Command {
        if (payload.size < 3) warnings.add("command_payload_too_short")
        return WhoopFrame.Command(
            rawBytes = raw,
            packetType = packetType,
            sequence = sequence,
            command = payload.getOrNull(2),
            dataHex = payload.sliceArray(minOf(3, payload.size) until payload.size).toHex(),
            headerCrcValid = headerCrcValid,
            payloadCrcValid = payloadCrcValid,
            warnings = warnings,
        )
    }

    private fun buildUnknown(
        raw: ByteArray,
        packetType: Byte?,
        headerCrcValid: Boolean,
        payloadCrcValid: Boolean,
        warnings: MutableList<String>,
        payloadHex: String,
    ): WhoopFrame.Unknown =
        WhoopFrame.Unknown(
            rawBytes = raw,
            packetType = packetType ?: 0,
            sequence = null,
            payloadHex = payloadHex,
            headerCrcValid = headerCrcValid,
            payloadCrcValid = payloadCrcValid,
            warnings = warnings,
        )

    // --- helpers ---

    private fun readU32Le(
        data: ByteArray,
        offset: Int,
    ): UInt? {
        if (offset + 4 > data.size) return null
        return (data[offset].toInt() and 0xff).toUInt() or
            ((data[offset + 1].toInt() and 0xff).toUInt() shl 8) or
            ((data[offset + 2].toInt() and 0xff).toUInt() shl 16) or
            ((data[offset + 3].toInt() and 0xff).toUInt() shl 24)
    }

    private fun readU16Le(
        data: ByteArray,
        offset: Int,
    ): UShort? {
        if (offset + 2 > data.size) return null
        return ((data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)).toUShort()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun UInt.toLittleEndianBytes(): ByteArray = ByteArray(4) { i -> ((this shr (i * 8)) and 0xffU).toByte() }
