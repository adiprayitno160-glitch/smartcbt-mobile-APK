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

  console.log(`=== Deploying SmartCBT v2.8.90 (Build 100) ===`);
  console.log(`Source APK size: ${fileBuffer.length} bytes (${fileSizeMb} MB)`);
  console.log(`SHA-256: ${sha256}`);

  // Target destinations
  const targets = [
    path.resolve('C:/APK/backend/uploads/SmartCBT_v2.8.90.apk'),
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
      versionCode: 100,
      versionName: '2.8.90',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: fileSizeMb,
      releaseNotes: 'Update SmartCBT v2.8.90 (Build 100): Grafik Tren Kehadiran Siswa & Orang Tua, Visual Bar Chart Mingguan/Harian, Rekap Persentase Disiplin, Filter GPS Satelit Presisi, Zero-GPS Mode & Room DB Offline Queue.',
      isForceUpdate: true,
    },
    create: {
      id: 'latest',
      versionCode: 100,
      versionName: '2.8.90',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: fileSizeMb,
      releaseNotes: 'Update SmartCBT v2.8.90 (Build 100): Grafik Tren Kehadiran Siswa & Orang Tua, Visual Bar Chart Mingguan/Harian, Rekap Persentase Disiplin, Filter GPS Satelit Presisi, Zero-GPS Mode & Room DB Offline Queue.',
      isForceUpdate: true,
    },
  });
  console.log(`AppVersionConfig updated:`, config);

  // 2. Upsert AppVersionRelease
  const release = await prisma.appVersionRelease.upsert({
    where: { versionCode: 100 },
    update: {
      nomorVersi: '2.8.90',
      changelog: '1. Grafik Tren Kehadiran Siswa & Orang Tua (Visual Bar Chart Mingguan/Harian)\n2. Rekapitulasi Presensi Persentase Lengkap & Segmented Bar\n3. Filter GPS Satelit Akurat & Mode Fallback QR Statis\n4. Mode Zero-GPS via Web Portal Feature Panel\n5. Antrean Presensi Offline Room DB v4 Auto-Sync\n6. Remote Device WhatsApp Stickers (.webp) & Scoped Storage',
      fileApkUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: fileSizeMb,
      checksumSha256: sha256,
      isForceUpdate: true,
      persentaseRollout: 100,
      status: 'ACTIVE',
    },
    create: {
      nomorVersi: '2.8.90',
      versionCode: 100,
      changelog: '1. Grafik Tren Kehadiran Siswa & Orang Tua (Visual Bar Chart Mingguan/Harian)\n2. Rekapitulasi Presensi Persentase Lengkap & Segmented Bar\n3. Filter GPS Satelit Akurat & Mode Fallback QR Statis\n4. Mode Zero-GPS via Web Portal Feature Panel\n5. Antrean Presensi Offline Room DB v4 Auto-Sync\n6. Remote Device WhatsApp Stickers (.webp) & Scoped Storage',
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
