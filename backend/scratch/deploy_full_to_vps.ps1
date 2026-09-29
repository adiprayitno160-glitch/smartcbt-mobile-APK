$VpsIP = "104.207.76.250"
$Port = 22022
$Pass = "Digicore@1"
$HostKey = "SHA256:awv5xhVaeCvHtIO8SqnfX2GpBXAdtaETA8cU3K0YsxE"
$Plink = "C:\Program Files\PuTTY\plink.exe"
$Pscp = "C:\Program Files\PuTTY\pscp.exe"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " DEPLOYING SMART SCHOOL TO NEW VPS       " -ForegroundColor Cyan
Write-Host " Target: $VpsIP : $Port                   " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# 1. Pastikan build dist terbaru
Write-Host "[1/5] Building dist (npm run build)..." -ForegroundColor Yellow
Set-Location "c:\APK\backend"
npm run build

# 2. Upload konfigurasi dan prisma
Write-Host "[2/5] Uploading core configs & prisma database..." -ForegroundColor Yellow
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey "c:\APK\backend\package.json" root@${VpsIP}:/var/www/smart-school/
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey "c:\APK\backend\package-lock.json" root@${VpsIP}:/var/www/smart-school/
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey "c:\APK\backend\ecosystem.config.js" root@${VpsIP}:/var/www/smart-school/
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey "c:\APK\backend\.env" root@${VpsIP}:/var/www/smart-school/
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey "c:\APK\backend\prisma\schema.prisma" root@${VpsIP}:/var/www/smart-school/prisma/
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey "c:\APK\backend\prisma\dev.db" root@${VpsIP}:/var/www/smart-school/prisma/

# 3. Upload dist, views, dan public
Write-Host "[3/5] Uploading dist, views, & public assets..." -ForegroundColor Yellow
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey -r "c:\APK\backend\dist" root@${VpsIP}:/var/www/smart-school/
& $Plink -batch -P $Port -pw $Pass -hostkey $HostKey root@${VpsIP} "mkdir -p /var/www/smart-school/src"
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey -r "c:\APK\backend\src\views" root@${VpsIP}:/var/www/smart-school/src/
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey -r "c:\APK\backend\public" root@${VpsIP}:/var/www/smart-school/

# 4. Install dependencies & Prisma di VPS
Write-Host "[4/5] Installing npm modules & generating Prisma Linux client on VPS..." -ForegroundColor Yellow
$remoteCommands = @"
cd /var/www/smart-school
npm install --omit=dev
npx prisma generate
pm2 delete smart-school-backend 2>/dev/null || true
pm2 start ecosystem.config.js
pm2 save
pm2 status
"@

$cmdFile = "c:\APK\backend\scratch\run_deploy.sh"
Set-Content -Path $cmdFile -Value $remoteCommands -Encoding Ascii
& $Plink -batch -P $Port -pw $Pass -hostkey $HostKey -m $cmdFile root@${VpsIP}

Write-Host "==========================================" -ForegroundColor Green
Write-Host " DEPLOYMENT TO VPS COMPLETED SUCCESSFULLY!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
