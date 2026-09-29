const { PrismaClient } = require('@prisma/client');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const prisma = new PrismaClient();

async function main() {
  const apkPath = path.resolve(__dirname, 'uploads', 'smartcbt-latest.apk');
  let sha256 = "UNKNOWN";
  let sizeMb = 27.5;

  if (fs.existsSync(apkPath)) {
    const fileBuf = fs.readFileSync(apkPath);
    sha256 = crypto.createHash('sha256').update(fileBuf).digest('hex').toUpperCase();
    sizeMb = parseFloat((fileBuf.length / (1024 * 1024)).toFixed(2));
    console.log(`Found APK: size=${sizeMb} MB, sha256=${sha256}`);
  } else {
    console.warn(`APK not found at ${apkPath}, using fallback size and hash.`);
  }

  const releaseNotes = 'Pembaruan Resmi Smart CBT v2.8.86 (Build 96): Menu Khusus Presensi Siswa (Mushola & KBM), Pengurutan Menu Grid (Cek Update & Panduan Aplikasi di Posisi Paling Akhir), Panduan Aplikasi Spesifik Role Pengguna, Wajib GPS Akurasi Tinggi sebelum Buka Kamera Scanner, Digital Gatepass dengan Dynamic QR & Satpam Checkout, Sinkronisasi Riil Susunan Pengurus Kelas dari Database, serta Penstabilan Total Jaringan Anti Putus Koneksi.';

  const config = await prisma.appVersionConfig.upsert({
    where: { id: 'latest' },
    update: {
      versionCode: 96,
      versionName: '2.8.86',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      releaseNotes: releaseNotes,
      isForceUpdate: true
    },
    create: {
      id: 'latest',
      versionCode: 96,
      versionName: '2.8.86',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      releaseNotes: releaseNotes,
      isForceUpdate: true
    }
  });

  const release = await prisma.appVersionRelease.upsert({
    where: { versionCode: 96 },
    update: {
      nomorVersi: '2.8.86',
      changelog: releaseNotes,
      fileApkUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      checksumSha256: sha256,
      isForceUpdate: true,
      persentaseRollout: 100,
      status: 'ACTIVE'
    },
    create: {
      nomorVersi: '2.8.86',
      versionCode: 96,
      changelog: releaseNotes,
      fileApkUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      checksumSha256: sha256,
      isForceUpdate: true,
      persentaseRollout: 100,
      status: 'ACTIVE'
    }
  });

  console.log("✅ Updated appVersionConfig:", config);
  console.log("✅ Updated appVersionRelease:", release);
}

main()
  .catch(console.error)
  .finally(() => prisma.$disconnect());
