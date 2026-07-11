package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_records")
data class CallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactName: String,
    val contactPhone: String,
    val isGroup: Boolean = false,
    val isVoice: Boolean = true,
    val callType: String, // "Incoming", "Outgoing", "Missed"
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0
)
