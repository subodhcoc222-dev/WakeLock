package com.anekar.wakelock.data.model

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromDaysList(days: List<Int>?): String {
        return days?.joinToString(separator = ",") ?: ""
    }

    @TypeConverter
    fun toDaysList(data: String?): List<Int> {
        if (data.isNullOrEmpty()) return emptyList()
        return data.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    @TypeConverter
    fun fromDifficulty(diff: MissionDifficulty?): String {
        return diff?.name ?: MissionDifficulty.SEVEN_DIGIT.name
    }

    @TypeConverter
    fun toDifficulty(name: String?): MissionDifficulty {
        return try {
            if (name != null) MissionDifficulty.valueOf(name) else MissionDifficulty.SEVEN_DIGIT
        } catch (e: Exception) {
            MissionDifficulty.SEVEN_DIGIT
        }
    }
}
