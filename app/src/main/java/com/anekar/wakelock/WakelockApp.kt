package com.anekar.wakelock

import android.app.Application
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.util.NotificationHelper

class WakelockApp : Application() {

    val database: WakelockDatabase by lazy {
        WakelockDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
    }
}
