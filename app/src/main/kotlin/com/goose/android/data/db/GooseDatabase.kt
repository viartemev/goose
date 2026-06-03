package com.goose.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FrameEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class GooseDatabase : RoomDatabase() {
    abstract fun frameDao(): FrameDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `frames` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `captured_at_ms` INTEGER NOT NULL,
                            `packet_type` INTEGER NOT NULL,
                            `sequence` INTEGER,
                            `frame_kind` TEXT NOT NULL,
                            `header_crc_valid` INTEGER NOT NULL,
                            `payload_crc_valid` INTEGER NOT NULL,
                            `raw_hex` TEXT NOT NULL,
                            `timestamp_seconds` INTEGER,
                            `timestamp_subseconds` INTEGER,
                            `body_hex` TEXT,
                            `warnings` TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_frames_captured_at_ms` ON `frames` (`captured_at_ms`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_frames_frame_kind` ON `frames` (`frame_kind`)",
                    )
                }
            }
    }
}
