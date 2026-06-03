package com.goose.android.ble

/** CRC functions ported from Rust/core/src/protocol.rs */
@OptIn(ExperimentalUnsignedTypes::class)
internal object WhoopCrc {
    /**
     * CRC-16 Modbus (used for WHOOP 5 / Maverick / Puffin header validation).
     * Polynomial 0xa001, initial value 0xffff.
     */
    fun crc16Modbus(
        data: ByteArray,
        fromIndex: Int = 0,
        toIndex: Int = data.size,
    ): UShort {
        var crc = 0xffff
        for (i in fromIndex until toIndex) {
            crc = crc xor (data[i].toInt() and 0xff)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1
            }
        }
        return crc.toUShort()
    }

    /**
     * CRC-32 (used for payload validation).
     * Standard ISO 3309 / ITU-T V.42 polynomial used by crc32fast crate.
     */
    fun crc32(
        data: ByteArray,
        fromIndex: Int = 0,
        toIndex: Int = data.size,
    ): UInt {
        var crc = 0xffffffffU
        for (i in fromIndex until toIndex) {
            val byte = data[i].toInt() and 0xff
            crc = crc32Table[((crc xor byte.toUInt()) and 0xffU).toInt()] xor (crc shr 8)
        }
        return crc xor 0xffffffffU
    }

    private val crc32Table: UIntArray =
        UIntArray(256) { i ->
            var c = i.toUInt()
            repeat(8) {
                c = if (c and 1U != 0U) 0xedb88320U xor (c shr 1) else c shr 1
            }
            c
        }
}
