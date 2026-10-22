package com.anekar.wakelock.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.anekar.wakelock.data.model.AlarmEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC")
    fun getAllAlarmsFlow(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC")
    suspend fun getAllAlarms(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id LIMIT 1")
    suspend fun getAlarmById(id: Long): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE isEnabled = 1")
    suspend fun getEnabledAlarms(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE isSurveillanceActive = 1 LIMIT 1")
    suspend fun getActiveSurveillanceAlarm(): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE isPunishmentMode = 1 LIMIT 1")
    suspend fun getActivePunishmentAlarm(): AlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: AlarmEntity): Long

    @Update
    suspend fun updateAlarm(alarm: AlarmEntity)

    @Delete
    suspend fun deleteAlarm(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarmById(id: Long)

    @Query("UPDATE alarms SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateAlarmState(id: Long, isEnabled: Boolean)

    @Query("UPDATE alarms SET isSurveillanceActive = :isActive, surveillanceStartTime = :startTime, surveillanceEndTime = :endTime, nextCheckTimestamp = :nextCheck WHERE id = :id")
    suspend fun updateSurveillanceState(id: Long, isActive: Boolean, startTime: Long, endTime: Long, nextCheck: Long)

    @Query("UPDATE alarms SET isPunishmentMode = :isPunishment, punishmentEndTime = :endTime WHERE id = :id")
    suspend fun updatePunishmentState(id: Long, isPunishment: Boolean, endTime: Long)
}
