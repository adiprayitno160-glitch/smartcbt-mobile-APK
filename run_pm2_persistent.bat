@echo off
title Smart School CBT - Persistent PM2 Service
cd /d C:\APK\backend

echo ========================================================
echo   SMART SCHOOL PERSISTENT PM2 SUPERVISOR
echo ========================================================

set PM2_HOME=C:\Users\user\.pm2
set NODE_ENV=production

echo [%date% %time%] [PM2 SUPERVISOR START] Memulai supervisor... >> C:\APK\pm2_persistent.log

:: 1. Mulai atau Pulihkan PM2
call "C:\Users\user\AppData\Roaming\npm\pm2.cmd" resurrect >> C:\APK\pm2_persistent.log 2>&1
call "C:\Users\user\AppData\Roaming\npm\pm2.cmd" start ecosystem.config.js >> C:\APK\pm2_persistent.log 2>&1
call "C:\Users\user\AppData\Roaming\npm\pm2.cmd" save --force >> C:\APK\pm2_persistent.log 2>&1

:: 2. Monitoring loop tangguh - periksa port 3000 setiap 15 detik
:LOOP
ping -n 16 127.0.0.1 >nul

netstat -aon | findstr :3000 | findstr LISTENING >nul 2>&1
if %errorlevel% neq 0 (
    echo [%date% %time%] [ALERT] Port 3000 terdeteksi padam! Memulihkan via PM2... >> C:\APK\pm2_persistent.log
    call "C:\Users\user\AppData\Roaming\npm\pm2.cmd" resurrect >> C:\APK\pm2_persistent.log 2>&1
    call "C:\Users\user\AppData\Roaming\npm\pm2.cmd" restart smart-school-backend >> C:\APK\pm2_persistent.log 2>&1
    call "C:\Users\user\AppData\Roaming\npm\pm2.cmd" save --force >> C:\APK\pm2_persistent.log 2>&1
)

goto LOOP
