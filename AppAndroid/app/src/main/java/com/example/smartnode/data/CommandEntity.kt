package com.example.smartnode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "commands")
data class CommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: String,                              // "led_on" | "led_off"
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)
