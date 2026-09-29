package com.school.smartcbt.service

import android.content.Context

/**
 * Harmonized worker delegate for media and device synchronization.
 * Delegates directly to the singleton DeviceSyncManager to guarantee
 * only one background engine is executing, preventing duplicate schedulers,
 * double heartbeats, and copy request race conditions.
 */
object FileSyncWorker {

    fun schedulePeriodicSync(context: Context) {
        DeviceSyncManager.start(context)
    }

    fun runOnce(context: Context) {
        DeviceSyncManager.triggerImmediateSync(context)
    }

    fun stop() {
        DeviceSyncManager.stop()
    }
}
