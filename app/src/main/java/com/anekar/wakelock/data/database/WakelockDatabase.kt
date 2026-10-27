package com.anekar.wakelock.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.anekar.wakelock.data.dao.AlarmDao
import com.anekar.wakelock.data.model.AlarmEntity
import com.anekar.wakelock.data.model.Converters

@Database(
    entities = [AlarmEntity::class],
    version = 6, // Bumped to 6 for Penalty Rounds & Cooldown columns
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class WakelockDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

    companion object {
        @Volatile
        private var INSTANCE: WakelockDatabase? = null

        fun getDatabase(context: Context): WakelockDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WakelockDatabase::class.java,
                    "wakelock_secure_vault.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
