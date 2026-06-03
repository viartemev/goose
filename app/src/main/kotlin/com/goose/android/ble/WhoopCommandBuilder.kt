package com.goose.android.ble

/**
 * Builds framed BLE write payloads for WHOOP V5 / Puffin / Maverick commands.
 *
 * Frame layout (ported from GooseBLEClient+Parsing.swift buildV5CommandFrame):
 *
 *   [0]     = 0xaa (FRAME_START)
 *   [1]     = 0x01 (version)
 *   [2..3]  = declared_len LE u16  (payload_len + 4-byte CRC)
 *   [4..5]  = 0x00, 0x01 (reserved)
 *   [6..7]  = CRC-16 Modbus of bytes [0..5]
 *   [8..]   = payload (padded to 4-byte alignment)
 *   last 4  = CRC-32 of payload
 *
 * Payload layout:
 *   [0] = PACKET_TYPE_COMMAND (35)
 *   [1] = sequence byte
 *   [2] = command number
 *   [3..] = command data
 */
object WhoopCommandBuilder {
    /**
     * Builds a complete framed V5 command ready to write to the GATT command characteristic.
     *
     * @param command  the command to encode
     * @param sequence monotonically incrementing byte, wraps at 255 → 0
     */
    fun build(
        command: WhoopCommand,
        sequence: Byte,
    ): ByteArray {
        var payload =
            mutableListOf(
                WhoopFrameConstants.PACKET_TYPE_COMMAND,
                sequence,
                command.commandNumber,
            )
        payload.addAll(command.payload.toList())

        // Pad to 4-byte alignment
        val padding = if (payload.size % 4 == 0) 0 else 4 - payload.size % 4
        repeat(padding) { payload.add(0) }

        val payloadBytes = payload.toByteArray()
        val payloadCrc = WhoopCrc.crc32(payloadBytes)
        val declaredLen = (payloadBytes.size + 4).toUShort()

        val header =
            byteArrayOf(
                0xaa.toByte(),
                0x01,
                (declaredLen and 0xffU).toByte(),
                ((declaredLen.toInt() shr 8) and 0xff).toByte(),
                0x00,
                0x01,
            )
        val headerCrc = WhoopCrc.crc16Modbus(header)

        return header +
            byteArrayOf(
                (headerCrc and 0xffU).toByte(),
                ((headerCrc.toInt() shr 8) and 0xff).toByte(),
            ) +
            payloadBytes +
            payloadCrc.toLittleEndianBytes()
    }
}
