package com.anekar.wakelock.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MissionDifficulty(val digits: Int, val defaultTimeoutSec: Int, val displayName: String) {
    TWO_DIGIT(2, 30, "2-Digit (6-Chain)"),
    FOUR_DIGIT(4, 40, "4-Digit (6-Chain)"),
    FIVE_DIGIT(5, 45, "5-Digit (6-Chain)"),
    SEVEN_DIGIT(7, 55, "7-Digit (6-Chain)"),
    EIGHT_DIGIT(8, 60, "8-Digit (6-Chain)")
}

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String = "ALARM PROTOCOL",
    val ringtoneUri: String = "", // Holds internal path to custom MP3/WAV or system ringtone
    val daysOfWeek: List<Int> = emptyList(),
    val isEnabled: Boolean = true,
    val missionDifficulty: MissionDifficulty = MissionDifficulty.TWO_DIGIT,
    val roundsCount: Int = 5,
    val timeoutSeconds: Int = 30,
    val taperIntervalSeconds: Int = 300,
    val taperDurationSeconds: Int = 1800,
    val wakeUpIntervalSeconds: Int = 600,
    val wakeUpTotalDurationSeconds: Int = 7200,
    val masterPinHash: String,

    // QR Desk Anchor
    val qrPayload: String = "",
    val qrChecksCount: Int = 0,

    // Smart Suspension Modes
    val skipNextOnce: Boolean = false,
    val pauseUntilTimestamp: Long = 0L,

    // Brutal Penalty Configuration
    val penaltyRoundsCount: Int = 100,
    val penaltyCooldownSeconds: Long = 43200L, // 12 Hours default

    // Active Surveillance & Anti-Tamper States
    val isSurveillanceActive: Boolean = false,
    val surveillanceStartTime: Long = 0L,
    val surveillanceEndTime: Long = 0L,
    val nextCheckTimestamp: Long = 0L,
    val isPunishmentMode: Boolean = false,
    val punishmentEndTime: Long = 0L
)
