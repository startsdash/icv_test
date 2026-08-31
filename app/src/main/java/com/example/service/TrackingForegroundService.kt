package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.manager.TrackingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service для поддержания непрерывного сбора данных с датчиков (PDR)
 * и передачи координат по Socket.io в фоновом режиме, даже когда пользователь
 * переключается в браузер или экран телефона заблокирован/выключен.
 */
class TrackingForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var notificationUpdateJob: Job? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        acquireWakeLock()
        acquireWifiLock()
        createNotificationChannel()
        startNotificationLiveUpdater()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_SERVICE) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val notification = buildNotification(
            "Уборка активна",
            "Координаты передаются на icv.dotozen.ru в реальном времени"
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "TrackingForegroundService started in foreground")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground notification: ${e.message}", e)
        }
        return START_STICKY
    }

    private fun startNotificationLiveUpdater() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            val manager = TrackingManager.getInstance(applicationContext)
            manager.uiState.collectLatest { state ->
                val steps = state.trackerState.stepCount
                val x = state.serverX
                val y = state.serverY
                val isSim = state.isSimulating
                val prefix = if (isSim) "[ТЕСТ] " else ""
                val text = "${prefix}Шагов: $steps | Сайт: (${x.toInt()}px, ${y.toInt()}px) | WSS: ${state.connectionState.displayName}"

                try {
                    val updatedNotification = buildNotification("Cleaner Track v1.0", text)
                    notificationManager?.notify(NOTIFICATION_ID, updatedNotification)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update notification: ${e.message}")
                }
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CleanerTracker::TrackingWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // до 12 часов смены
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}", e)
        } finally {
            wakeLock = null
        }
    }

    private fun acquireWifiLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockType, "CleanerTracker::WifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLock: ${e.message}", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WifiLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WifiLock: ${e.message}", e)
        } finally {
            wifiLock = null
        }
    }

    private fun stopForegroundService() {
        notificationUpdateJob?.cancel()
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "TrackingForegroundService stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Служба отслеживания уборки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Обеспечивает непрерывную передачу координат при переключении между приложениями и выключенном экране"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
        releaseWifiLock()
        Log.i(TAG, "TrackingForegroundService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TrackingService"
        const val CHANNEL_ID = "cleaner_tracker_tracking_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVICE = "com.example.service.START_TRACKING"
        const val ACTION_STOP_SERVICE = "com.example.service.STOP_TRACKING"

        fun start(context: Context) {
            val intent = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ForegroundService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop ForegroundService: ${e.message}", e)
            }
        }
    }
}
