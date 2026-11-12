package com.example.smartnode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartNodeDao {

    @Insert
    suspend fun insertCommand(command: CommandEntity): Long

    @Insert
    suspend fun insertTelemetry(telemetry: TelemetryEntity): Long

    @Query("SELECT * FROM commands WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedCommands(): List<CommandEntity>

    @Query("SELECT * FROM telemetry WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedTelemetry(): List<TelemetryEntity>

    @Query("UPDATE commands SET isSynced = 1 WHERE id = :id")
    suspend fun markCommandSynced(id: Long)

    @Query("UPDATE telemetry SET isSynced = 1 WHERE id = :id")
    suspend fun markTelemetrySynced(id: Long)

    @Query("SELECT * FROM telemetry ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestTelemetry(): Flow<TelemetryEntity?>

    @Query("SELECT COUNT(*) FROM commands WHERE isSynced = 0")
    fun observePendingCommandCount(): Flow<Int>
}
