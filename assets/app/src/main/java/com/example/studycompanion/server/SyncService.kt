package com.example.studycompanion.server

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.studycompanion.App
import com.example.studycompanion.MainActivity
import com.example.studycompanion.core.Logger
import com.example.studycompanion.core.NetworkUtils
import com.example.studycompanion.data.SettingsStore
import com.example.studycompanion.domain.LiveSessionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SyncService : Service() {

    @Inject
    lateinit var ktorServer: KtorServer

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var liveSessionState: LiveSessionState

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var wifiLock: WifiManager.WifiLock? = null
    private var updateJob: Job? = null
    private var startupJob: Job? = null
    private var activePort: Int = 8765

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNotification()
        }

        override fun onLost(network: Network) {
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StudyCompanion:WifiLock").apply {
            setReferenceCounted(false)
        }

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Logger.e("SyncService", "Error registering network callback", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> if (!startForegroundServer()) return START_NOT_STICKY
            ACTION_STOP -> { stopForegroundServer(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun startForegroundServer(): Boolean {
        if (startupJob?.isActive == true || ktorServer.isRunning()) return true
        ktorServer.lastError = null
        // Promotion must happen immediately, before DataStore or network startup.
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            ktorServer.lastError = "起動通知を開始できません: ${e.message ?: e.javaClass.simpleName}"
            Logger.e("SyncService", "Foreground promotion failed", e)
            stopSelf()
            return false
        }
        startupJob = serviceScope.launch {
            try {
                activePort = settingsStore.portFlow.first()
                val success = withContext(Dispatchers.IO) { ktorServer.startServer(activePort) }
                if (!success) { stopForegroundServer(); return@launch }
                // Wi-Fi retention is optional; a device denying it must not kill the app.
                try { if (wifiLock?.isHeld == false) wifiLock?.acquire() }
                catch (e: SecurityException) { Logger.w("SyncService", "Wi-Fi lock unavailable", e) }
                startNotificationLoop()
            } catch (e: CancellationException) {
                // If stopped while CIO was starting, close the just-created server too.
                withContext(NonCancellable + Dispatchers.IO) { ktorServer.stopServer() }
                throw e
            } catch (e: Exception) {
                ktorServer.lastError = "サーバー起動に失敗しました: ${e.message ?: e.javaClass.simpleName}"
                Logger.e("SyncService", "Server startup failed", e)
                stopForegroundServer()
            }
        }
        return true
    }

    private fun stopForegroundServer() {
        startupJob?.cancel()
        updateJob?.cancel()
        ktorServer.stopServer()
        if (wifiLock?.isHeld == true) {
            releaseWifiLock()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startNotificationLoop() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive) {
                updateNotification()
                delay(10000)
            }
        }
    }

    private fun updateNotification() {
        if (!ktorServer.isRunning()) return
        val notification = buildNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { notificationManager.notify(NOTIFICATION_ID, notification) }
        catch (e: SecurityException) { Logger.w("SyncService", "Notification unavailable", e) }
    }

    private fun buildNotification(): android.app.Notification {
        val ips = NetworkUtils.getLocalIpAddresses()
        val ipText = if (ips.isNotEmpty()) {
            ips.joinToString(", ") { "$it:$activePort" }
        } else {
            "テザリングはIP自動 / ポート $activePort"
        }

        val live = liveSessionState.getValidLive()
        val title: String
        val content: String

        if (live != null) {
            val elapsedSec = (System.currentTimeMillis() / 1000.0 - live.start).toInt().coerceAtLeast(0)
            val elapsedMin = elapsedSec / 60
            title = "学習中: ${live.subject}"
            content = "経過時間: ${elapsedMin}分 ($ipText)"
        } else {
            title = "Study Companion 待機中"
            content = ipText
        }

        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.SYNC_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        updateJob?.cancel()
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // ignore
        }
        if (wifiLock?.isHeld == true) {
            releaseWifiLock()
        }
        ktorServer.stopServer()
        super.onDestroy()
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() }
        catch (e: RuntimeException) { Logger.w("SyncService", "Wi-Fi lock release failed", e) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.studycompanion.action.START_SERVICE"
        const val ACTION_STOP = "com.example.studycompanion.action.STOP_SERVICE"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
