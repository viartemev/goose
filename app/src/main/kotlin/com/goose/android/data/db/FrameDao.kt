package com.goose.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FrameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(frame: FrameEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(frames: List<FrameEntity>): List<Long>

    @Query("SELECT * FROM frames ORDER BY captured_at_ms DESC LIMIT :limit")
    fun recentFrames(limit: Int = 200): Flow<List<FrameEntity>>

    @Query("SELECT * FROM frames WHERE frame_kind = :kind ORDER BY captured_at_ms DESC LIMIT :limit")
    fun framesByKind(
        kind: String,
        limit: Int = 200,
    ): Flow<List<FrameEntity>>

    @Query("SELECT COUNT(*) FROM frames")
    fun count(): Flow<Int>

    @Query("DELETE FROM frames WHERE captured_at_ms < :thresholdMs")
    suspend fun deleteOlderThan(thresholdMs: Long): Int
}
