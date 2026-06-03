package com.goose.android.data.db

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

// Minimal entity to satisfy Room. Full schema added in T-014..T-016.
@Entity(tableName = "event_log")
data class EventLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val tag: String,
    val message: String,
)

@Database(
    entities = [EventLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GooseDatabase : RoomDatabase()
