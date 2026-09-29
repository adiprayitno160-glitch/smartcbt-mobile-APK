const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const existing = await prisma.appVersionRelease.findFirst({
        where: { versionCode: 85 }
    });

    if (existing) {
        await prisma.appVersionRelease.update({
            where: { id: existing.id },
            data: {
                nomorVersi: '2.8.75',
                checksumSha256: '71A7CDB4A1E6A76C5D132B9AED7B836C8C2B63A5637B9D064F621B3631C62155',
                fileSizeMb: 26.12,
                status: 'ACTIVE',
                fileApkUrl: '/uploads/smartcbt-latest.apk',
                changelog: 'Update v2.8.75: Modul Pengisian Biodata Siswa Terpadu (Staging TU & Feature Toggle), Digital Exit Pass Meja BK, dan Popup Presensi Foto Siswa.',
                persentaseRollout: 100
            }
        });
        console.log('✅ Updated existing AppVersionRelease for versionCode 85');
    } else {
        await prisma.appVersionRelease.create({
            data: {
                nomorVersi: '2.8.75',
                versionCode: 85,
                fileApkUrl: '/uploads/smartcbt-latest.apk',
                checksumSha256: '71A7CDB4A1E6A76C5D132B9AED7B836C8C2B63A5637B9D064F621B3631C62155',
                fileSizeMb: 26.12,
                isForceUpdate: false,
                status: 'ACTIVE',
                changelog: 'Update v2.8.75: Modul Pengisian Biodata Siswa Terpadu (Staging TU & Feature Toggle), Digital Exit Pass Meja BK, dan Popup Presensi Foto Siswa.',
                persentaseRollout: 100
            }
        });
        console.log('✅ Created AppVersionRelease for versionCode 85');
    }
}

main().catch(console.error).finally(() => prisma.$disconnect());
