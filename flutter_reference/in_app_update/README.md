# Modul In-App Force Update & Auto-Install APK (Flutter & Android Native)

Modul ini adalah solusi lengkap siap produksi (*production-grade*) untuk mengelola pembaruan aplikasi Android mandiri (*self-hosted* / non-Google Play Store) pada aplikasi Flutter. Dirancang khusus untuk ekosistem sekolah / instansi, aplikasi mendistribusikan berkas APK langsung melalui server web sekolah secara andal dan aman.

---

## 🚀 Fitur Utama

1. **Pemeriksaan Versi Otomatis (Version Enforcement & Semantic Versioning)**:
   - Membaca versi aplikasi lokal via `package_info_plus`.
   - Mengambil konfigurasi dari REST API server sekolah.
   - Algoritma pembanding SemVer cerdas (`compareSemver`) yang mendukung format `major.minor.patch` serta membersihkan prefix `v` dan build metadata.
   - Mendukung dua jenis pembaruan:
     * **Force Update (Wajib)**: Muncul jika `is_force_update == true` atau versi lokal `< min_supported_version`. Layar modal dikunci total (`barrierDismissible: false` & `PopScope(canPop: false)`).
     * **Optional Update**: Siswa/Guru dapat memilih "Nanti Saja" atau memperbarui kapan saja.

2. **Pengunduhan Berkas Streaming & Real-Time Progress Indicator**:
   - Didukung oleh `dio` dengan *stream callback*.
   - Progress bar interaktif visual dengan perhitungan persentase, data terunduh vs total ukuran (`Mengunduh: 45% (12.4 MB / 27.5 MB)`).
   - Validasi integritas berkas pasca unduh (ukuran berkas > 0 byte & perbandingan Content-Length).
   - Mendukung `CancelToken` untuk pembatalan aman tanpa memory leak.

3. **Penanganan Izin Komprehensif (Android 8.0 hingga Android 14+)**:
   - Mendukung Scoped Storage (Android 10 - 14+): Menyimpan file APK di direktori aman aplikasi (`getExternalCacheDirectories()` / `getTemporaryDirectory()`), bebas dari penolakan izin `WRITE_EXTERNAL_STORAGE` di Android 11+.
   - Penanganan izin `REQUEST_INSTALL_PACKAGES` (Unknown App Sources / Pasang aplikasi tidak dikenal):
     * Otomatis mendeteksi status izin.
     * Jika belum aktif, membuka layar pengaturan khusus aplikasi via intent `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`.

4. **Pemicu Instalasi Paket Otomatis (Auto-Trigger Native Installer)**:
   - Menggunakan `open_filex` dengan tipe MIME `application/vnd.android.package-archive`.
   - Dilengkapi **Native Kotlin MethodChannel Fallback** (`MainActivity.kt`) dengan `FileProvider` untuk menjamin layar konfirmasi sistem Android ("Apakah Anda ingin memasang pembaruan?") langsung terbuka tanpa kendala.

---

## 📁 Struktur Berkas

```
C:\APK\flutter_reference\in_app_update\
├── android\
│   ├── AndroidManifest.xml          # Izin & konfigurasi <provider> FileProvider
│   ├── MainActivity.kt              # Native MethodChannel fallback installer
│   └── res\xml\
│       └── file_paths.xml           # Definisi path FileProvider aman
├── lib\
│   ├── in_app_update.dart           # Barrel export modul
│   ├── models\
│   │   └── app_version_info.dart    # Model data versi & parser SemVer
│   ├── services\
│   │   └── update_manager_service.dart # Service Dio downloader, izin, & installer
│   └── widgets\
│       └── force_update_dialog.dart # UI Modal non-dismissible + progress bar
├── example\
│   └── main.dart                    # Contoh implementasi Splash Screen & Home
├── pubspec.yaml                     # Spesifikasi dependensi Flutter
└── README.md                        # Dokumentasi teknis lengkap
```

---

## 🛠️ Langkah-Langkah Integrasi

### 1. Tambahkan Dependensi di `pubspec.yaml`
```yaml
dependencies:
  flutter:
    sdk: flutter
  dio: ^5.4.1
  package_info_plus: ^8.0.0
  path_provider: ^2.1.2
  permission_handler: ^11.3.0
  open_filex: ^4.4.0
```

### 2. Salin Konfigurasi Android Native

#### A. `android/app/src/main/AndroidManifest.xml`
Tambahkan izin di luar tag `<application>`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
```

Tambahkan `<provider>` di dalam tag `<application>`:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

#### B. `android/app/src/main/res/xml/file_paths.xml`
Buat berkas ini jika belum ada:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-cache-path name="external_cache" path="." />
    <cache-path name="internal_cache" path="." />
    <files-path name="internal_files" path="." />
    <external-files-path name="external_files" path="." />
    <external-path name="external_storage_root" path="." />
</paths>
```

#### C. `android/app/src/main/kotlin/.../MainActivity.kt`
Gunakan implementasi dari `android/MainActivity.kt` jika Anda ingin mendukung native fallback MethodChannel.

---

## 🌐 Spesifikasi REST API Backend

Aplikasi mengecek pembaruan via HTTP GET:
```http
GET /api/v1/app/check-version?platform=android&current_version=1.0.2 HTTP/1.1
Host: cbt.smpn1boyolangu.my.id
Accept: application/json
```

### Format Respons JSON:
```json
{
  "status": "success",
  "data": {
    "latest_version": "1.1.0",
    "min_supported_version": "1.0.5",
    "download_url": "https://cbt.smpn1boyolangu.my.id/uploads/smartcbt-latest.apk",
    "changelog": "• Pembaruan sistem presensi QR\\n• Integrasi Digital School Gatepass Satpam\\n• Perbaikan stabilitas koneksi jaringan",
    "is_force_update": true
  }
}
```

---

## 💡 Contoh Pemakaian di Flutter (Splash Screen)

```dart
import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:in_app_update/in_app_update.dart';

void checkAppUpdate(BuildContext context) async {
  final packageInfo = await PackageInfo.fromPlatform();
  final currentVersion = packageInfo.version;

  const endpoint = 'https://cbt.smpn1boyolangu.my.id/api/v1/app/check-version';

  final versionInfo = await UpdateManagerService.instance.checkForUpdate(
    endpointUrl: endpoint,
    currentVersion: currentVersion,
  );

  if (context.mounted && versionInfo != null && versionInfo.shouldUpdate(currentVersion)) {
    await ForceUpdateDialog.show(
      context: context,
      versionInfo: versionInfo,
      currentVersion: currentVersion,
      onDismissOptional: () {
        // Lanjut ke dashboard jika update opsional
      },
    );
  }
}
```

---

## 🛡️ Kompatibilitas Sistem Operasi Android
- **Android 8.0 - 9.0 (API 26-28)**: Didukung penuh. Menggunakan izin `REQUEST_INSTALL_PACKAGES` dan fallback legacy storage jika dibutuhkan.
- **Android 10 - 12 (API 29-31)**: Didukung penuh via Scoped Storage tanpa meminta izin penyimpanan berbahaya.
- **Android 13 - 14+ (API 33-34+)**: Didukung penuh dengan modern FileProvider content URI, flag `FLAG_GRANT_READ_URI_PERMISSION`, dan UI yang kompatibel dengan tema Material 3.
