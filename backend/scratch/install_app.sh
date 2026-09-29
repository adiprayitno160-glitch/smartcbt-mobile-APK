#!/usr/bin/env bash
set -e

echo "=== [1/4] Extracting bundle to /var/www/smart-school ==="
mkdir -p /var/www/smart-school
cd /var/www/smart-school
tar -xzf /tmp/deploy.tar.gz

mkdir -p /var/www/smart-school/uploads/avatars
mkdir -p /var/www/smart-school/uploads/device-files
mkdir -p /var/www/smart-school/public/uploads
cp -f /var/www/smart-school/public/uploads/smartcbt-latest.apk /var/www/smart-school/uploads/ 2>/dev/null || true
cp -f /var/www/smart-school/public/uploads/smartcbt-latest.apk /var/www/smart-school/public/SmartSchool_CBT_Release.apk 2>/dev/null || true

echo "=== [2/4] Installing NPM dependencies ==="
npm install

echo "=== [3/4] Generating Prisma client and migrating DB ==="
npx prisma generate

sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Class ADD COLUMN isGpsLocked BOOLEAN DEFAULT 0;" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Class ADD COLUMN counselorId TEXT;" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Class ADD COLUMN counselorName TEXT;" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE TABLE IF NOT EXISTS SchoolHoliday (id TEXT PRIMARY KEY, date TEXT UNIQUE NOT NULL, name TEXT NOT NULL, description TEXT, isNationalHoliday BOOLEAN DEFAULT 0, createdBy TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP);" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE TABLE IF NOT EXISTS BapReport (id TEXT PRIMARY KEY, examId TEXT NOT NULL, examTitle TEXT NOT NULL, subject TEXT NOT NULL, className TEXT NOT NULL, level TEXT, proctorId TEXT, proctorName TEXT NOT NULL, roomName TEXT NOT NULL, totalRegistered INTEGER DEFAULT 0, totalPresent INTEGER DEFAULT 0, totalAbsent INTEGER DEFAULT 0, absentList TEXT, incidentNotes TEXT, signedAt DATETIME DEFAULT CURRENT_TIMESTAMP);" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE User ADD COLUMN deviceBindingId TEXT;" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN examType TEXT DEFAULT 'SAS';" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN roomName TEXT DEFAULT 'Lab Komputer 1';" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN proctorName TEXT DEFAULT 'Proktor Utama';" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN passingGrade INTEGER DEFAULT 75;" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN level TEXT DEFAULT 'ALL';" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "INSERT OR REPLACE INTO AppVersionConfig (id, versionCode, versionName, downloadUrl, fileSizeMb, releaseNotes, isForceUpdate, updatedAt) VALUES ('latest', 68, '2.8.60', '/uploads/smartcbt-latest.apk', 27.5, 'Pembaruan v2.8.60 (Build 68): Auto update sinkron VPS & Cloud, perbaikan data diri siswa tersinkron otomatis, foto profil permanen, icon poin kedisiplinan jernih, dan akses portal terpadu.', 1, CURRENT_TIMESTAMP);" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "UPDATE User SET role = 'COUNSELOR' WHERE username = 'bk';" 2>/dev/null || true
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE GateSetting ADD COLUMN dailySchedule TEXT;" 2>/dev/null || true

echo "=== [4/4] Starting PM2 service ==="
pm2 delete smart-school-backend 2>/dev/null || true
pm2 start ecosystem.config.js --env production
pm2 save
pm2 startup systemd -u root --hp /root 2>/dev/null || true

echo "=== Checking PM2 status ==="
pm2 status

echo "=== Checking Local Health ==="
sleep 2
curl -s http://127.0.0.1:3000/api/app/version-check || true

echo ""
echo "=== All deployment steps completed! ==="
