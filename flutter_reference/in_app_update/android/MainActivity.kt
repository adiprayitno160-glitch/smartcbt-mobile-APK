package com.school.smartcbt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.NonNull
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * MainActivity dengan Native MethodChannel untuk instalasi APK mandiri.
 * Menangani Intent ACTION_VIEW / ACTION_INSTALL_PACKAGE secara native
 * dengan FileProvider content URI, FLAG_GRANT_READ_URI_PERMISSION,
 * dan pengecekan canRequestPackageInstalls() pada Android 8.0+ (API 26+).
 */
class MainActivity: FlutterActivity() {
    private val INSTALLER_CHANNEL = "com.school.smartcbt/app_installer"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, INSTALLER_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "canRequestPackageInstalls" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val canInstall = packageManager.canRequestPackageInstalls()
                        result.success(canInstall)
                    } else {
                        result.success(true)
                    }
                }
                "openInstallPermissionSetting" -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:$packageName")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    } catch (e: Exception) {
                        result.error("ERROR_OPEN_SETTINGS", e.localizedMessage, null)
                    }
                }
                "installApkNative" -> {
                    val filePath = call.argument<String>("filePath")
                    if (filePath.isNullOrEmpty()) {
                        result.error("INVALID_PATH", "File path tidak boleh kosong", null)
                        return@setMethodCallHandler
                    }

                    val apkFile = File(filePath)
                    if (!apkFile.exists()) {
                        result.error("FILE_NOT_FOUND", "Berkas APK tidak ditemukan di: $filePath", null)
                        return@setMethodCallHandler
                    }

                    try {
                        installApk(this, apkFile)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("INSTALL_FAILED", e.localizedMessage, null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun installApk(context: Context, file: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, authority, file)
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
    }
}
