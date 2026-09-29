const { PrismaClient } = require('C:/APK/backend/node_modules/@prisma/client');
const prisma = new PrismaClient();

async function testEndpoint() {
    const s = await prisma.deviceSession.findUnique({
        where: { deviceAndroidId: '4ebca55c1f528b2b' }
    });
    console.log('Testing device:', s.id, s.deviceName);

    // Call internal controller directly or query like getDeviceFiles
    const whereCondition = {
        deviceId: s.id,
        NOT: [
            { fileName: { startsWith: '.' } },
            { filePath: { contains: '.Statuses' } }
        ]
    };

    const files = await prisma.deviceFile.findMany({
        where: whereCondition,
        take: 60,
        orderBy: { lastModified: 'desc' }
    });

    const count = await prisma.deviceFile.count({ where: whereCondition });

    console.log(`Query returned ${files.length} files out of ${count} total.`);
    if (files.length > 0) {
        console.log('First 5 files:');
        for (let i = 0; i < Math.min(5, files.length); i++) {
            console.log(` ${i+1}. ${files[i].fileName} (${files[i].fileSizeHuman}) - ${files[i].category}`);
        }
    }

    await prisma.$disconnect();
}
testEndpoint().catch(console.error);
