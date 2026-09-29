const { PrismaClient } = require('C:/APK/backend/node_modules/@prisma/client');
const prisma = new PrismaClient();

async function findCandidates() {
    const s = await prisma.deviceSession.findUnique({
        where: { deviceAndroidId: '4ebca55c1f528b2b' }
    });
    
    // Find small real images or files from today
    const candidates = await prisma.deviceFile.findMany({
        where: {
            deviceId: s.id,
            category: 'IMAGE',
            fileSize: { lt: 500000 } // < 500 KB for fast test
        },
        take: 5,
        orderBy: { lastModified: 'desc' }
    });

    console.log('Candidates to test download:');
    for (const c of candidates) {
        console.log(`ID: ${c.id}`);
        console.log(`File: ${c.fileName} (${c.fileSizeHuman})`);
        console.log(`Path: ${c.filePath}`);
        console.log('---');
    }

    await prisma.$disconnect();
}
findCandidates().catch(console.error);
