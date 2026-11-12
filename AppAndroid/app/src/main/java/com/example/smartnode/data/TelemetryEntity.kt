package com.example.smartnode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telemetry")
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val temperature: Double,
    val humidity: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)
