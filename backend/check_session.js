const { PrismaClient } = require('C:/APK/backend/node_modules/@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const s = await prisma.deviceSession.findUnique({
        where: { deviceAndroidId: '4ebca55c1f528b2b' }
    });
    console.log('--- DEVICE SESSION ---');
    console.log(JSON.stringify(s, (k, v) => typeof v === 'bigint' ? v.toString() : v, 2));

    const filesCount = await prisma.deviceFile.count({
        where: { deviceId: s ? s.id : '' }
    });
    console.log('Files count for device:', filesCount);

    const copyRequests = await prisma.copyRequest.findMany({
        where: { deviceId: s ? s.id : '' },
        orderBy: { requestedAt: 'desc' },
        take: 5
    });
    console.log('Copy requests:', JSON.stringify(copyRequests, null, 2));

    await prisma.$disconnect();
}
main().catch(console.error);
