package com.anekar.wakelock.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.anekar.wakelock.R
import com.anekar.wakelock.ui.AlarmTriggerActivity

object NotificationHelper {
    const val CHANNEL_ALARM_ID = "wakelock_alarm_channel"
    const val CHANNEL_SURVEILLANCE_ID = "wakelock_surveillance_channel"
    const val CHANNEL_PUNISHMENT_ID = "wakelock_punishment_channel"

    const val NOTIFICATION_ALARM_ID = 1001
    const val NOTIFICATION_SURVEILLANCE_ID = 1002
    const val NOTIFICATION_PUNISHMENT_ID = 1003

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // चैनल साउंड को null रखा गया है ताकि Android सिस्टम और AudioHijackManager का डबल साउंड ओवरलैप न हो
            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                context.getString(R.string.channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_alarm_desc)
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val surveillanceChannel = NotificationChannel(
                CHANNEL_SURVEILLANCE_ID,
                context.getString(R.string.channel_surveillance_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_surveillance_desc)
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val punishmentChannel = NotificationChannel(
                CHANNEL_PUNISHMENT_ID,
                context.getString(R.string.channel_punishment_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_punishment_desc)
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(alarmChannel)
            notificationManager.createNotificationChannel(surveillanceChannel)
            notificationManager.createNotificationChannel(punishmentChannel)
        }
    }

    fun buildAlarmNotification(context: Context, alarmId: Long, label: String): Notification {
        val fullScreenIntent = Intent(context, AlarmTriggerActivity::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            alarmId.toInt(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ALARM_ID)
            .setSmallIcon(R.drawable.ic_lock_alarm)
            .setContentTitle("WAKELOCK // $label")
            .setContentText("ACTIVE PROTOCOL TARGET REACHED")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setSound(null) // नोटिफिकेशन साउंड म्यूट, केवल AudioHijackManager आवाज़ संभालेगा
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }
}
