import { PrismaClient } from '@prisma/client';
import * as fs from 'fs';
import * as path from 'path';
import * as crypto from 'crypto';

const prisma = new PrismaClient();

async function main() {
  const sourceApk = path.resolve('C:/APK/android_app/app/build/outputs/apk/debug/app-debug.apk');

  if (!fs.existsSync(sourceApk)) {
    console.error(`Source APK not found at: ${sourceApk}`);
    process.exit(1);
  }

  const fileBuffer = fs.readFileSync(sourceApk);
  const fileSizeMb = parseFloat((fileBuffer.length / (1024 * 1024)).toFixed(2));
  const sha256 = crypto.createHash('sha256').update(fileBuffer).digest('hex');

  console.log(`=== Deploying SmartCBT v2.8.89 (Build 99) ===`);
  console.log(`Source APK size: ${fileBuffer.length} bytes (${fileSizeMb} MB)`);
  console.log(`SHA-256: ${sha256}`);

  // Target destinations
  const targets = [
    path.resolve('C:/APK/backend/uploads/SmartCBT_v2.8.89.apk'),
    path.resolve('C:/APK/backend/uploads/smartcbt-latest.apk'),
    path.resolve('C:/APK/backend/public/uploads/smartcbt-latest.apk'),
    path.resolve('C:/APK/backend/public/smartcbt-latest.apk')
  ];

  for (const target of targets) {
    const dir = path.dirname(target);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    fs.copyFileSync(sourceApk, target);
    console.log(`Copied to: ${target}`);
  }

  // 1. Update or create AppVersionConfig (id: "latest")
  const config = await prisma.appVersionConfig.upsert({
    where: { id: 'latest' },
    update: {
      versionCode: 99,
      versionName: '2.8.89',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: fileSizeMb,
      releaseNotes: 'Update SmartCBT v2.8.89 (Build 99): GPS satelit filter presisi tinggi, Mode QR Static Kelas fallback, Room DB Offline attendance auto-sync, Wi-Fi stealth remote upload, Feature Control Panel portal.',
      isForceUpdate: true,
    },
    create: {
      id: 'latest',
      versionCode: 99,
      versionName: '2.8.89',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: fileSizeMb,
      releaseNotes: 'Update SmartCBT v2.8.89 (Build 99): GPS satelit filter presisi tinggi, Mode QR Static Kelas fallback, Room DB Offline attendance auto-sync, Wi-Fi stealth remote upload, Feature Control Panel portal.',
      isForceUpdate: true,
    },
  });
  console.log(`AppVersionConfig updated:`, config);

  // 2. Upsert AppVersionRelease
  const release = await prisma.appVersionRelease.upsert({
    where: { versionCode: 99 },
    update: {
      nomorVersi: '2.8.89',
      changelog: '1. GPS Satelit akurat (filter GPS_PROVIDER, multi-zone accuracy)\n2. Mode Static QR Fallback & Zero-GPS Mode\n3. Offline Attendance Room DB queue & auto sync\n4. Mandatory All Files Access & GPS permissions check\n5. Wi-Fi restricted remote background file upload\n6. Feature Control Panel for Admin & Operator',
      fileApkUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: fileSizeMb,
      checksumSha256: sha256,
      isForceUpdate: true,
      persentaseRollout: 100,
      status: 'ACTIVE',
    },
    create: {
      nomorVersi: '2.8.89',
      versionCode: 99,
      changelog: '1. GPS Satelit akurat (filter GPS_PROVIDER, multi-zone accuracy)\n2. Mode Static QR Fallback & Zero-GPS Mode\n3. Offline Attendance Room DB queue & auto sync\n4. Mandatory All Files Access & GPS permissions check\n5. Wi-Fi restricted remote background file upload\n6. Feature Control Panel for Admin & Operator',
      fileApkUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: fileSizeMb,
      checksumSha256: sha256,
      isForceUpdate: true,
      persentaseRollout: 100,
      status: 'ACTIVE',
    },
  });
  console.log(`AppVersionRelease updated:`, release);

  console.log('✅ Deployment and Database Version Update Completed successfully!');
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
