$HostIP = "cbt.smpn1boyolangu.my.id"
$FallbackIP = "104.207.76.73"
$Port = 22022
$Pass = "Digicore@1"
$HostKey = "SHA256:46kqNyXxgSB9uALFaArfB/kQ0ktO4uNwNv5IFIyxuEA"
$Plink = "C:\Program Files\PuTTY\plink.exe"
$Pscp = "C:\Program Files\PuTTY\pscp.exe"

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "   DEPLOYMENT SMART SCHOOL KE VPS ($HostIP)      " -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

Write-Host "[1/5] Menguji koneksi ke VPS ${HostIP} port ${Port}..." -ForegroundColor Yellow
$targetHost = $HostIP
$test = Test-NetConnection -ComputerName $HostIP -Port $Port -InformationLevel Quiet
if (-not $test) {
    Write-Host "[!] Domain ${HostIP} belum terhubung atau sedang propagasi DNS, mencoba fallback IP VPS ($FallbackIP)..." -ForegroundColor Yellow
    $testFallback = Test-NetConnection -ComputerName $FallbackIP -Port $Port -InformationLevel Quiet
    if ($testFallback) {
        $targetHost = $FallbackIP
        Write-Host "[+] Koneksi melalui IP VPS ($FallbackIP) berhasil!" -ForegroundColor Green
    } else {
        Write-Host "[-] Gagal terhubung ke ${HostIP} maupun ${FallbackIP} pada port ${Port}." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "[+] Koneksi ke VPS via domain ${HostIP} berhasil!" -ForegroundColor Green
}

Write-Host "[2/5] Menjalankan migrasi skema database di VPS..." -ForegroundColor Yellow
$remoteSql = @"
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Class ADD COLUMN isGpsLocked BOOLEAN DEFAULT 0;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Class ADD COLUMN counselorId TEXT;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Class ADD COLUMN counselorName TEXT;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE TABLE IF NOT EXISTS SchoolHoliday (id TEXT PRIMARY KEY, date TEXT UNIQUE NOT NULL, name TEXT NOT NULL, description TEXT, isNationalHoliday BOOLEAN DEFAULT 0, createdBy TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE TABLE IF NOT EXISTS BapReport (id TEXT PRIMARY KEY, examId TEXT NOT NULL, examTitle TEXT NOT NULL, subject TEXT NOT NULL, className TEXT NOT NULL, level TEXT, proctorId TEXT, proctorName TEXT NOT NULL, roomName TEXT NOT NULL, totalRegistered INTEGER DEFAULT 0, totalPresent INTEGER DEFAULT 0, totalAbsent INTEGER DEFAULT 0, absentList TEXT, incidentNotes TEXT, signedAt DATETIME DEFAULT CURRENT_TIMESTAMP);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE User ADD COLUMN deviceBindingId TEXT;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE User ADD COLUMN tugasTambahan TEXT;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN examType TEXT DEFAULT 'SAS';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN roomName TEXT DEFAULT 'Lab Komputer 1';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN proctorName TEXT DEFAULT 'Proktor Utama';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN passingGrade INTEGER DEFAULT 75;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN level TEXT DEFAULT 'ALL';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE TABLE IF NOT EXISTS TeacherLeaveRequest (id TEXT PRIMARY KEY, teacherId TEXT NOT NULL, teacherName TEXT NOT NULL, category TEXT NOT NULL, startDate DATETIME NOT NULL, endDate DATETIME NOT NULL, reason TEXT NOT NULL, affectedSchedules TEXT, assignmentDetails TEXT, attachmentUrl TEXT, status TEXT DEFAULT 'PENDING', approvedBy TEXT, approvalNotes TEXT, approvedAt DATETIME, dispositionToPiket BOOLEAN DEFAULT 0, piketNotes TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE TABLE IF NOT EXISTS PrayerClassSchedule (id TEXT PRIMARY KEY, dayOfWeek INTEGER NOT NULL, dayName TEXT NOT NULL, prayerType TEXT DEFAULT 'DHUHUR', classNames TEXT, barcodeKey TEXT DEFAULT 'BARCODE_SHOLAT_DHUHUR_MASJID', notes TEXT, isActive BOOLEAN DEFAULT 1, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE TABLE IF NOT EXISTS StudentPrayerAttendance (id TEXT PRIMARY KEY, studentId TEXT NOT NULL, studentName TEXT NOT NULL, className TEXT NOT NULL, gender TEXT, prayerType TEXT NOT NULL, date TEXT NOT NULL, status TEXT NOT NULL, method TEXT DEFAULT 'STATIC_BARCODE', recordedById TEXT, recordedByName TEXT, notes TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE UNIQUE INDEX IF NOT EXISTS idx_student_prayer_unique ON StudentPrayerAttendance(studentId, prayerType, date);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE INDEX IF NOT EXISTS idx_student_prayer_lookup ON StudentPrayerAttendance(date, prayerType, className);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "INSERT OR REPLACE INTO AppVersionConfig (id, versionCode, versionName, downloadUrl, fileSizeMb, releaseNotes, isForceUpdate, updatedAt) VALUES ('latest', 72, '2.8.64', '/uploads/smartcbt-latest.apk', 27.24, 'Pembaruan v2.8.64 (Build 72): Multi-Role Switcher Guru & Operator APK, Posko Guru Piket & Izin Guru Terintegrasi, Rekap Presensi 360 Derajat & Verifikasi Izin Siswa BK dengan Foto Surat, serta Scan Barcode Sholat Berjamaah Masjid & Kontrol Guru PAI.', 1, CURRENT_TIMESTAMP);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "UPDATE User SET role = 'COUNSELOR' WHERE username = 'bk';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE GateSetting ADD COLUMN dailySchedule TEXT;" 2>/dev/null
mkdir -p /var/www/smart-school/uploads/avatars /var/www/smart-school/public/uploads /var/www/smart-school/uploads /var/www/smart-school/uploads/teacher_leaves /var/www/smart-school/public/uploads/teacher_leaves 2>/dev/null
echo "Database migration completed."
"@

$sqlFile = "$PSScriptRoot\backend\scratch\remote_setup.sh"
Set-Content -Path $sqlFile -Value $remoteSql -Encoding Ascii
& $Plink -batch -P $Port -pw $Pass -hostkey $HostKey -m $sqlFile root@$targetHost

Write-Host "[3/5] Memastikan build dist terbaru..." -ForegroundColor Yellow
Set-Location "$PSScriptRoot\backend"
npm run build
Copy-Item -Path "src\views" -Destination "dist\views" -Recurse -Force
Set-Location "$PSScriptRoot"

Write-Host "[4/5] Mengunggah file dist, views, public assets, avatars, dan APK terbaru ke VPS..." -ForegroundColor Yellow
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey -r C:\APK\backend\dist root@${targetHost}:/var/www/smart-school/
& $Plink -batch -P $Port -pw $Pass -hostkey $HostKey root@${targetHost} "mkdir -p /var/www/smart-school/src /var/www/smart-school/uploads /var/www/smart-school/public/uploads"
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey -r C:\APK\backend\src\views root@${targetHost}:/var/www/smart-school/src/
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey -r C:\APK\backend\public root@${targetHost}:/var/www/smart-school/
if (Test-Path "C:\APK\backend\uploads\avatars") {
    Write-Host "[+] Menyinkronkan folder avatars ke VPS..." -ForegroundColor Cyan
    & $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey -r C:\APK\backend\uploads\avatars root@${targetHost}:/var/www/smart-school/uploads/
}
if (Test-Path "C:\APK\smartcbt-latest.apk") {
    Write-Host "[+] Mengunggah file APK smartcbt-latest.apk (v2.8.64) ke VPS..." -ForegroundColor Cyan
    & $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey C:\APK\smartcbt-latest.apk root@${targetHost}:/var/www/smart-school/public/uploads/smartcbt-latest.apk
    & $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey C:\APK\backend\prisma\schema.prisma root@${targetHost}:/var/www/smart-school/prisma/schema.prisma
    Write-Host "[+] Menyinkronkan APK ke seluruh direktori unduhan VPS..." -ForegroundColor Cyan
    $copyCmd = "cp /var/www/smart-school/public/uploads/smartcbt-latest.apk /var/www/smart-school/public/uploads/SmartSchool_CBT_Release.apk; cp /var/www/smart-school/public/uploads/smartcbt-latest.apk /var/www/smart-school/uploads/smartcbt-latest.apk; cp /var/www/smart-school/public/uploads/smartcbt-latest.apk /var/www/smart-school/uploads/SmartSchool_CBT_Release.apk; cp /var/www/smart-school/public/uploads/smartcbt-latest.apk /var/www/smart-school/public/smartcbt-latest.apk; cp /var/www/smart-school/public/uploads/smartcbt-latest.apk /var/www/smart-school/public/SmartSchool_CBT_Release.apk"
    & $Plink -batch -P $Port -pw $Pass -hostkey $HostKey root@$targetHost $copyCmd
}

Write-Host "[5/5] Me-restart PM2 smart-school-backend di VPS..." -ForegroundColor Yellow
& $Plink -batch -P $Port -pw $Pass -hostkey $HostKey root@$targetHost "pm2 restart smart-school-backend; pm2 status"

Write-Host "=================================================" -ForegroundColor Green
Write-Host "   DEPLOYMENT SUKSES SELESAI!                    " -ForegroundColor Green
Write-Host "=================================================" -ForegroundColor Green
