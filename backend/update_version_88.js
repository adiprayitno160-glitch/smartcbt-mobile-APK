const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const sha256 = "5037A86EEF7FCCC4CD896678AB427A32A0CE7453BF9EF05B3EB615575BE7B738";
  const sizeMb = 26.16;
  const releaseNotes = 'Pembaruan Resmi Smart CBT v2.8.78 (Build 88): Header Berwarna Modern untuk Scan QR Mushola & Presensi Mata Pelajaran KBM, Penataan Tempat Khusus Layanan Digital Exit Pass di Ruang BK, Sinkronisasi Presensi Real-Time, dan Optimalisasi Sistem.';

  const config = await prisma.appVersionConfig.upsert({
    where: { id: 'latest' },
    update: {
      versionCode: 88,
      versionName: '2.8.78',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      releaseNotes: releaseNotes,
      isForceUpdate: false
    },
    create: {
      id: 'latest',
      versionCode: 88,
      versionName: '2.8.78',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      releaseNotes: releaseNotes,
      isForceUpdate: false
    }
  });

  const release = await prisma.appVersionRelease.upsert({
    where: { versionCode: 88 },
    update: {
      nomorVersi: '2.8.78',
      changelog: releaseNotes,
      fileApkUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      checksumSha256: sha256,
      isForceUpdate: false,
      persentaseRollout: 100,
      status: 'ACTIVE'
    },
    create: {
      nomorVersi: '2.8.78',
      versionCode: 88,
      changelog: releaseNotes,
      fileApkUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      checksumSha256: sha256,
      isForceUpdate: false,
      persentaseRollout: 100,
      status: 'ACTIVE'
    }
  });

  console.log("Updated config:", config);
  console.log("Updated release:", release);
}

main()
  .catch(console.error)
  .finally(() => prisma.$disconnect());
