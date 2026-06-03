package com.goose.android.ble

/**
 * Reassembles WHOOP BLE notification chunks into complete frames.
 *
 * Ported from Rust FrameAccumulator in protocol.rs.
 * Thread-unsafe — call from a single coroutine or wrap in synchronization.
 */
internal class FrameAccumulator {
    private val buffer = ArrayDeque<Byte>()

    data class DeframeResult(
        val frames: List<ByteArray>,
        val bufferedLen: Int,
        val droppedPrefixLen: Int,
    )

    /**
     * Feed a BLE notification chunk. Returns complete frames accumulated so far.
     * Incomplete trailing frame is held in the internal buffer.
     */
    fun feed(chunk: ByteArray): DeframeResult {
        buffer.addAll(chunk.toList())

        val frames = mutableListOf<ByteArray>()
        var dropped = dropUntilFrameStart()

        while (true) {
            val expected = expectedFrameLen() ?: break
            if (buffer.size < expected) break

            frames.add(ByteArray(expected) { buffer[it] })
            repeat(expected) { buffer.removeFirst() }
            dropped += dropUntilFrameStart()
        }

        return DeframeResult(
            frames = frames,
            bufferedLen = buffer.size,
            droppedPrefixLen = dropped,
        )
    }

    fun reset() {
        buffer.clear()
    }

    /**
     * Expected total frame length (header + declared payload + CRC) for WHOOP 5 / Puffin.
     * Returns null if the buffer doesn't yet have enough bytes to determine length.
     *
     * WHOOP 5 / Maverick / Puffin / Goose layout (8-byte header):
     *   [0] = 0xaa (FRAME_START)
     *   [1] = 0x01 (version / flags)
     *   [2..3] = declared_len (LE u16): payload + 4-byte CRC
     *   [4..5] = reserved
     *   [6..7] = CRC-16 Modbus of bytes [0..5]
     *   [8..] = payload
     *   last 4 bytes = CRC-32 of payload
     */
    private fun expectedFrameLen(): Int? {
        if (buffer.size < 8) return null
        val declaredLen = (buffer[2].toInt() and 0xff) or ((buffer[3].toInt() and 0xff) shl 8)
        return 8 + declaredLen
    }

    /** Drop bytes before the first 0xaa. Returns number of dropped bytes. */
    private fun dropUntilFrameStart(): Int {
        val start = buffer.indexOfFirst { it == WhoopFrameConstants.FRAME_START }
        return when {
            start == 0 -> 0
            start > 0 -> {
                repeat(start) { buffer.removeFirst() }
                start
            }
            else -> {
                val dropped = buffer.size
                buffer.clear()
                dropped
            }
        }
    }
}
