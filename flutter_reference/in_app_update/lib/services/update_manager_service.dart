import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:open_filex/open_filex.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

import '../models/app_version_info.dart';

/// Callback untuk memantau progress unduhan APK secara real-time
typedef DownloadProgressCallback = void Function(
  int receivedBytes,
  int totalBytes,
  double percentage,
  String formattedProgress,
);

/// Layanan manajemen pembaruan in-app aplikasi mandiri (self-hosted OTA Update).
/// Menangani pengecekan versi API, izin Android 8.0-14+ (Unknown App Sources),
/// unduhan streaming dengan Dio, dan pemicu instalasi native Android.
class UpdateManagerService {
  UpdateManagerService._internal() {
    _dio = Dio(
      BaseOptions(
        connectTimeout: const Duration(seconds: 30),
        receiveTimeout: const Duration(seconds: 90),
        sendTimeout: const Duration(seconds: 30),
        headers: {
          'Accept': 'application/json',
          'User-Agent': 'SmartSchool-InAppUpdater/1.0.0 (Android)',
        },
      ),
    );
  }

  static final UpdateManagerService instance = UpdateManagerService._internal();

  late final Dio _dio;
  static const MethodChannel _nativeInstallerChannel =
      MethodChannel('com.school.smartcbt/app_installer');

  /// Mendapatkan versi aplikasi yang terinstal saat ini
  Future<String> getCurrentAppVersion() async {
    try {
      final info = await PackageInfo.fromPlatform();
      return info.version;
    } catch (e) {
      debugPrint('[UpdateManagerService] Gagal membaca PackageInfo: $e');
      return '1.0.0';
    }
  }

  /// Memanggil endpoint REST API untuk mengecek pembaruan aplikasi
  /// Format query: ?platform=android&current_version=X.Y.Z
  Future<AppVersionInfo?> checkForUpdate({
    required String endpointUrl,
    String? currentVersion,
    Map<String, dynamic>? queryParameters,
    Map<String, String>? headers,
  }) async {
    try {
      final localVersion = currentVersion ?? await getCurrentAppVersion();
      final uri = Uri.parse(endpointUrl);

      final queryParams = {
        'platform': 'android',
        'current_version': localVersion,
        if (queryParameters != null) ...queryParameters,
      };

      final response = await _dio.getUri(
        uri.replace(queryParameters: queryParams),
        options: Options(headers: headers),
      );

      if (response.statusCode == 200 && response.data != null) {
        final Map<String, dynamic> data = response.data is Map<String, dynamic>
            ? response.data as Map<String, dynamic>
            : Map<String, dynamic>.from(response.data as Map);

        return AppVersionInfo.fromJson(data);
      }
    } on DioException catch (e) {
      debugPrint('[UpdateManagerService] Dio error checking update: ${e.message}');
      rethrow;
    } catch (e) {
      debugPrint('[UpdateManagerService] Error checking update: $e');
      rethrow;
    }
    return null;
  }

  /// Mengecek apakah izin instalasi paket dari unknown source sudah aktif di Android
  Future<bool> checkInstallPermission() async {
    if (!Platform.isAndroid) return false;

    try {
      // 1. Coba melalui permission_handler
      final status = await Permission.requestInstallPackages.status;
      if (status.isGranted) return true;

      // 2. Fallback via Native MethodChannel (canRequestPackageInstalls())
      final bool? canInstall = await _nativeInstallerChannel.invokeMethod<bool>('canRequestPackageInstalls');
      return canInstall ?? false;
    } catch (e) {
      debugPrint('[UpdateManagerService] Gagal mengecek install permission: $e');
      return false;
    }
  }

  /// Meminta izin kepada pengguna untuk menginstal APK dari aplikasi ini
  /// Pada Android 8.0 (API 26) hingga Android 14 (API 34+), ini akan membuka
  /// halaman pengaturan khusus "Pasang aplikasi tidak dikenal" untuk aplikasi ini.
  Future<bool> requestInstallPermission() async {
    if (!Platform.isAndroid) return false;

    try {
      // Coba request standar
      final status = await Permission.requestInstallPackages.request();
      if (status.isGranted) return true;

      // Jika ditolak atau butuh direct intent ke pengaturan sumber tidak dikenal
      try {
        final bool? opened = await _nativeInstallerChannel.invokeMethod<bool>('openInstallPermissionSetting');
        if (opened == true) return false; // Pengguna dialihkan ke halaman setting
      } catch (_) {}

      // Fallback ke app settings umum
      await openAppSettings();
      return false;
    } catch (e) {
      debugPrint('[UpdateManagerService] Error requesting install permission: $e');
      await openAppSettings();
      return false;
    }
  }

  /// Mengunduh berkas APK dari server dengan streaming progress real-time
  Future<File> downloadApk({
    required String downloadUrl,
    required String targetFileName,
    required DownloadProgressCallback onProgress,
    CancelToken? cancelToken,
  }) async {
    try {
      // Tentukan direktori penyimpanan yang aman dan mendukung FileProvider
      Directory? baseDir;
      if (Platform.isAndroid) {
        final extCacheDirs = await getExternalCacheDirectories();
        if (extCacheDirs != null && extCacheDirs.isNotEmpty) {
          baseDir = extCacheDirs.first;
        }
      }
      baseDir ??= await getTemporaryDirectory();

      final savePath = '${baseDir.path}/$targetFileName';
      final file = File(savePath);

      // Jika file sisa sebelumnya ada, hapus agar unduhan bersih
      if (await file.exists()) {
        await file.delete();
      }

      int lastReceived = 0;
      int reportedTotal = 0;

      await _dio.download(
        downloadUrl,
        savePath,
        cancelToken: cancelToken,
        options: Options(
          responseType: ResponseType.bytes,
          followRedirects: true,
          headers: {
            'Cache-Control': 'no-cache',
          },
        ),
        onReceiveProgress: (received, total) {
          lastReceived = received;
          reportedTotal = total;

          final double percent = total > 0 ? (received / total) : 0.0;
          final String receivedMb = (received / (1024 * 1024)).toStringAsFixed(1);
          final String totalMb = total > 0 
              ? (total / (1024 * 1024)).toStringAsFixed(1) 
              : '?';

          final progressText = total > 0
              ? 'Mengunduh: ${(percent * 100).toInt()}% ($receivedMb MB / $totalMb MB)'
              : 'Mengunduh: $receivedMb MB';

          onProgress(received, total, percent, progressText);
        },
      );

      // Validasi integritas berkas setelah unduhan selesai
      if (!await file.exists()) {
        throw Exception('Berkas APK tidak ditemukan setelah proses unduh selesai.');
      }

      final fileSize = await file.length();
      if (fileSize <= 0) {
        throw Exception('Berkas APK yang diunduh berukuran 0 byte (gagal unduh).');
      }

      if (reportedTotal > 0 && fileSize != reportedTotal) {
        debugPrint('[UpdateManagerService] Peringatan: ukuran file ($fileSize) tidak cocok total ($reportedTotal)');
      }

      debugPrint('[UpdateManagerService] Unduhan APK sukses: $savePath (${(fileSize / (1024 * 1024)).toStringAsFixed(2)} MB)');
      return file;
    } on DioException catch (e) {
      if (CancelToken.isCancel(e)) {
        debugPrint('[UpdateManagerService] Pengunduhan dibatalkan oleh pengguna.');
        throw Exception('Pengunduhan dibatalkan.');
      }
      debugPrint('[UpdateManagerService] Network error during download: ${e.message}');
      throw Exception('Gagal mengunduh APK: Koneksi terputus. Pastikan internet aktif.');
    } catch (e) {
      debugPrint('[UpdateManagerService] Error downloading APK: $e');
      rethrow;
    }
  }

  /// Memicu Android Package Installer secara otomatis
  /// Menggunakan OpenFilex dengan fallback ke Native MethodChannel
  Future<bool> installApk(String filePath) async {
    final file = File(filePath);
    if (!await file.exists()) {
      debugPrint('[UpdateManagerService] Gagal instal: File tidak ditemukan di $filePath');
      return false;
    }

    try {
      debugPrint('[UpdateManagerService] Memicu instalasi via OpenFilex: $filePath');
      final result = await OpenFilex.open(
        filePath,
        type: 'application/vnd.android.package-archive',
      );

      if (result.type == ResultType.done) {
        return true;
      }

      debugPrint('[UpdateManagerService] OpenFilex result: ${result.type} - ${result.message}. Mencoba native fallback...');
    } catch (e) {
      debugPrint('[UpdateManagerService] OpenFilex error: $e. Mencoba native fallback...');
    }

    // Native Fallback via Intent ACTION_VIEW dengan FileProvider
    try {
      final bool? nativeSuccess = await _nativeInstallerChannel.invokeMethod<bool>(
        'installApkNative',
        {'filePath': filePath},
      );
      return nativeSuccess ?? false;
    } catch (nativeErr) {
      debugPrint('[UpdateManagerService] Native installer error: $nativeErr');
      return false;
    }
  }
}
