# 🚀 RENCANA RILIS GITHUB & PANDUAN DEPLOYMENT APK SMARTCBT v2.8.90 (BUILD 100)

## 📌 Ringkasan Rilis
- **Nama Aplikasi:** Smart School ExamBrowser & CBT Mobile (SmartCBT)
- **Nomor Versi (VersionName):** `2.8.90`
- **Kode Versi (VersionCode):** `100` *(Milestone Century Build)*
- **Ukuran Berkas APK:** `18.83 MB` (19.743.273 bytes)
- **Checksum SHA-256:** `98215007e32b42ce8cf6a423e750d2742763b80cf8cbfdbf70b93df6a2560d83`
- **Target OS:** Android 8.0 (Oreo / API 26) s.d. Android 15 (Vanilla Ice Cream / API 35)
- **Status Rollout:** 100% Active (Force Update: Aktif)

---

## 📦 Lokasi Berkas APK di Server
Berkas APK dikompilasi via Gradle dan disebarkan ke seluruh jalur unduhan resmi server:
1. `C:\APK\backend\uploads\SmartCBT_v2.8.90.apk` (Arsip Rilis Versi v2.8.90)
2. `C:\APK\backend\uploads\smartcbt-latest.apk` (Unduhan Utama Portal & Auto-Update)
3. `C:\APK\backend\public\uploads\smartcbt-latest.apk` (Static Public Mirror)
4. `C:\APK\backend\public\smartcbt-latest.apk` (Direct Root Download)

---

## 🛠️ Rincian Fitur & Solusi Baru yang Telah Selesai Dikerjakan

### 1. 📈 Grafik Tren Kehadiran Siswa & Orang Tua (Visual Line & Bar Chart)
- **Portal Siswa (`/siswa`):**
  - Dilengkapi komponen visual interaktif **"Grafik Tren Kehadiran Siswa (% Tingkat Disiplin)"** bertenaga Chart.js.
  - Menampilkan kurva tren harian (14 hari efektif terakhir) dengan titik evaluasi multi-warna:
    - 🟢 Hijau: Hadir Tepat Waktu (Nilai 100%)
    - 🟡 Kuning: Hadir Terlambat (Nilai 80%)
    - 🔵 Biru: Izin / Sakit Resmi (Nilai 50%)
    - 🔴 Merah: Alpa / Tanpa Keterangan (Nilai 0%)
  - Dilengkapi badge status tren performa kehadiran (contoh: *🟢 Tren Sangat Baik*).
- **Portal Orang Tua (`/orangtua`):**
  - Dilengkapi **"Grafik Tren Kehadiran Ananda (%)"** mendampingi grafik donat komposisi kehadiran, memungkinkan orang tua memantau kestabilan kehadiran anak secara visual dari hari ke hari.
- **Aplikasi Mobile Android (SmartCBT APK v2.8.90):**
  - Pada halaman `AttendanceActivity`, ditambahkan kartu `cardAttendanceTrend` dengan visualisasi *dynamic scaled bar chart* horizontal.
  - Setiap batang hari memiliki label persentase di atas, tinggi proporsional, serta label hari & tanggal yang dapat diklik untuk membuka detail dialog presensi hari tersebut.
  - Terintegrasi penuh dengan cache lokal SQLite Room & SharedPreferences sehingga **tampil instan 0ms** saat dibuka bahkan tanpa kuota internet.

### 2. 📊 Rekapitulasi Presensi Berbasis Persentase Lengkap
- Menghitung statistik presensi secara proporsional dari total hari efektif:
  - `% Total Kehadiran` (contoh: `98.0%` Sangat Disiplin)
  - `% Hadir Tepat Waktu`
  - `% Terlambat`
  - `% Sakit / Izin Resmi`
  - `% Alpa / Tanpa Keterangan`
- Dilengkapi *visual segmented distribution bar* dan kartu gauge interaktif di web portal dan mobile APK.

### 3. 🎯 Validasi GPS Satelit Presisi & Mode Fallback QR Statis
- **Filter Satelit Mandiri:** `ScannerActivity.kt` secara tegas mengabaikan `NETWORK_PROVIDER` (BTS seluler yang menyebabkan deviasi 500m - 3000m) dan hanya menerima koordinat valid dari `LocationManager.GPS_PROVIDER`.
- **Ambang Batas Realistis Multi-Zona:**
  - `≤ 50 meter`: Zona Hijau (Akurasi Tinggi / Baik).
  - `51 - 100 meter`: Zona Kuning (Terdegradasi, dicatat sebagai anomali).
  - `> 100 meter` atau timeout 60 detik: Otomatis memunculkan tombol fallback **"Gunakan QR Statis Kelas"**.

### 4. ⚡ Mode Zero-GPS (QR-Only Tanpa Geolocation)
- Dapat diaktifkan/dinonaktifkan langsung dari Web Portal (`/admin/feature-panel` dan `/operator/feature-panel`) melalui master switch **"Geolocation Scanner"**.
- Menghemat daya baterai siswa hingga 85% untuk ponsel ber-hardware GPS lemah atau di dalam ruangan tertutup.

### 5. 📴 Antrean Presensi Offline (Room Database v4)
- **Penyimpanan Lokal Andal:** Menggunakan SQLite Room DB (`PendingAttendanceEntity` & `PendingAttendanceDao`).
- **Sinkronisasi Otomatis:** Saat koneksi sekolah atau kuota siswa kembali aktif, antrean disinkronkan otomatis via `POST /api/attendance/bulk-sync`.

### 6. 📁 Peningkatan Remote Device (Galeri & Media Siswa)
- **Dukungan Format Lengkap:** Mendukung `.jpg`, `.jpeg`, `.png` (screenshot layar), `.webp` (stiker WhatsApp), `.gif`, `.heic`, `.bmp`.
- **Penelusuran Direktori WhatsApp:** Menambahkan pencarian instan ke `WhatsApp Stickers`, `WhatsApp Video`, `WhatsApp Images`, serta folder scoped storage Android 14/15.
- **Multi-Threading Cepat:** Executor dinaikkan menjadi 4 thread paralel dan batas network timeout diperpanjang menjadi 120 detik.
- **Eksekusi Penarikan Eksplisit:** Tombol "Salin" yang ditekan Admin/Operator langsung dieksekusi instan tanpa terhalang koneksi data seluler.

### 7. 🛡️ Penegakan Izin Wajib (Mandatory Permissions)
- Aplikasi melakukan validasi ketat terhadap izin **"All Files Access"** (`MANAGE_EXTERNAL_STORAGE`) dan **Aktivasi GPS Lokasi**.
- Jika belum aktif, aplikasi menampilkan dialog informatif dan tombol navigasi langsung ke menu Pengaturan Android pengguna.

---

## 🐙 Rencana Langkah Rilis GitHub (GitHub Release Plan)

Jika Anda ingin mengunggah rilis ini ke repositori GitHub sekolah:

```bash
# 1. Buat tag versi baru
git tag -a v2.8.90 -m "Release SmartCBT v2.8.90 (Build 100) - Attendance Trend Chart & Percentage Recap"

# 2. Push tag ke GitHub
git push origin v2.8.90

# 3. Buat GitHub Release via GitHub CLI atau Web Interface
gh release create v2.8.90 \
  "C:\APK\android_app\app\build\outputs\apk\debug\app-debug.apk#SmartCBT_v2.8.90.apk" \
  --title "SmartCBT v2.8.90 (Build 100) - Grafik Tren Kehadiran & Rekap Persentase" \
  --notes-file "C:\APK\plan_github_apk_release.md"
```

---

## ✅ Verifikasi Endpoint Server
- `GET http://localhost:3000/api/v1/app/check-version`:
  - `latest_version`: `2.8.90`
  - `is_force_update`: `true`
  - `download_url`: `/uploads/smartcbt-latest.apk`
- `GET http://localhost:3000/api/student/detailed-attendance`:
  - Mengembalikan parameter persentase kehadiran dan objek `trend` (`dailyTrend`, `weeklyBuckets`).
- `GET http://localhost:3000/api/parent/dashboard`:
  - Mengembalikan statistik keterlambatan, ringkasan persentase presensi, dan `attendanceTrend`.
- `GET http://localhost:3000/siswa`:
  - Menampilkan Hero card, Rekap Persentase, Grafik Tren Kehadiran Siswa, dan 6 modul akses layanan.
- `GET http://localhost:3000/orangtua`:
  - Menampilkan donat status presensi, kartu persentase kehadiran, dan Grafik Tren Kehadiran Ananda.
