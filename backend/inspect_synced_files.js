const { PrismaClient } = require('C:/APK/backend/node_modules/@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const s = await prisma.deviceSession.findUnique({
        where: { deviceAndroidId: '4ebca55c1f528b2b' }
    });
    
    // Sample 20 files
    const sampleFiles = await prisma.deviceFile.findMany({
        where: { deviceId: s.id },
        take: 20,
        orderBy: { lastModified: 'desc' }
    });
    console.log('Sample 20 files:');
    for (const f of sampleFiles) {
        console.log(`- [${f.category}] ${f.fileName} (${f.fileSizeHuman}) -> ${f.filePath}`);
    }

    // Categories distribution
    const categories = await prisma.deviceFile.groupBy({
        by: ['category'],
        where: { deviceId: s.id },
        _count: true
    });
    console.log('\nCategories:', JSON.stringify(categories, null, 2));

    await prisma.$disconnect();
}
main().catch(console.error);
