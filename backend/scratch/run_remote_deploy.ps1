$HostIP = "104.207.76.73"
$Port = 22022
$Pass = "Digicore@1"
$HostKey = "SHA256:46kqNyXxgSB9uALFaArfB/kQ0ktO4uNwNv5IFIyxuEA"
$Plink = "C:\Program Files\PuTTY\plink.exe"
$Pscp = "C:\Program Files\PuTTY\pscp.exe"

Write-Host "Mengunggah install_app.sh ke VPS..." -ForegroundColor Yellow
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey "c:\APK\backend\scratch\install_app.sh" root@${HostIP}:/tmp/install_app.sh

Write-Host "Mengunggah deploy.tar.gz ke VPS..." -ForegroundColor Yellow
& $Pscp -batch -P $Port -pw $Pass -hostkey $HostKey "c:\APK\backend\scratch\deploy.tar.gz" root@${HostIP}:/tmp/deploy.tar.gz

Write-Host "Mengeksekusi instalasi dan startup di VPS..." -ForegroundColor Yellow
& $Plink -batch -P $Port -pw $Pass -hostkey $HostKey root@${HostIP} "chmod +x /tmp/install_app.sh && /tmp/install_app.sh"

Write-Host "Selesai!" -ForegroundColor Green
