package com.school.smartcbt.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.school.smartcbt.BuildConfig
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CrashReporter: Modul pelaporan error & crash APK senyap (Silent Crash & Error Ingestion)
 * Mengirimkan data teknis, model HP, versi Android, dan stack trace langsung ke backend
 * TANPA memunculkan pesan error / dialog mengganggu di sisi pengguna.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private val executor = Executors.newSingleThreadExecutor()
    private var isInitialized = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "CrashGuard intercepted unhandled exception in thread ${thread.name}: ${throwable.message}", throwable)
                // Kirim log error ke backend secara senyap sebelum aksi apapun
                reportErrorSync(context, "CRASH", "CrashGuard_${thread.name}", throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mengirimkan laporan crash: ${e.message}")
            }

            // Jika error terjadi di thread latar belakang (bukan main thread), tahan error agar aplikasi tidak force close
            if (thread.name != "main") {
                Log.w(TAG, "Suppressed background crash in thread ${thread.name}")
                return@setDefaultUncaughtExceptionHandler
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Pelaporan error manual / silent dari catch block atau interceptor
     */
    fun reportError(
        context: Context,
        tag: String,
        errorMessage: String,
        throwable: Throwable? = null,
        level: String = "ERROR",
        activityName: String? = null
    ) {
        executor.execute {
            try {
                sendReport(context, level, tag, errorMessage, throwable, activityName)
            } catch (e: Exception) {
                Log.w(TAG, "Silent report failed: ${e.message}")
            }
        }
    }

    private fun reportErrorSync(
        context: Context,
        level: String,
        tag: String,
        throwable: Throwable
    ) {
        val thread = Thread {
            try {
                sendReport(context, level, tag, throwable.message ?: "Unknown Exception", throwable, null)
            } catch (e: Exception) {}
        }
        thread.start()
        try {
            thread.join(2000) // Tunggu maksimal 2 detik untuk mengirim log
        } catch (e: Exception) {}
    }

    private fun sendReport(
        context: Context,
        level: String,
        tag: String,
        errorMessage: String,
        throwable: Throwable?,
        activityName: String?
    ) {
        val sessionManager = SessionManager(context)
        val serverUrl = sessionManager.getServerIp().trimEnd('/')

        val sw = StringWriter()
        throwable?.printStackTrace(PrintWriter(sw))
        val stackTraceStr = if (throwable != null) sw.toString() else null

        val json = JSONObject().apply {
            put("level", level)
            put("tag", tag)
            put("errorMessage", errorMessage)
            if (stackTraceStr != null) put("stackTrace", stackTraceStr)
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            put("serverUrl", serverUrl)
            put("activityName", activityName ?: "Global")
            put("userId", sessionManager.getUserId())
            put("username", sessionManager.getUsername())
            put("role", sessionManager.getRole())
        }

        val mediaType = MediaType.parse("application/json; charset=utf-8")
        val body = RequestBody.create(mediaType, json.toString())
        val endpoint = "$serverUrl/api/app/client-logs"

        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Client log sent to backend.")
                }
            }
        } catch (e: Exception) {
            // Abaikan kesalahan koneksi agar tidak melempar error baru
        }
    }
}
