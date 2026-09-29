const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const updated = await prisma.appVersionConfig.upsert({
        where: { id: 'latest' },
        update: {
            versionCode: 75,
            versionName: '2.8.65',
            downloadUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: 27.2,
            releaseNotes: 'Pembaruan Resmi v2.8.65 (Build 75): Login default NISN siswa & orang tua, pembaruan kalender absensi ringkas, pembersihan total e-file dummy, pesan balasan BK langsung terhubung ke APK, preview video & foto remote devices, dan dialog akses modern.',
            isForceUpdate: true
        },
        create: {
            id: 'latest',
            versionCode: 75,
            versionName: '2.8.65',
            downloadUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: 27.2,
            releaseNotes: 'Pembaruan Resmi v2.8.65 (Build 75): Login default NISN siswa & orang tua, pembaruan kalender absensi ringkas, pembersihan total e-file dummy, pesan balasan BK langsung terhubung ke APK, preview video & foto remote devices, dan dialog akses modern.',
            isForceUpdate: true
        }
    });
    console.log('Successfully updated appVersionConfig in DB:', updated);
}

main().catch(console.error).finally(() => prisma.$disconnect());
