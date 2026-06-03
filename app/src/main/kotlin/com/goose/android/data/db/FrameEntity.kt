package com.goose.android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "frames",
    indices = [
        Index("captured_at_ms"),
        Index("frame_kind"),
    ],
)
data class FrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "captured_at_ms") val capturedAtMs: Long,
    @ColumnInfo(name = "packet_type") val packetType: Int,
    @ColumnInfo(name = "sequence") val sequence: Int?,
    @ColumnInfo(name = "frame_kind") val frameKind: String,
    @ColumnInfo(name = "header_crc_valid") val headerCrcValid: Boolean,
    @ColumnInfo(name = "payload_crc_valid") val payloadCrcValid: Boolean,
    @ColumnInfo(name = "raw_hex") val rawHex: String,
    /** Unix seconds from the WHOOP band clock; null for command/metadata frames. */
    @ColumnInfo(name = "timestamp_seconds") val timestampSeconds: Long?,
    @ColumnInfo(name = "timestamp_subseconds") val timestampSubseconds: Int?,
    /** Frame-specific payload hex (bodyHex / dataHex / payloadHex depending on kind). */
    @ColumnInfo(name = "body_hex") val bodyHex: String?,
    /** JSON array of parser warning strings. */
    @ColumnInfo(name = "warnings") val warnings: String,
)
