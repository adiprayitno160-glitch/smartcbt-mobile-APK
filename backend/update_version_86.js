const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const sha256 = "E3BAFA1BFB2CED866467F8956CAED216CC0A2CD4C5018A7A98266AD472E76758";
  const sizeMb = 26.16;
  const releaseNotes = 'Pembaruan Resmi Smart CBT v2.8.76: Header Berwarna Modern untuk Scan QR Mushola & Presensi Mata Pelajaran KBM, Penataan Layanan Digital Exit Pass di Ruang BK, Sinkronisasi Presensi Real-Time, dan Penguncian Gerbang Presensi Siswa Izin/Sakit.';

  const config = await prisma.appVersionConfig.upsert({
    where: { id: 'latest' },
    update: {
      versionCode: 86,
      versionName: '2.8.76',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      releaseNotes: releaseNotes,
      isForceUpdate: false
    },
    create: {
      id: 'latest',
      versionCode: 86,
      versionName: '2.8.76',
      downloadUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      releaseNotes: releaseNotes,
      isForceUpdate: false
    }
  });

  const release = await prisma.appVersionRelease.upsert({
    where: { versionCode: 86 },
    update: {
      nomorVersi: '2.8.76',
      changelog: releaseNotes,
      fileApkUrl: '/uploads/smartcbt-latest.apk',
      fileSizeMb: sizeMb,
      checksumSha256: sha256,
      isForceUpdate: false,
      persentaseRollout: 100,
      status: 'ACTIVE'
    },
    create: {
      nomorVersi: '2.8.76',
      versionCode: 86,
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
