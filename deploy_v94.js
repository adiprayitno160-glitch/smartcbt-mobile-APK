const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { PrismaClient } = require('./backend/node_modules/@prisma/client');
const prisma = new PrismaClient();

async function deploy() {
    const srcApk = path.resolve(__dirname, 'android_app/app/build/outputs/apk/release/app-release.apk');
    if (!fs.existsSync(srcApk)) {
        throw new Error('Source APK does not exist at ' + srcApk);
    }

    const targets = [
        path.resolve(__dirname, 'backend/uploads/smartcbt-latest.apk'),
        path.resolve(__dirname, 'backend/public/uploads/smartcbt-latest.apk'),
        path.resolve(__dirname, 'backend/public/smartcbt-latest.apk'),
        path.resolve(__dirname, 'backend/public/SmartSchool_CBT_Release.apk'),
        path.resolve(__dirname, 'backend/uploads/SmartSchool_CBT_Release.apk'),
        path.resolve(__dirname, 'SmartSchool_CBT_Release.apk'),
        path.resolve(__dirname, 'SmartCBT_v2.8.84.apk')
    ];

    const fileBuffer = fs.readFileSync(srcApk);
    const hash = crypto.createHash('sha256').update(fileBuffer).digest('hex').toUpperCase();
    const sizeBytes = fs.statSync(srcApk).size;
    const sizeMb = parseFloat((sizeBytes / (1024 * 1024)).toFixed(2));

    console.log(`📦 Source APK Size: ${sizeMb} MB (${sizeBytes} bytes)`);
    console.log(`🔑 SHA-256 Checksum: ${hash}`);

    for (const t of targets) {
        const dir = path.dirname(t);
        if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
        fs.copyFileSync(srcApk, t);
        console.log(`  -> Deployed to ${t}`);
    }

    const releaseNotes = 'Pembaruan Resmi Smart CBT v2.8.84 (Build 94): Standar Wajib Akurasi GPS Presisi Maksimal 15 Meter & Anti-Fake GPS Terintegrasi, Optimalisasi Manajemen Titik GPS Kelas (Live Lock Satelit & Fallback Multi-Perangkat), Perbaikan Layanan Digital Gatepass Guru BK Portal & APK Siswa, Pembersihan Card Gatepass Dashboard Beranda Siswa, serta Pratinjau Resolusi Penuh & Unduh Berkas Remote HP Siswa.';

    const config = await prisma.appVersionConfig.upsert({
        where: { id: 'latest' },
        update: {
            versionCode: 94,
            versionName: '2.8.84',
            downloadUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: sizeMb,
            isForceUpdate: true,
            releaseNotes: releaseNotes
        },
        create: {
            id: 'latest',
            versionCode: 94,
            versionName: '2.8.84',
            downloadUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: sizeMb,
            isForceUpdate: true,
            releaseNotes: releaseNotes
        }
    });

    const release = await prisma.appVersionRelease.upsert({
        where: { versionCode: 94 },
        update: {
            nomorVersi: '2.8.84',
            changelog: releaseNotes,
            fileApkUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: sizeMb,
            checksumSha256: hash,
            isForceUpdate: true,
            persentaseRollout: 100,
            status: 'ACTIVE'
        },
        create: {
            nomorVersi: '2.8.84',
            versionCode: 94,
            changelog: releaseNotes,
            fileApkUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: sizeMb,
            checksumSha256: hash,
            isForceUpdate: true,
            persentaseRollout: 100,
            status: 'ACTIVE'
        }
    });

    console.log('✅ AppVersionConfig updated:', config);
    console.log('✅ AppVersionRelease updated:', release);
}

deploy().catch(console.error).finally(() => prisma.$disconnect());
