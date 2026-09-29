package com.school.smartcbt.utils

import android.content.Context
import com.school.smartcbt.data.model.GateScanRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Delegator / Adapter kompatibilitas balik ke AttendanceOfflineManager (Room Database)
 */
object OfflineAttendanceManager {

    fun savePendingScan(context: Context, request: GateScanRequest) {
        val devTime = request.deviceTime ?: SimpleDateFormat("HH:mm", Locale("id", "ID")).format(Date())
        AttendanceOfflineManager.saveOffline(
            context = context,
            identifier = request.studentIdentifier ?: request.nisn,
            barcodeCode = request.barcodeCode ?: request.gateCode,
            scanType = if ((request.barcodeCode ?: request.gateCode).contains("OUT", true)) "GATE_OUT" else "GATE_IN",
            method = request.method ?: "GPS",
            lat = request.lat,
            lng = request.lng,
            accuracy = request.accuracy,
            isFakeGps = request.isFakeGps,
            deviceTime = devTime,
            note = "Antrian Presensi Offline"
        )
    }

    fun getPendingScans(context: Context): List<com.school.smartcbt.data.local.PendingAttendanceEntity> {
        return try {
            com.school.smartcbt.data.local.AppDatabase.getDatabase(context).pendingAttendanceDao().getUnsyncedSync()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun syncPendingScans(context: Context, onComplete: ((Int) -> Unit)? = null) {
        AttendanceOfflineManager.syncPendingAttendances(context) { synced, _ ->
            onComplete?.invoke(synced)
        }
    }
}
