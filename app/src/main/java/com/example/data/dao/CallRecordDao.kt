package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.CallRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {
    @Query("SELECT * FROM call_records ORDER BY timestamp DESC")
    fun getAllCallRecords(): Flow<List<CallRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallRecord(record: CallRecord)

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun deleteCallRecordById(id: Long)

    @Query("SELECT COUNT(*) FROM call_records WHERE timestamp = :timestamp")
    suspend fun getCountByTimestamp(timestamp: Long): Int

    @Query("DELETE FROM call_records")
    suspend fun deleteAllCallRecords()
}
