sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Class ADD COLUMN isGpsLocked BOOLEAN DEFAULT 0;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Class ADD COLUMN counselorId TEXT;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Class ADD COLUMN counselorName TEXT;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE TABLE IF NOT EXISTS SchoolHoliday (id TEXT PRIMARY KEY, date TEXT UNIQUE NOT NULL, name TEXT NOT NULL, description TEXT, isNationalHoliday BOOLEAN DEFAULT 0, createdBy TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "CREATE TABLE IF NOT EXISTS BapReport (id TEXT PRIMARY KEY, examId TEXT NOT NULL, examTitle TEXT NOT NULL, subject TEXT NOT NULL, className TEXT NOT NULL, level TEXT, proctorId TEXT, proctorName TEXT NOT NULL, roomName TEXT NOT NULL, totalRegistered INTEGER DEFAULT 0, totalPresent INTEGER DEFAULT 0, totalAbsent INTEGER DEFAULT 0, absentList TEXT, incidentNotes TEXT, signedAt DATETIME DEFAULT CURRENT_TIMESTAMP);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE User ADD COLUMN deviceBindingId TEXT;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN examType TEXT DEFAULT 'SAS';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN roomName TEXT DEFAULT 'Lab Komputer 1';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN proctorName TEXT DEFAULT 'Proktor Utama';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN passingGrade INTEGER DEFAULT 75;" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE Exam ADD COLUMN level TEXT DEFAULT 'ALL';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "INSERT OR REPLACE INTO AppVersionConfig (id, versionCode, versionName, downloadUrl, fileSizeMb, releaseNotes, isForceUpdate, updatedAt) VALUES ('latest', 71, '2.8.63', '/uploads/smartcbt-latest.apk', 27.2, 'Pembaruan v2.8.63 (Build 71): Perbaikan tuntas pengunduhan APK terbaru di VPS & lokal, pembersihan notifikasi otomatis, dan sinkronisasi rilis multi-direktori.', 1, CURRENT_TIMESTAMP);" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "UPDATE User SET role = 'COUNSELOR' WHERE username = 'bk';" 2>/dev/null
sqlite3 /var/www/smart-school/prisma/dev.db "ALTER TABLE GateSetting ADD COLUMN dailySchedule TEXT;" 2>/dev/null
mkdir -p /var/www/smart-school/uploads/avatars /var/www/smart-school/public/uploads /var/www/smart-school/uploads 2>/dev/null
echo "Database migration completed."
