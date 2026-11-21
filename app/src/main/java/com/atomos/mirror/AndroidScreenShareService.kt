package com.atomos.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class AndroidScreenShareService : Service() {
    private var sender: AndroidScreenSender? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sender?.stop(); sender = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val projection = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)
        else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_PROJECTION)
        }
        val pin = intent?.getStringExtra(EXTRA_PIN)
        if (projection == null || pin.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }

        createChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Atomos Mirror is sharing")
            .setContentText("Your screen is being sent over the local network.")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)

        sender?.stop()
        sender = AndroidScreenSender(applicationContext, projection, pin, intent.getBooleanExtra(EXTRA_MICROPHONE, false))
        try { sender?.start() } catch (_: Exception) { sender?.stop(); sender = null; stopSelf() }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        sender?.stop(); sender = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_PROJECTION = "projection_data"
        const val EXTRA_PIN = "pairing_pin"
        const val EXTRA_MICROPHONE = "microphone_enabled"
        const val ACTION_STOP = "com.atomos.mirror.STOP_SCREEN_SHARE"
        private const val CHANNEL_ID = "atomos_screen_share"
        private const val NOTIFICATION_ID = 701
    }
}
