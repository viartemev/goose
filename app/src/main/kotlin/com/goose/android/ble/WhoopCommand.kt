package com.goose.android.ble

/**
 * WHOOP BLE command definitions.
 *
 * Ported from GooseBLEClient.swift HistoricalCommandKind / ClockCommandKind /
 * SensorStreamCommandKind and commands.rs.
 */
sealed class WhoopCommand {
    abstract val commandNumber: Byte
    abstract val payload: ByteArray
    abstract val name: String

    // ---- Identity ----

    /** Handshake — first command sent after connection to verify firmware version. */
    object GetHello : WhoopCommand() {
        override val commandNumber: Byte = WhoopFrameConstants.COMMAND_GET_HELLO
        override val payload: ByteArray = byteArrayOf()
        override val name: String = "GET_HELLO"
    }

    // ---- Historical data ----

    /** Request the available data range from the band. */
    object GetDataRange : WhoopCommand() {
        override val commandNumber: Byte = 34
        override val payload: ByteArray = byteArrayOf()
        override val name: String = "GET_DATA_RANGE"
    }

    /** Request the band to start streaming historical data. */
    object SendHistoricalData : WhoopCommand() {
        override val commandNumber: Byte = 22
        override val payload: ByteArray = byteArrayOf()
        override val name: String = "SEND_HISTORICAL_DATA"
    }

    /**
     * Acknowledge receipt of historical data.
     * Payload: [1, 0, 0, 0, 0, 0, 0, 0, 0] signals success.
     */
    object HistoricalDataResult : WhoopCommand() {
        override val commandNumber: Byte = 23
        override val payload: ByteArray = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0)
        override val name: String = "HISTORICAL_DATA_RESULT"
    }

    // ---- Clock ----

    /** Read the band's current clock. */
    object GetClock : WhoopCommand() {
        override val commandNumber: Byte = 11
        override val payload: ByteArray = byteArrayOf()
        override val name: String = "GET_CLOCK"
    }

    /**
     * Set the band clock to [timestampSeconds] / [timestampSubseconds]
     * (split 32.32 fixed-point seconds since WHOOP epoch).
     */
    class SetClock(timestampSeconds: UInt, timestampSubseconds: UInt) : WhoopCommand() {
        override val commandNumber: Byte = 10
        override val payload: ByteArray = run {
            val buf = ByteArray(8)
            buf[0] = (timestampSeconds and 0xffU).toByte()
            buf[1] = ((timestampSeconds shr 8) and 0xffU).toByte()
            buf[2] = ((timestampSeconds shr 16) and 0xffU).toByte()
            buf[3] = ((timestampSeconds shr 24) and 0xffU).toByte()
            buf[4] = (timestampSubseconds and 0xffU).toByte()
            buf[5] = ((timestampSubseconds shr 8) and 0xffU).toByte()
            buf[6] = ((timestampSubseconds shr 16) and 0xffU).toByte()
            buf[7] = ((timestampSubseconds shr 24) and 0xffU).toByte()
            buf
        }
        override val name: String = "SET_CLOCK"
    }

    // ---- Physiology stream ----

    /** Enable real-time HR streaming (standard HR characteristic). */
    object ToggleRealtimeHROn : WhoopCommand() {
        override val commandNumber: Byte = 3
        override val payload: ByteArray = byteArrayOf(1)
        override val name: String = "TOGGLE_REALTIME_HR_ON"
    }

    /** Disable real-time HR streaming. */
    object ToggleRealtimeHROff : WhoopCommand() {
        override val commandNumber: Byte = 3
        override val payload: ByteArray = byteArrayOf(0)
        override val name: String = "TOGGLE_REALTIME_HR_OFF"
    }

    /** Enable R10/R11 realtime optical stream. */
    object SendR10R11RealtimeOn : WhoopCommand() {
        override val commandNumber: Byte = 63
        override val payload: ByteArray = byteArrayOf(1)
        override val name: String = "SEND_R10_R11_REALTIME_ON"
    }

    /** Disable R10/R11 realtime optical stream. */
    object SendR10R11RealtimeOff : WhoopCommand() {
        override val commandNumber: Byte = 63
        override val payload: ByteArray = byteArrayOf(0)
        override val name: String = "SEND_R10_R11_REALTIME_OFF"
    }

    // ---- High-frequency sync ----

    /** Start high-frequency historical sync. */
    object StartHighFrequencySync : WhoopCommand() {
        override val commandNumber: Byte = 96.toByte()
        override val payload: ByteArray = byteArrayOf()
        override val name: String = "START_HIGH_FREQUENCY_SYNC"
    }

    /** Stop high-frequency historical sync. */
    object StopHighFrequencySync : WhoopCommand() {
        override val commandNumber: Byte = 97.toByte()
        override val payload: ByteArray = byteArrayOf()
        override val name: String = "STOP_HIGH_FREQUENCY_SYNC"
    }

    // ---- Raw command (debug / future use) ----

    /** Arbitrary command — used for debug commands and forward-compatibility. */
    class Raw(
        override val commandNumber: Byte,
        override val payload: ByteArray,
        override val name: String = "RAW_0x${commandNumber.toInt().and(0xff).toString(16).uppercase()}",
    ) : WhoopCommand()
}
