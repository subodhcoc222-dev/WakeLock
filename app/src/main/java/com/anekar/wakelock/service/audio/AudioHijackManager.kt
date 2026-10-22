package com.anekar.wakelock.service.audio

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

class AudioHijackManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var volumeObserver: ContentObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    fun forceMaxVolume() {
        try {
            val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)
            
            val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startVolumeLock() {
        forceMaxVolume()
        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                forceMaxVolume()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver as ContentObserver
        )
    }

    fun stopVolumeLock() {
        volumeObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            volumeObserver = null
        }
    }

    fun startAlarmTone(customUri: String?) {
        stopAlarmTone()
        startVolumeLock()

        val alertUri: Uri = if (!customUri.isNullOrBlank()) {
            Uri.parse(customUri)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()
            )
            setDataSource(context, alertUri)
            isLooping = true
            prepare()
            start()
        }
    }

    fun playDoubleBeep() {
        forceMaxVolume()
        val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val beepPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()
            )
            setDataSource(context, notificationUri)
            prepare()
            start()
        }

        beepPlayer.setOnCompletionListener {
            handler.postDelayed({
                val secondBeep = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )
                    setDataSource(context, notificationUri)
                    prepare()
                    start()
                }
                secondBeep.setOnCompletionListener { player -> player.release() }
            }, 180)
            it.release()
        }
    }

    fun stopAlarmTone() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
            mediaPlayer = null
        }
        stopVolumeLock()
    }
}
