package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.IceServer
import kotlinx.coroutines.flow.Flow

@Dao
interface IceServerDao {
    @Query("SELECT * FROM ice_servers")
    fun getAllIceServers(): Flow<List<IceServer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIceServer(server: IceServer)

    @Delete
    suspend fun deleteIceServer(server: IceServer)

    @Query("DELETE FROM ice_servers")
    suspend fun clearAllIceServers()
}
