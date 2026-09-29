@echo off
title Smart CBT Persistent Server Service
cd /d C:\APK\backend

echo ========================================================
echo   SMART CBT PERSISTENT SERVER DAEMON
echo ========================================================

:: 1. Bebaskan port 3000 jika ada proses zombie yang menggantung
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :3000 ^| findstr LISTENING 2^>nul') do (
    echo [%date% %time%] Membebaskan port 3000 dari PID %%a...
    taskkill /F /PID %%a >nul 2>&1
)

:LOOP
echo [%date% %time%] Memulai server Node.js... >> C:\APK\server_runner.log
echo [%date% %time%] Memulai server di http://localhost:3000...

"C:\Program Files\nodejs\node.exe" dist/server.js >> C:\APK\server_runtime.log 2>&1

echo [%date% %time%] Server terhenti. Me-restart dalam 3 detik... >> C:\APK\server_runner.log
echo [%date% %time%] Server terhenti. Me-restart...

:: Bebaskan port jika crash menyisakan socket
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :3000 ^| findstr LISTENING 2^>nul') do (
    taskkill /F /PID %%a >nul 2>&1
)

:: Gunakan ping sebagai delay aman yang tidak error pada non-interactive / redirection
ping -n 4 127.0.0.1 >nul
goto LOOP
