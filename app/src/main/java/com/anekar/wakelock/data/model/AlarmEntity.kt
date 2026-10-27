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
    val hour: Int, // 0-23 format
    val minute: Int, // 0-59 format
    val label: String = "ALARM PROTOCOL",
    val ringtoneUri: String = "",
    val daysOfWeek: List<Int> = emptyList(), // 1=Mon, 2=Tue, ..., 7=Sun
    val isEnabled: Boolean = true,
    val missionDifficulty: MissionDifficulty = MissionDifficulty.TWO_DIGIT,
    val roundsCount: Int = 5,
    val timeoutSeconds: Int = 30,
    val taperIntervalSeconds: Int = 300, // 0 = OFF, 30 = 30s test, 300 = 5m prod
    val taperDurationSeconds: Int = 1800, // 60 = 1m test, 1800 = 30m prod
    val wakeUpIntervalSeconds: Int = 600, // Regular interval after taper
    val wakeUpTotalDurationSeconds: Int = 7200, // Total surveillance duration
    val masterPinHash: String, // SHA-256 hashed PIN
    
    // QR Code Integration (Physical Desk Anchor)
    val qrPayload: String = "", // Empty = Normal Mode (No QR); Non-empty = Required Desk QR
    val qrChecksCount: Int = 0, // 0 = Only Initial Alarm; 1..6 = First N taper checks require QR

    // Active Surveillance & Anti-Tamper States
    val isSurveillanceActive: Boolean = false,
    val surveillanceStartTime: Long = 0L,
    val surveillanceEndTime: Long = 0L,
    val nextCheckTimestamp: Long = 0L,
    val isPunishmentMode: Boolean = false,
    val punishmentEndTime: Long = 0L
)
