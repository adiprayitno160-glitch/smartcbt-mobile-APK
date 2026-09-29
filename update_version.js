const { PrismaClient } = require('./backend/node_modules/@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const config = await prisma.appVersionConfig.upsert({
        where: { id: 'latest' },
        update: {
            versionCode: 94,
            versionName: '2.8.84',
            downloadUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: 26.5,
            releaseNotes: 'Pembaruan Resmi Smart CBT v2.8.84 (Build 94): Standar Wajib Akurasi GPS Presisi Maksimal 15 Meter & Anti-Fake GPS Terintegrasi, Optimalisasi Manajemen Titik GPS Kelas (Live Lock Satelit & Fallback Multi-Perangkat), Perbaikan Layanan Digital Gatepass Guru BK Portal & APK Siswa, Pembersihan Card Gatepass Dashboard Beranda Siswa, serta Pratinjau Resolusi Penuh & Unduh Berkas Remote HP Siswa.',
            isForceUpdate: true
        },
        create: {
            id: 'latest',
            versionCode: 94,
            versionName: '2.8.84',
            downloadUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: 26.5,
            releaseNotes: 'Pembaruan Resmi Smart CBT v2.8.84 (Build 94): Standar Wajib Akurasi GPS Presisi Maksimal 15 Meter & Anti-Fake GPS Terintegrasi, Optimalisasi Manajemen Titik GPS Kelas (Live Lock Satelit & Fallback Multi-Perangkat), Perbaikan Layanan Digital Gatepass Guru BK Portal & APK Siswa, Pembersihan Card Gatepass Dashboard Beranda Siswa, serta Pratinjau Resolusi Penuh & Unduh Berkas Remote HP Siswa.',
            isForceUpdate: true
        }
    });
    console.log('AppVersionConfig updated successfully to 2.8.74 (code 84)');
}

main().catch(console.error).finally(() => prisma.$disconnect());
