package com.school.smartcbt.utils

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.school.smartcbt.data.model.AppVersionConfig
import com.school.smartcbt.data.remote.ApiClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AppUpdateChecker {

    private var isUpdateDialogShowing = false
    var pendingApkFile: File? = null
    var pendingFallbackUrl: String = ""

    fun resumePendingInstallIfAny(context: Context) {
        val file = pendingApkFile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (context.packageManager.canRequestPackageInstalls()) {
                val fallback = pendingFallbackUrl
                pendingApkFile = null
                pendingFallbackUrl = ""
                launchPackageInstaller(context, file, fallback)
            }
        }
    }

    fun checkForUpdates(context: Context, showToastIfLatest: Boolean = false) {
        if (showToastIfLatest) {
            Toast.makeText(context, "🔍 Memeriksa pembaruan sistem...", Toast.LENGTH_SHORT).show()
        }

        Thread {
            try {
                var currentVersionCode = 0
                var currentVersionName = ""
                try {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo.versionCode
                    }
                    currentVersionName = pInfo.versionName ?: ""
                } catch (e: Exception) {
                    currentVersionCode = com.school.smartcbt.BuildConfig.VERSION_CODE
                    currentVersionName = com.school.smartcbt.BuildConfig.VERSION_NAME
                }

                if (currentVersionCode <= 0) currentVersionCode = com.school.smartcbt.BuildConfig.VERSION_CODE
                if (currentVersionName.isBlank()) currentVersionName = com.school.smartcbt.BuildConfig.VERSION_NAME

                val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()

                val activeBaseUrl = ApiClient.getBaseServerUrl(context).trimEnd('/')
                val vpsBaseUrl = SessionManager.ONLINE_SERVER_URL.trimEnd('/')

                var targetConfig: AppVersionConfig? = null
                var sourceBaseUrl = activeBaseUrl

                // 1. Periksa server aktif yang sedang terhubung ke aplikasi (Cepat, < 100ms di lokal)
                try {
                    val activeReq = Request.Builder()
                        .url("$activeBaseUrl/api/app/version-check")
                        .header("Cache-Control", "no-cache")
                        .build()
                    val activeResp = okHttpClient.newCall(activeReq).execute()
                    if (activeResp.isSuccessful) {
                        val body = activeResp.body()?.string()
                        if (!body.isNullOrEmpty()) {
                            targetConfig = parseVersionConfig(body)
                        }
                    }
                    activeResp.close()
                } catch (e: Exception) {}

                // Jika server aktif sudah menyediakan versi yang lebih baru, LANGSUNG PROSES ke modal popup!
                val hasActiveUpdate = targetConfig != null && (
                    targetConfig.versionCode > currentVersionCode ||
                    (targetConfig.versionCode == currentVersionCode && isVersionNameNewer(targetConfig.versionName, currentVersionName))
                )

                if (hasActiveUpdate) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        showCenteredUpdateModal(context, targetConfig!!, currentVersionName, activeBaseUrl)
                    }
                    return@Thread
                }

                // 2. Jika server aktif belum ada update dan bukan VPS resmi, coba periksa VPS resmi
                val isSameAsVps = activeBaseUrl.contains("cbt.smpn1boyolangu.my.id")
                if (!isSameAsVps) {
                    try {
                        val vpsReq = Request.Builder()
                            .url("$vpsBaseUrl/api/app/version-check")
                            .header("Cache-Control", "no-cache")
                            .build()
                        val vpsResp = okHttpClient.newCall(vpsReq).execute()
                        if (vpsResp.isSuccessful) {
                            val body = vpsResp.body()?.string()
                            if (!body.isNullOrEmpty()) {
                                val vpsConfig = parseVersionConfig(body)
                                if (vpsConfig != null && (
                                    vpsConfig.versionCode > currentVersionCode ||
                                    (vpsConfig.versionCode == currentVersionCode && isVersionNameNewer(vpsConfig.versionName, currentVersionName))
                                )) {
                                    targetConfig = vpsConfig
                                    sourceBaseUrl = vpsBaseUrl
                                }
                            }
                        }
                        vpsResp.close()
                    } catch (e: Exception) {}
                }

                // 3. Tampilkan dialog di tengah jika ada versi baru
                val hasNewVersion = targetConfig != null && (
                    targetConfig.versionCode > currentVersionCode ||
                    (targetConfig.versionCode == currentVersionCode && isVersionNameNewer(targetConfig.versionName, currentVersionName))
                )

                (context as? android.app.Activity)?.runOnUiThread {
                    if (hasNewVersion && targetConfig != null) {
                        showCenteredUpdateModal(context, targetConfig, currentVersionName, sourceBaseUrl)
                    } else {
                        // Bersihkan notifikasi status bar jika ada
                        try {
                            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                            notificationManager?.cancel(8888)
                        } catch (e: Exception) {}

                        if (showToastIfLatest) {
                            Toast.makeText(context, "✅ Aplikasi sudah versi terbaru (v$currentVersionName)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun parseVersionConfig(jsonStr: String): AppVersionConfig? {
        return try {
            val json = org.json.JSONObject(jsonStr)
            val updateObj = json.optJSONObject("update") ?: return null
            AppVersionConfig(
                versionCode = updateObj.optInt("versionCode", 0),
                versionName = updateObj.optString("versionName", ""),
                downloadUrl = updateObj.optString("downloadUrl", "/uploads/smartcbt-latest.apk"),
                fileSizeMb = updateObj.optDouble("fileSizeMb", 27.24),
                releaseNotes = updateObj.optString("releaseNotes", ""),
                isForceUpdate = updateObj.optBoolean("isForceUpdate", true)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun isVersionNameNewer(remoteVer: String, currentVer: String): Boolean {
        if (remoteVer.isBlank() || currentVer.isBlank()) return false
        val r = remoteVer.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val c = currentVer.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until minOf(r.size, c.size)) {
            if (r[i] > c[i]) return true
            if (r[i] < c[i]) return false
        }
        return r.size > c.size
    }

    private fun showCenteredUpdateModal(
        context: Context,
        update: AppVersionConfig,
        currentVerName: String,
        sourceBaseUrl: String
    ) {
        val activity = context as? android.app.Activity
        if (activity != null) {
            if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
                return
            }
        }

        if (isUpdateDialogShowing) return
        isUpdateDialogShowing = true

        try {
            val density = context.resources.displayMetrics.density
            val currentVer = if (currentVerName.isNotBlank()) "v$currentVerName" else "Versi Lama"
            val newVer = "v${update.versionName}"

            // Outer wrapper CardView
            val cardView = androidx.cardview.widget.CardView(context).apply {
                radius = 20 * density
                cardElevation = 12 * density
                setCardBackgroundColor(android.graphics.Color.WHITE)
                useCompatPadding = true
            }

            val scroll = android.widget.ScrollView(context)
            val root = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                val padH = (22 * density).toInt()
                val padV = (24 * density).toInt()
                setPadding(padH, padV, padH, padV)
                setBackgroundColor(android.graphics.Color.WHITE)
            }
            scroll.addView(root)
            cardView.addView(scroll)

            // Center Rocket Icon
            val iconBadge = android.widget.TextView(context).apply {
                text = "🚀"
                textSize = 38f
                gravity = android.view.Gravity.CENTER
            }
            root.addView(iconBadge)

            val tvTitle = android.widget.TextView(context).apply {
                text = "Pembaruan Tersedia!"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#0F172A"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            root.addView(tvTitle)

            val tvSub = android.widget.TextView(context).apply {
                text = "Smart School • Versi $newVer Siap Dipasang"
                textSize = 11.5f
                setTextColor(android.graphics.Color.parseColor("#64748B"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, (2 * density).toInt(), 0, (14 * density).toInt())
            }
            root.addView(tvSub)

            // Version comparison pills
            val rowVersion = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }

            val tvOld = android.widget.TextView(context).apply {
                text = currentVer
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#64748B"))
                setBackgroundColor(android.graphics.Color.parseColor("#F1F5F9"))
                val h = (10 * density).toInt()
                val v = (5 * density).toInt()
                setPadding(h, v, h, v)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            rowVersion.addView(tvOld)

            val tvArrow = android.widget.TextView(context).apply {
                text = "  ➔  "
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#0284C7"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            rowVersion.addView(tvArrow)

            val tvNew = android.widget.TextView(context).apply {
                text = "$newVer (Build ${update.versionCode})"
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#15803D"))
                setBackgroundColor(android.graphics.Color.parseColor("#DCFCE7"))
                val h = (10 * density).toInt()
                val v = (5 * density).toInt()
                setPadding(h, v, h, v)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            rowVersion.addView(tvNew)
            root.addView(rowVersion)

            // Release Notes Box
            val cardNotes = androidx.cardview.widget.CardView(context).apply {
                radius = 12 * density
                cardElevation = 0f
                setCardBackgroundColor(android.graphics.Color.parseColor("#F8FAFC"))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (14 * density).toInt()
                    bottomMargin = (18 * density).toInt()
                }
                layoutParams = lp
            }

            val innerNotes = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val p = (12 * density).toInt()
                setPadding(p, p, p, p)
            }

            val tvNotesH = android.widget.TextView(context).apply {
                text = "📋 Fitur & Pembaruan (${update.fileSizeMb} MB):"
                textSize = 11.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#1E293B"))
            }
            innerNotes.addView(tvNotesH)

            val tvNotesContent = android.widget.TextView(context).apply {
                text = update.releaseNotes?.trim()?.ifEmpty { "Peningkatan sistem, fitur baru, dan stabilitas performa." }
                    ?: "Peningkatan sistem, fitur baru, dan stabilitas performa."
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#475569"))
                setPadding(0, (4 * density).toInt(), 0, 0)
                setLineSpacing(3f, 1f)
            }
            innerNotes.addView(tvNotesContent)
            cardNotes.addView(innerNotes)
            root.addView(cardNotes)

            var dialog: AlertDialog? = null

            // Primary Action Button: PERBARUI SEKARANG
            val btnUpdate = android.widget.Button(context).apply {
                text = "⚡ PERBARUI SEKARANG"
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (48 * density).toInt()
                )
                layoutParams = lp
                setOnClickListener {
                    dialog?.dismiss()
                    startDirectDownloadAndInstall(context, update, sourceBaseUrl)
                }
            }
            root.addView(btnUpdate)

            // Secondary: Batal / Nanti (hanya jika bukan force update)
            if (!update.isForceUpdate) {
                val btnLater = android.widget.Button(context).apply {
                    text = "Nanti Saja"
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#64748B"))
                    setBackgroundColor(android.graphics.Color.parseColor("#F1F5F9"))
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (42 * density).toInt()
                    ).apply {
                        topMargin = (8 * density).toInt()
                    }
                    layoutParams = lp
                    setOnClickListener {
                        dialog?.dismiss()
                    }
                }
                root.addView(btnLater)
            }

            val tvBrowser = android.widget.TextView(context).apply {
                text = "🌐 Atau Unduh Langsung via Browser"
                textSize = 11.5f
                setTextColor(android.graphics.Color.parseColor("#2563EB"))
                gravity = android.view.Gravity.CENTER
                val p = (12 * density).toInt()
                setPadding(0, p, 0, (4 * density).toInt())
                setOnClickListener {
                    dialog?.dismiss()
                    val downloadUrl = resolveDownloadUrl(context, update.downloadUrl, sourceBaseUrl)
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                }
            }
            root.addView(tvBrowser)

            dialog = AlertDialog.Builder(context)
                .setView(cardView)
                .setCancelable(!update.isForceUpdate)
                .create()

            dialog.setOnDismissListener {
                isUpdateDialogShowing = false
            }

            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.setGravity(android.view.Gravity.CENTER)
            dialog.show()

        } catch (e: Exception) {
            e.printStackTrace()
            isUpdateDialogShowing = false
        }
    }

    private fun startDirectDownloadAndInstall(
        context: Context,
        update: AppVersionConfig,
        preferredBaseUrl: String
    ) {
        val activity = context as? android.app.Activity

        // Dialog Progress Unduhan di tengah layar
        val progressDialog = ProgressDialog(context).apply {
            setTitle("⚡ Mengunduh Smart CBT v${update.versionName}")
            setMessage("Sedang mengunduh berkas pembaruan...\nMohon jangan tutup aplikasi.")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            progress = 0
            setCancelable(false)
        }

        try {
            if (activity != null && !activity.isFinishing && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed)) {
                progressDialog.show()
            }
        } catch (e: Exception) {}

        Thread {
            try {
                val downloadUrl = resolveDownloadUrl(context, update.downloadUrl, preferredBaseUrl)
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()

                val targetUrls = mutableListOf(downloadUrl)
                if (!downloadUrl.contains("cbt.smpn1boyolangu.my.id")) {
                    targetUrls.add("https://cbt.smpn1boyolangu.my.id/uploads/smartcbt-latest.apk")
                }

                var response: okhttp3.Response? = null
                for (url in targetUrls) {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .header("Cache-Control", "no-cache, no-store, must-revalidate")
                            .header("Pragma", "no-cache")
                            .build()
                        val res = client.newCall(req).execute()
                        if (res.isSuccessful && res.body() != null) {
                            response = res
                            break
                        }
                    } catch (e: Exception) {}
                }

                if (response == null || !response.isSuccessful || response.body() == null) {
                    throw Exception("Tidak dapat mengunduh berkas APK dari server.")
                }

                val responseBody = response.body()!!
                val contentLength = responseBody.contentLength()

                val targetDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                val apkFile = File(targetDir, "SmartSchool_CBT_v${update.versionCode}.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val inputStream = responseBody.byteStream()
                val outputStream = FileOutputStream(apkFile)
                val buffer = ByteArray(8192)
                var totalBytesRead = 0L
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    totalBytesRead += read
                    if (contentLength > 0) {
                        val percent = ((totalBytesRead * 100) / contentLength).toInt()
                        activity?.runOnUiThread {
                            try {
                                if (progressDialog.isShowing) {
                                    progressDialog.progress = percent
                                    val readMb = String.format("%.1f", totalBytesRead / (1024.0 * 1024.0))
                                    val totalMb = String.format("%.1f", contentLength / (1024.0 * 1024.0))
                                    progressDialog.setMessage("Mengunduh: $readMb MB / $totalMb MB ($percent%)")
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
                apkFile.setReadable(true, false)

                activity?.runOnUiThread {
                    try {
                        if (progressDialog.isShowing) {
                            progressDialog.dismiss()
                        }
                    } catch (e: Exception) {}

                    // Periksa izin sumber tidak dikenal untuk Android 8+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                        pendingApkFile = apkFile
                        pendingFallbackUrl = downloadUrl
                        AlertDialog.Builder(context)
                            .setTitle("⚙️ Izin Pemasangan Diperlukan")
                            .setMessage("Pembaruan Smart CBT v${update.versionName} telah berhasil diunduh. Harap aktifkan izin 'Izinkan dari sumber ini' pada pengaturan, lalu ketuk tombol kembali untuk memasang pembaruan.")
                            .setPositiveButton("Buka Pengaturan") { _, _ ->
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    launchPackageInstaller(context, apkFile, downloadUrl)
                                }
                            }
                            .setNegativeButton("Lanjutkan Pasang") { _, _ ->
                                launchPackageInstaller(context, apkFile, downloadUrl)
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        launchPackageInstaller(context, apkFile, downloadUrl)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    try {
                        if (progressDialog.isShowing) {
                            progressDialog.dismiss()
                        }
                    } catch (ex: Exception) {}
                    Toast.makeText(context, "Gagal mengunduh pembaruan: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchPackageInstaller(context: Context, apkFile: File, fallbackUrl: String = "") {
        try {
            if (!apkFile.exists() || apkFile.length() < 10 * 1024 * 1024) {
                Toast.makeText(context, "Berkas installer tidak lengkap atau rusak (${apkFile.length() / (1024 * 1024)} MB). Harap unduh ulang.", Toast.LENGTH_LONG).show()
                return
            }
            pendingApkFile = null
            pendingFallbackUrl = ""

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            // Explicitly grant permissions to known Android & OEM Package Installers
            val installerPackages = listOf(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller",
                "com.miui.packageinstaller",
                "com.samsung.android.packageinstaller",
                "com.coloros.packageinstaller",
                "com.vivo.packageinstaller"
            )
            for (pkg in installerPackages) {
                try {
                    context.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {}
            }

            try {
                val resInfoList = context.packageManager.queryIntentActivities(intent, 0)
                for (resolveInfo in resInfoList) {
                    val packageName = resolveInfo.activityInfo.packageName
                    context.grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: Exception) {}

            Toast.makeText(context, "Membuka instalasi... Ketuk 'Update', lalu ketuk 'Buka'.", Toast.LENGTH_LONG).show()
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Gagal membuka penginstal otomatis, membuka browser...", Toast.LENGTH_LONG).show()
            if (fallbackUrl.isNotBlank()) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                } catch (ex: Exception) {}
            }
        }
    }

    private fun resolveDownloadUrl(context: Context, rawUrl: String, preferredBaseUrl: String = ""): String {
        val baseUrl = if (preferredBaseUrl.isNotBlank()) preferredBaseUrl else ApiClient.getBaseServerUrl(context)
        if (rawUrl.startsWith("/")) {
            return "$baseUrl$rawUrl"
        }
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            val uri = Uri.parse(rawUrl)
            val host = uri.host ?: ""
            if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") {
                val pathAndQuery = (uri.path ?: "") + if (uri.query != null) "?${uri.query}" else ""
                return "$baseUrl$pathAndQuery"
            }
            return rawUrl
        }
        return "$baseUrl/$rawUrl"
    }
}
