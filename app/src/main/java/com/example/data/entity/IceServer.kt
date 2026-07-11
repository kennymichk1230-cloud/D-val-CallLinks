package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ice_servers")
data class IceServer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val url: String,
    val username: String? = null,
    val credential: String? = null,
    val isTurn: Boolean = false
)
