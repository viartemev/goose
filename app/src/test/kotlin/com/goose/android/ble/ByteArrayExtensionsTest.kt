package com.goose.android.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteArrayExtensionsTest {
    // ---- toHex ----

    @Test
    fun toHex_empty() {
        assertEquals("", byteArrayOf().toHex())
    }

    @Test
    fun toHex_singleByte() {
        assertEquals("aa", byteArrayOf(0xaa.toByte()).toHex())
    }

    @Test
    fun toHex_multipleBytes() {
        assertEquals("aa010800", byteArrayOf(0xaa.toByte(), 0x01, 0x08, 0x00).toHex())
    }

    @Test
    fun toHex_zeroByte() {
        assertEquals("00", byteArrayOf(0x00).toHex())
    }

    @Test
    fun toHex_allByteValues() {
        val bytes = ByteArray(256) { it.toByte() }
        val hex = bytes.toHex()
        assertEquals(512, hex.length) // 2 hex chars per byte
        assert(hex.startsWith("0001"))
        assert(hex.endsWith("feff"))
    }

    // ---- UInt.toLittleEndianBytes ----

    @Test
    fun toLittleEndianBytes_zero() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), 0u.toLittleEndianBytes())
    }

    @Test
    fun toLittleEndianBytes_smallValue() {
        // 0x01020304 → LE = [04, 03, 02, 01]
        assertArrayEquals(
            byteArrayOf(0x04, 0x03, 0x02, 0x01),
            0x01020304u.toLittleEndianBytes(),
        )
    }

    @Test
    fun toLittleEndianBytes_maxValue() {
        val bytes = 0xFFFFFFFFu.toLittleEndianBytes()
        assertEquals(4, bytes.size)
        bytes.forEach { assertEquals(0xFF.toByte(), it) }
    }

    @Test
    fun toLittleEndianBytes_roundtrip() {
        // toLittleEndianBytes → readU32Le should match
        val original = 0xDEADBEEFu
        val le = original.toLittleEndianBytes()
        val reconstructed = (
            (le[0].toInt() and 0xff).toUInt() or
                ((le[1].toInt() and 0xff).toUInt() shl 8) or
                ((le[2].toInt() and 0xff).toUInt() shl 16) or
                ((le[3].toInt() and 0xff).toUInt() shl 24)
        )
        assertEquals(original, reconstructed)
    }

    // ---- hex roundtrip for known frame ----

    @Test
    fun hexRoundtrip_getHello() {
        val hex = "aa0108000001e67123019101363e5c8d"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(hex, bytes.toHex())
    }
}
