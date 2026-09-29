@echo off
cd /d C:\APK\backend
set PM2_HOME=C:\Users\user\.pm2
call C:\Users\user\AppData\Roaming\npm\pm2.cmd start ecosystem.config.js >> C:\APK\test_pm2.log 2>&1
call C:\Users\user\AppData\Roaming\npm\pm2.cmd save --force >> C:\APK\test_pm2.log 2>&1
:LOOP
ping -n 5 127.0.0.1 >nul
goto LOOP
