package com.goose.android.data.db

import com.goose.android.ble.WhoopFrame

object FrameMapper {
    fun WhoopFrame.toEntity(capturedAtMs: Long): FrameEntity =
        FrameEntity(
            capturedAtMs = capturedAtMs,
            packetType = packetType.toInt() and 0xFF,
            sequence = sequence?.toInt()?.and(0xFF),
            frameKind = frameKind(),
            headerCrcValid = headerCrcValid,
            payloadCrcValid = payloadCrcValid,
            rawHex = rawBytes.toHex(),
            timestampSeconds = bandTimestampSeconds(),
            timestampSubseconds = bandTimestampSubseconds(),
            bodyHex = payloadHex(),
            warnings = warnings.toJsonArray(),
        )

    private fun WhoopFrame.frameKind(): String =
        when (this) {
            is WhoopFrame.RealtimeData -> "realtime"
            is WhoopFrame.HistoricalData -> "historical"
            is WhoopFrame.CommandResponse -> "command_response"
            is WhoopFrame.Event -> "event"
            is WhoopFrame.Metadata -> "metadata"
            is WhoopFrame.Command -> "command"
            is WhoopFrame.Unknown -> "unknown"
        }

    private fun WhoopFrame.bandTimestampSeconds(): Long? =
        when (this) {
            is WhoopFrame.RealtimeData -> timestampSeconds?.toLong()
            is WhoopFrame.HistoricalData -> timestampSeconds?.toLong()
            is WhoopFrame.Event -> timestampSeconds?.toLong()
            else -> null
        }

    private fun WhoopFrame.bandTimestampSubseconds(): Int? =
        when (this) {
            is WhoopFrame.RealtimeData -> timestampSubseconds?.toInt()
            is WhoopFrame.HistoricalData -> timestampSubseconds?.toInt()
            is WhoopFrame.Event -> timestampSubseconds?.toInt()
            else -> null
        }

    private fun WhoopFrame.payloadHex(): String? =
        when (this) {
            is WhoopFrame.RealtimeData -> bodyHex
            is WhoopFrame.HistoricalData -> bodyHex
            is WhoopFrame.CommandResponse -> dataHex
            is WhoopFrame.Event -> dataHex
            is WhoopFrame.Metadata -> dataHex
            is WhoopFrame.Command -> dataHex
            is WhoopFrame.Unknown -> payloadHex
        }

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    internal fun List<String>.toJsonArray(): String =
        joinToString(prefix = "[", postfix = "]", separator = ",") {
            "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
}
