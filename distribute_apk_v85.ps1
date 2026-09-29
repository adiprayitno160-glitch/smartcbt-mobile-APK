$srcApk = "C:\APK\android_app\app\build\outputs\apk\release\app-release.apk"

if (-not (Test-Path $srcApk)) {
    Write-Error "Source APK not found at $srcApk"
    exit 1
}

$destinations = @(
    "C:\APK\SmartCBT_v2.8.75.apk",
    "C:\APK\smartcbt-latest.apk",
    "C:\APK\SmartSchool_CBT_Release.apk",
    "C:\APK\backend\uploads\smartcbt-latest.apk",
    "C:\APK\backend\uploads\SmartSchool_CBT_Release.apk"
)

foreach ($dest in $destinations) {
    Copy-Item -Path $srcApk -Destination $dest -Force
    Write-Host "✅ Copied to $dest"
}

$fileHash = (Get-FileHash -Path $srcApk -Algorithm SHA256).Hash
$fileSize = (Get-Item $srcApk).Length
$fileSizeMb = [math]::Round($fileSize / (1024 * 1024), 2)

Write-Host "SHA256: $fileHash"
Write-Host "Size: $fileSize bytes ($fileSizeMb MB)"

# Update database release checksum
$updateScript = @"
const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    await prisma.appVersionRelease.updateMany({
        where: { versionCode: 85 },
        data: {
            checksumSha256: '$fileHash',
            fileSizeMb: $fileSizeMb,
            status: 'ACTIVE'
        }
    });
    console.log('✅ Database AppVersionRelease checksum updated');
}
main().catch(console.error).finally(() => prisma.\$disconnect());
"@

Set-Content -Path "C:\APK\backend\src\scripts\update_checksum_85.js" -Value $updateScript
node "C:\APK\backend\src\scripts\update_checksum_85.js"
