package com.goose.android.ble

/** Protocol constants ported from Rust/core/src/protocol.rs */
internal object WhoopFrameConstants {
    const val FRAME_START: Byte = 0xaa.toByte()

    const val PACKET_TYPE_COMMAND: Byte = 35
    const val PACKET_TYPE_COMMAND_RESPONSE: Byte = 36
    const val PACKET_TYPE_PUFFIN_COMMAND: Byte = 37
    const val PACKET_TYPE_PUFFIN_COMMAND_RESPONSE: Byte = 38
    const val PACKET_TYPE_REALTIME_DATA: Byte = 40
    const val PACKET_TYPE_REALTIME_RAW_DATA: Byte = 43
    const val PACKET_TYPE_HISTORICAL_DATA: Byte = 47
    const val PACKET_TYPE_EVENT: Byte = 48
    const val PACKET_TYPE_METADATA: Byte = 49
    const val PACKET_TYPE_CONSOLE_LOGS: Byte = 50
    const val PACKET_TYPE_REALTIME_IMU_DATA_STREAM: Byte = 51
    const val PACKET_TYPE_HISTORICAL_IMU_DATA_STREAM: Byte = 52
    const val PACKET_TYPE_RELATIVE_PUFFIN_EVENTS: Byte = 53
    const val PACKET_TYPE_PUFFIN_EVENTS_FROM_STRAP: Byte = 54
    const val PACKET_TYPE_RELATIVE_BATTERY_PACK_CONSOLE_LOGS: Byte = 55
    const val PACKET_TYPE_PUFFIN_METADATA: Byte = 56

    const val COMMAND_GET_HELLO: Byte = 145.toByte()

    fun packetTypeName(packetType: Byte): String? =
        when (packetType) {
            PACKET_TYPE_COMMAND -> "COMMAND"
            PACKET_TYPE_COMMAND_RESPONSE -> "COMMAND_RESPONSE"
            PACKET_TYPE_PUFFIN_COMMAND -> "PUFFIN_COMMAND"
            PACKET_TYPE_PUFFIN_COMMAND_RESPONSE -> "PUFFIN_COMMAND_RESPONSE"
            PACKET_TYPE_REALTIME_DATA -> "REALTIME_DATA"
            PACKET_TYPE_REALTIME_RAW_DATA -> "REALTIME_RAW_DATA"
            PACKET_TYPE_HISTORICAL_DATA -> "HISTORICAL_DATA"
            PACKET_TYPE_EVENT -> "EVENT"
            PACKET_TYPE_METADATA -> "METADATA"
            PACKET_TYPE_CONSOLE_LOGS -> "CONSOLE_LOGS"
            PACKET_TYPE_REALTIME_IMU_DATA_STREAM -> "REALTIME_IMU_DATA_STREAM"
            PACKET_TYPE_HISTORICAL_IMU_DATA_STREAM -> "HISTORICAL_IMU_DATA_STREAM"
            PACKET_TYPE_RELATIVE_PUFFIN_EVENTS -> "RELATIVE_PUFFIN_EVENTS"
            PACKET_TYPE_PUFFIN_EVENTS_FROM_STRAP -> "PUFFIN_EVENTS_FROM_STRAP"
            PACKET_TYPE_RELATIVE_BATTERY_PACK_CONSOLE_LOGS -> "RELATIVE_BATTERY_PACK_CONSOLE_LOGS"
            PACKET_TYPE_PUFFIN_METADATA -> "PUFFIN_METADATA"
            else -> null
        }

    /** These packet types are allowed to arrive truncated (streaming data). */
    fun isPartialDataPacketTypeAllowed(packetType: Byte): Boolean =
        packetType == PACKET_TYPE_REALTIME_DATA ||
            packetType == PACKET_TYPE_REALTIME_RAW_DATA ||
            packetType == PACKET_TYPE_HISTORICAL_DATA ||
            packetType == PACKET_TYPE_REALTIME_IMU_DATA_STREAM ||
            packetType == PACKET_TYPE_HISTORICAL_IMU_DATA_STREAM
}
