package com.atomos.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder

/**
 * Owns the local dashboard, WebRTC signalling socket and mDNS advertisement.
 * This service is started in the foreground so leaving or recreating MainActivity
 * does not tear down the receiver.
 */
class MirrorReceiverService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): MirrorReceiverService = this@MirrorReceiverService
    }

    private val binder = LocalBinder()
    private var httpServer: LocalReceiverServer? = null
    private var signalServer: SignalServer? = null
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    val pairingPin: String get() = signalServer?.pairingPin ?: ""
    val httpPort: Int get() = httpServer?.listeningPort ?: HTTP_PORT
    fun localAddress(): String = httpServer?.localAddress() ?: "Starting receiver…"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Atomos Mirror receiver is active")
            .setContentText("Local Wi-Fi receiver and device discovery are running.")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startInfrastructure()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopInfrastructure()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (httpServer == null || signalServer == null) startInfrastructure()
        return START_STICKY
    }

    private fun startInfrastructure() {
        if (httpServer != null && signalServer != null) return
        try {
            val signalling = SignalServer(SIGNAL_PORT)
            signalling.start()
            signalServer = signalling
            val http = LocalReceiverServer(applicationContext, HTTP_PORT, signalling.pairingPin)
            http.start()
            httpServer = http
            advertiseReceiver(signalling.pairingPin)
        } catch (error: Exception) {
            stopInfrastructure()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun advertiseReceiver(pin: String) {
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        nsdManager = manager
        val info = NsdServiceInfo().apply {
            serviceName = "Atomos-Mirror"
            serviceType = "_atomosmirror._tcp."
            port = HTTP_PORT
            setAttribute("pin", pin)
            setAttribute("signalPort", SIGNAL_PORT.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
        }
        registration = listener
        try { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) { }
    }

    private fun stopInfrastructure() {
        try { registration?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) { }
        registration = null
        nsdManager = null
        try { httpServer?.stop() } catch (_: Exception) { }
        httpServer = null
        try { signalServer?.stop() } catch (_: Exception) { }
        signalServer = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Local receiver", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopInfrastructure()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.atomos.mirror.START_RECEIVER"
        const val ACTION_STOP = "com.atomos.mirror.STOP_RECEIVER"
        const val HTTP_PORT = 8765
        const val SIGNAL_PORT = 8766
        private const val CHANNEL_ID = "atomos_receiver"
        private const val NOTIFICATION_ID = 700
    }
}
