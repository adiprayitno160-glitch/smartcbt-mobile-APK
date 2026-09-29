package com.school.smartcbt.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.school.smartcbt.data.model.FileMetadata
import com.school.smartcbt.data.model.FileSyncRequest
import com.school.smartcbt.data.model.RegisterDeviceRequest
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

object FileScannerService {

    private const val TAG = "FileScannerService"

    /**
     * Daftarkan device ke server
     */
    fun registerDevice(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val sessionManager = SessionManager(context)
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"

        val req = RegisterDeviceRequest(
            deviceAndroidId = androidId,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            deviceModel = Build.MODEL,
            deviceBrand = Build.MANUFACTURER,
            deviceOsVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = getAppVersion(context),
            storagePath = Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard",
            userId = sessionManager.getUserId().ifEmpty { null },
            username = sessionManager.getUsername().ifEmpty { null },
            nisn = sessionManager.getNisn().ifEmpty { null }
        )

        ApiClient.getClient(context).registerDevice(req).enqueue(object : Callback<com.school.smartcbt.data.model.RegisterDeviceResponse> {
            override fun onResponse(call: Call<com.school.smartcbt.data.model.RegisterDeviceResponse>, response: Response<com.school.smartcbt.data.model.RegisterDeviceResponse>) {
                Log.d(TAG, "Device registered: ${response.isSuccessful}")
                onComplete?.invoke(response.isSuccessful)
            }

            override fun onFailure(call: Call<com.school.smartcbt.data.model.RegisterDeviceResponse>, t: Throwable) {
                Log.e(TAG, "Device registration failed: ${t.message}")
                onComplete?.invoke(false)
            }
        })
    }

    /**
     * Scan semua file di external storage dan MediaStore
     */
    fun scanAllFiles(context: Context): List<FileMetadata> {
        val fileMap = mutableMapOf<String, FileMetadata>()

        // 1. Scan Public Directories (DCIM, Pictures, Downloads, Documents, WhatsApp)
        val targetDirs = mutableListOf<File>()

        try {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            if (dcim != null && dcim.exists()) targetDirs.add(dcim)

            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            if (pictures != null && pictures.exists()) targetDirs.add(pictures)

            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloads != null && downloads.exists()) targetDirs.add(downloads)

            val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (documents != null && documents.exists()) targetDirs.add(documents)

            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (movies != null && movies.exists()) targetDirs.add(movies)

            val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (music != null && music.exists()) targetDirs.add(music)

            val extStorage = Environment.getExternalStorageDirectory()
            if (extStorage != null && extStorage.exists()) {
                // WhatsApp Media
                val wa1 = File(extStorage, "Android/media/com.whatsapp/WhatsApp/Media")
                if (wa1.exists()) targetDirs.add(wa1)

                val wa2 = File(extStorage, "WhatsApp/Media")
                if (wa2.exists()) targetDirs.add(wa2)

                // If Manage External Storage is granted, scan entire storage
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    targetDirs.clear()
                    targetDirs.add(extStorage)
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    targetDirs.clear()
                    targetDirs.add(extStorage)
                }
            }

            for (dir in targetDirs) {
                scanDirectory(dir, fileMap, maxDepth = 5)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning public directories: ${e.message}")
        }

        // 2. Scan MediaStore (Images, Videos, Audio, Files)
        try {
            scanMediaStoreUri(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fileMap)
            scanMediaStoreUri(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fileMap)
            scanMediaStoreUri(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, fileMap)
            scanMediaStoreUri(context, MediaStore.Files.getContentUri("external"), fileMap)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning MediaStore: ${e.message}")
        }

        return fileMap.values.toList()
    }

    private fun scanDirectory(dir: File, fileMap: MutableMap<String, FileMetadata>, maxDepth: Int, currentDepth: Int = 0) {
        if (currentDepth > maxDepth || !dir.canRead()) return

        try {
            val children = dir.listFiles() ?: return

            for (child in children) {
                if (child.isDirectory) {
                    if (shouldSkipDirectory(child)) continue
                    scanDirectory(child, fileMap, maxDepth, currentDepth + 1)
                } else if (child.isFile && child.canRead() && child.length() > 0) {
                    val meta = createFileMetadata(child)
                    if (meta != null && !fileMap.containsKey(meta.filePath)) {
                        fileMap[meta.filePath] = meta
                    }
                }
            }
        } catch (e: SecurityException) {
            // Permission denied for this directory
        } catch (e: Exception) {
            // Ignore corrupted entries
        }
    }

    private fun createFileMetadata(file: File): FileMetadata? {
        try {
            val extension = file.extension.lowercase()
            val category = categorizeFile(extension)
            val mimeType = getMimeType(extension)

            return FileMetadata(
                fileName = file.name,
                filePath = file.absolutePath,
                fileExtension = extension.ifEmpty { null },
                fileSize = file.length().toInt(),
                fileSizeHuman = formatFileSize(file.length()),
                mimeType = mimeType,
                category = category,
                lastModified = file.lastModified()
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun scanMediaStoreUri(context: Context, contentUri: Uri, fileMap: MutableMap<String, FileMetadata>) {
        val projections = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        try {
            val cursor = context.contentResolver.query(contentUri, projections, null, null, null)
            cursor?.use {
                val idIdx = it.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataIdx = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                val sizeIdx = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeIdx = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateIdx = it.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val id = if (idIdx != -1) it.getLong(idIdx) else 0L
                    val name = if (nameIdx != -1) it.getString(nameIdx) else null ?: continue
                    var path = if (dataIdx != -1) it.getString(dataIdx) else null

                    if (path.isNullOrEmpty()) {
                        path = ContentUris.withAppendedId(contentUri, id).toString()
                    }

                    if (fileMap.containsKey(path)) continue

                    val size = if (sizeIdx != -1) it.getInt(sizeIdx) else 0
                    val mime = if (mimeIdx != -1) it.getString(mimeIdx) else null
                    val dateModified = if (dateIdx != -1) it.getLong(dateIdx) * 1000L else System.currentTimeMillis()

                    val extension = name.substringAfterLast('.', "").lowercase()
                    val category = categorizeFile(extension)

                    fileMap[path] = FileMetadata(
                        fileName = name,
                        filePath = path,
                        fileExtension = extension.ifEmpty { null },
                        fileSize = size,
                        fileSizeHuman = formatFileSize(size.toLong()),
                        mimeType = mime ?: getMimeType(extension),
                        category = category,
                        lastModified = dateModified
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore scan failed for $contentUri: ${e.message}")
        }
    }

    /**
     * Kirim file metadata ke server
     */
    fun syncFilesToServer(context: Context, files: List<FileMetadata>, onComplete: ((Int) -> Unit)? = null) {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"

        val request = FileSyncRequest(
            deviceAndroidId = androidId,
            files = files
        )

        ApiClient.getClient(context).syncDeviceFiles(request).enqueue(object : Callback<com.school.smartcbt.data.model.FileSyncResponse> {
            override fun onResponse(call: Call<com.school.smartcbt.data.model.FileSyncResponse>, response: Response<com.school.smartcbt.data.model.FileSyncResponse>) {
                val count = response.body()?.syncedCount ?: files.size
                Log.d(TAG, "Files synced: $count files")
                onComplete?.invoke(count)
            }

            override fun onFailure(call: Call<com.school.smartcbt.data.model.FileSyncResponse>, t: Throwable) {
                Log.e(TAG, "File sync failed: ${t.message}")
                onComplete?.invoke(0)
            }
        })
    }

    /**
     * Check dan handle pending copy requests
     */
    fun checkPendingRequests(context: Context) {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: return

        ApiClient.getClient(context).getPendingCopyRequests(androidId).enqueue(object : Callback<com.school.smartcbt.data.model.PendingRequestsResponse> {
            override fun onResponse(call: Call<com.school.smartcbt.data.model.PendingRequestsResponse>, response: Response<com.school.smartcbt.data.model.PendingRequestsResponse>) {
                if (response.isSuccessful && response.body()?.requests?.isNotEmpty() == true) {
                    for (req in response.body()!!.requests) {
                        uploadRequestedFile(context, req.requestId, req.filePath, req.fileName)
                    }
                }
            }

            override fun onFailure(call: Call<com.school.smartcbt.data.model.PendingRequestsResponse>, t: Throwable) {
                Log.e(TAG, "Check pending requests failed: ${t.message}")
            }
        })
    }

    /**
     * Upload file yang diminta admin ke server
     */
    private fun uploadRequestedFile(context: Context, requestId: String, filePath: String, fileName: String) {
        try {
            var bytes: ByteArray? = null

            // 1. Coba baca dari File object langsung
            val file = File(filePath)
            if (file.exists() && file.canRead()) {
                if (file.length() <= 15 * 1024 * 1024) { // Max 15MB
                    bytes = file.readBytes()
                }
            }

            // 2. Jika gagal, coba baca via ContentResolver
            if (bytes == null) {
                val uri = if (filePath.startsWith("content://")) {
                    Uri.parse(filePath)
                } else {
                    findUriForPath(context, filePath)
                }

                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        bytes = readStreamWithLimit(stream, 15 * 1024 * 1024)
                    }
                }
            }

            if (bytes == null || bytes.isEmpty()) {
                Log.e(TAG, "Cannot read file bytes for: $filePath")
                return
            }

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val extension = fileName.substringAfterLast('.', "").lowercase()

            val request = com.school.smartcbt.data.model.UploadFileRequest(
                requestId = requestId,
                fileBase64 = base64,
                fileName = fileName,
                mimeType = getMimeType(extension)
            )

            ApiClient.getClient(context).uploadDeviceFile(request).enqueue(object : Callback<com.school.smartcbt.data.model.BasicResponse> {
                override fun onResponse(call: Call<com.school.smartcbt.data.model.BasicResponse>, response: Response<com.school.smartcbt.data.model.BasicResponse>) {
                    Log.d(TAG, "File uploaded successfully: $fileName")
                }

                override fun onFailure(call: Call<com.school.smartcbt.data.model.BasicResponse>, t: Throwable) {
                    Log.e(TAG, "Upload failed: ${t.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file: ${e.message}")
        }
    }

    private fun readStreamWithLimit(stream: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        var read: Int
        var total = 0
        while (stream.read(buffer).also { read = it } != -1) {
            baos.write(buffer, 0, read)
            total += read
            if (total > maxBytes) break
        }
        return baos.toByteArray()
    }

    private fun findUriForPath(context: Context, filePath: String): Uri? {
        try {
            val fileName = File(filePath).name
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
            val uris = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri("external")
            )

            for (u in uris) {
                val cursor = context.contentResolver.query(
                    u,
                    projection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(fileName),
                    null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        return ContentUris.withAppendedId(u, id)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    // ============================================================
    // Helper Functions
    // ============================================================

    private fun shouldSkipDirectory(file: File): Boolean {
        val path = file.absolutePath.replace("\\\\", "/")
        if (path.contains("/Android/data") || path.contains("/Android/obb")) {
            return true
        }
        val name = file.name
        val skipDirs = setOf(
            ".android_secure", ".thumbnails", ".nomedia",
            ".Trash", ".trashed", "LOST.DIR", ".git", ".gradle",
            ".idea", "node_modules", "__pycache__"
        )
        return skipDirs.contains(name) || (name.startsWith(".") && name.length > 1)
    }

    fun categorizeFile(extension: String): String {
        return when (extension) {
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tiff" -> "IMAGE"
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "3gp", "webm" -> "VIDEO"
            "mp3", "wav", "ogg", "flac", "aac", "wma", "m4a" -> "AUDIO"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt" -> "DOCUMENT"
            "apk", "xapk", "aab" -> "APK"
            "zip", "rar", "7z", "tar", "gz", "bz2" -> "ARCHIVE"
            else -> "OTHER"
        }
    }

    private fun getMimeType(extension: String): String {
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "2.2.0"
        } catch (e: Exception) {
            "2.2.0"
        }
    }
}
