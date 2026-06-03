package com.goose.android.ble

/**
 * A fully-parsed WHOOP BLE frame.
 *
 * Sealed class mirrors the Rust ParsedPayload enum from protocol.rs.
 * Each variant carries the raw payload bytes so upper layers can do
 * domain-specific parsing without needing another copy of the frame.
 */
sealed class WhoopFrame {
    abstract val rawBytes: ByteArray
    abstract val packetType: Byte
    abstract val sequence: Byte?
    abstract val headerCrcValid: Boolean
    abstract val payloadCrcValid: Boolean
    abstract val warnings: List<String>

    /** 40, 43 — realtime HR / raw optical / IMU */
    data class RealtimeData(
        override val rawBytes: ByteArray,
        override val packetType: Byte,
        override val sequence: Byte?,
        val packetK: Byte?,
        val statusOrStream: Byte?,
        val counterOrPage: UInt?,
        val timestampSeconds: UInt?,
        val timestampSubseconds: UShort?,
        val bodyHex: String,
        override val headerCrcValid: Boolean,
        override val payloadCrcValid: Boolean,
        override val warnings: List<String>,
    ) : WhoopFrame() {
        override fun equals(other: Any?) = other is RealtimeData && rawBytes.contentEquals(other.rawBytes)

        override fun hashCode() = rawBytes.contentHashCode()
    }

    /** 47, 52 — historical data / historical IMU */
    data class HistoricalData(
        override val rawBytes: ByteArray,
        override val packetType: Byte,
        override val sequence: Byte?,
        val packetK: Byte?,
        val statusOrStream: Byte?,
        val counterOrPage: UInt?,
        val timestampSeconds: UInt?,
        val timestampSubseconds: UShort?,
        val bodyHex: String,
        override val headerCrcValid: Boolean,
        override val payloadCrcValid: Boolean,
        override val warnings: List<String>,
    ) : WhoopFrame() {
        override fun equals(other: Any?) = other is HistoricalData && rawBytes.contentEquals(other.rawBytes)

        override fun hashCode() = rawBytes.contentHashCode()
    }

    /** 36, 38 — command responses from the band */
    data class CommandResponse(
        override val rawBytes: ByteArray,
        override val packetType: Byte,
        override val sequence: Byte?,
        val responseToCommand: Byte?,
        val originSequence: Byte?,
        val resultCode: Byte?,
        val dataHex: String,
        override val headerCrcValid: Boolean,
        override val payloadCrcValid: Boolean,
        override val warnings: List<String>,
    ) : WhoopFrame() {
        override fun equals(other: Any?) = other is CommandResponse && rawBytes.contentEquals(other.rawBytes)

        override fun hashCode() = rawBytes.contentHashCode()
    }

    /** 48, 53, 54 — strap events */
    data class Event(
        override val rawBytes: ByteArray,
        override val packetType: Byte,
        override val sequence: Byte?,
        val eventId: UShort?,
        val eventName: String?,
        val timestampSeconds: UInt?,
        val timestampSubseconds: UShort?,
        val dataHex: String,
        override val headerCrcValid: Boolean,
        override val payloadCrcValid: Boolean,
        override val warnings: List<String>,
    ) : WhoopFrame() {
        override fun equals(other: Any?) = other is Event && rawBytes.contentEquals(other.rawBytes)

        override fun hashCode() = rawBytes.contentHashCode()
    }

    /** 49, 56 — metadata / puffin metadata */
    data class Metadata(
        override val rawBytes: ByteArray,
        override val packetType: Byte,
        override val sequence: Byte?,
        val dataHex: String,
        override val headerCrcValid: Boolean,
        override val payloadCrcValid: Boolean,
        override val warnings: List<String>,
    ) : WhoopFrame() {
        override fun equals(other: Any?) = other is Metadata && rawBytes.contentEquals(other.rawBytes)

        override fun hashCode() = rawBytes.contentHashCode()
    }

    /** 35, 37 — commands sent by app (echo from band) */
    data class Command(
        override val rawBytes: ByteArray,
        override val packetType: Byte,
        override val sequence: Byte?,
        val command: Byte?,
        val dataHex: String,
        override val headerCrcValid: Boolean,
        override val payloadCrcValid: Boolean,
        override val warnings: List<String>,
    ) : WhoopFrame() {
        override fun equals(other: Any?) = other is Command && rawBytes.contentEquals(other.rawBytes)

        override fun hashCode() = rawBytes.contentHashCode()
    }

    /** All other packet types — raw payload preserved for future parsing. */
    data class Unknown(
        override val rawBytes: ByteArray,
        override val packetType: Byte,
        override val sequence: Byte?,
        val payloadHex: String,
        override val headerCrcValid: Boolean,
        override val payloadCrcValid: Boolean,
        override val warnings: List<String>,
    ) : WhoopFrame() {
        override fun equals(other: Any?) = other is Unknown && rawBytes.contentEquals(other.rawBytes)

        override fun hashCode() = rawBytes.contentHashCode()
    }
}
