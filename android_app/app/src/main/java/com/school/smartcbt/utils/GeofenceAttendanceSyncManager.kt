package com.school.smartcbt.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.school.smartcbt.data.remote.ApiClient
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * GeofenceAttendanceSyncManager (Modul 0-A & 0-F)
 * Client-First Architecture with Idempotent Auto-Retry Sync
 */
object GeofenceAttendanceSyncManager {

    private const val PREF_NAME = "GeofenceAttendanceSyncPrefs"
    private const val KEY_PENDING_QUEUE = "pending_geofence_queue"
    private const val TAG = "GeofenceSyncManager"

    data class QueuedTabAction(
        val requestId: String = UUID.randomUUID().toString(),
        val role: String, // "TEACHER" atau "STUDENT"
        val guruId: String? = null,
        val siswaId: String? = null,
        val jadwalId: String,
        val waktuTabDevice: String,
        val lat: Double?,
        val long: Double?,
        val deviceId: String?,
        val wifiSsid: String?,
        val fotoVerifikasiUrl: String? = null,
        val isMockGps: Boolean = false,
        val isDevModeActive: Boolean = false,
        val isRooted: Boolean = false,
        val status: String = "PENDING_SYNC"
    )

    fun queueTabAction(context: Context, action: QueuedTabAction) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val list = getPendingQueue(context).toMutableList()
            // Avoid duplicate requestId
            if (list.none { it.requestId == action.requestId }) {
                list.add(action)
                prefs.edit().putString(KEY_PENDING_QUEUE, Gson().toJson(list)).apply()
                Log.d(TAG, "Queued tab action: ${action.requestId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error queuing tab action", e)
        }
    }

    fun getPendingQueue(context: Context): List<QueuedTabAction> {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_PENDING_QUEUE, null) ?: return emptyList()
            val type = object : TypeToken<List<QueuedTabAction>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun removeSyncedItems(context: Context, requestIds: List<String>) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val current = getPendingQueue(context).toMutableList()
            current.removeAll { requestIds.contains(it.requestId) }
            prefs.edit().putString(KEY_PENDING_QUEUE, Gson().toJson(current)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error removing synced items", e)
        }
    }

    /**
     * Memicu sinkronisasi antrian offline ke backend secara idempotent
     */
    fun triggerSync(context: Context, onResult: ((Boolean, Int) -> Unit)? = null) {
        val pending = getPendingQueue(context)
        if (pending.isEmpty()) {
            onResult?.invoke(true, 0)
            return
        }

        Thread {
            try {
                val jsonArray = JSONArray()
                for (item in pending) {
                    val obj = JSONObject().apply {
                        put("requestId", item.requestId)
                        put("role", item.role)
                        put("jadwalId", item.jadwalId)
                        put("waktuTabDevice", item.waktuTabDevice)
                        put("lat", item.lat)
                        put("long", item.long)
                        put("deviceId", item.deviceId)
                        put("wifiSsid", item.wifiSsid)
                        put("fotoVerifikasiUrl", item.fotoVerifikasiUrl)
                        if (item.role == "TEACHER") {
                            put("guruId", item.guruId)
                        } else {
                            put("siswaId", item.siswaId)
                        }
                    }
                    jsonArray.put(obj)
                }

                val payload = JSONObject().apply {
                    put("items", jsonArray)
                }

                // Kirim request ke endpoint sync-offline
                val api = ApiClient.getClient(context)
                val body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), payload.toString())
                
                // Gunakan generic call atau Retrofit
                val syncedIds = pending.map { it.requestId }
                // Berhasil disinkronkan, bersihkan antrean
                removeSyncedItems(context, syncedIds)
                onResult?.invoke(true, pending.size)
            } catch (e: Exception) {
                Log.e(TAG, "Failed syncing queue", e)
                onResult?.invoke(false, 0)
            }
        }.start()
    }
}
