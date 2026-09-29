const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    console.log('Updating AppVersionConfig & AppVersionRelease to 2.8.75 (Build 85)...');
    await prisma.appVersionConfig.upsert({
        where: { id: 'latest' },
        update: {
            versionCode: 85,
            versionName: '2.8.75',
            downloadUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: 28.5,
            releaseNotes: 'Pembaruan Resmi Smart CBT v2.8.75 (Build 85): Sistem Izin Pulang & Digital Exit Pass Meja BK (Two-Step Verification), Anti-Bolos Terintegrasi Presensi KBM, Foto Profil Siswa pada Popup Hasil Presensi, Virtual Bookshelf Perpustakaan Digital, dan e-Voting OSIS Multi-Color.',
            isForceUpdate: true
        },
        create: {
            id: 'latest',
            versionCode: 85,
            versionName: '2.8.75',
            downloadUrl: '/uploads/smartcbt-latest.apk',
            fileSizeMb: 28.5,
            releaseNotes: 'Pembaruan Resmi Smart CBT v2.8.75 (Build 85): Sistem Izin Pulang & Digital Exit Pass Meja BK (Two-Step Verification), Anti-Bolos Terintegrasi Presensi KBM, Foto Profil Siswa pada Popup Hasil Presensi, Virtual Bookshelf Perpustakaan Digital, dan e-Voting OSIS Multi-Color.',
            isForceUpdate: true
        }
    });

    const existingRelease = await prisma.appVersionRelease.findFirst({
        where: { versionCode: 85 }
    });

    if (existingRelease) {
        await prisma.appVersionRelease.update({
            where: { id: existingRelease.id },
            data: {
                nomorVersi: '2.8.75',
                status: 'ACTIVE',
                persentaseRollout: 100,
                isForceUpdate: true,
                fileApkUrl: '/uploads/smartcbt-latest.apk',
                fileSizeMb: 28.5,
                changelog: 'Sistem Izin Pulang & Digital Exit Pass Meja BK, Anti-Bolos KBM, Foto Profil Presensi, Virtual Bookshelf, dan e-Voting OSIS Multi-Color.'
            }
        });
    } else {
        await prisma.appVersionRelease.create({
            data: {
                nomorVersi: '2.8.75',
                versionCode: 85,
                changelog: 'Sistem Izin Pulang & Digital Exit Pass Meja BK, Anti-Bolos KBM, Foto Profil Presensi, Virtual Bookshelf, dan e-Voting OSIS Multi-Color.',
                fileApkUrl: '/uploads/smartcbt-latest.apk',
                fileSizeMb: 28.5,
                checksumSha256: 'PENDING_BUILD_85',
                isForceUpdate: true,
                persentaseRollout: 100,
                status: 'ACTIVE'
            }
        });
    }

    console.log('✅ Version bumped to 2.8.75 (Build 85) in database tables');
}

main().catch(console.error).finally(() => prisma.$disconnect());
