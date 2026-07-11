package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val phone: String,
    val name: String,
    val avatarUrl: String = "",
    val isFavorite: Boolean = false,
    val isBlocked: Boolean = false,
    val group: String = "None", // "None", "Family", "Work", "Friends", etc.
    val status: String = "Offline", // "Online", "Offline", "Busy", "Ringing", "In Call"
    val lastSeen: Long = System.currentTimeMillis()
)

