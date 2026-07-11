package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entity.Contact
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun getAllContacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteContacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE phone = :phone LIMIT 1")
    suspend fun getContactByPhone(phone: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: Contact)

    @Delete
    suspend fun deleteContact(contact: Contact)

    @Query("UPDATE contacts SET status = :status, lastSeen = :lastSeen WHERE phone = :phone")
    suspend fun updateStatus(phone: String, status: String, lastSeen: Long)

    @Query("UPDATE contacts SET isBlocked = :isBlocked WHERE phone = :phone")
    suspend fun setBlocked(phone: String, isBlocked: Boolean)

    @Query("UPDATE contacts SET isFavorite = :isFavorite WHERE phone = :phone")
    suspend fun setFavorite(phone: String, isFavorite: Boolean)
}
