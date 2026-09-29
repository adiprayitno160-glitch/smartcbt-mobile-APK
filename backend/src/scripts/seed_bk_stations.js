const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    console.log('Seeding default BK stations...');
    const count = await prisma.bkStation.count();
    if (count === 0) {
        await prisma.bkStation.create({
            data: {
                stationName: 'Meja Konseling BK 1 (Ruang Utama)',
                qrSecretToken: 'BK-STATION-ROOM1-9A8B7C6D',
                isActive: true
            }
        });
        await prisma.bkStation.create({
            data: {
                stationName: 'Meja Konseling BK 2 (Koordinator BK)',
                qrSecretToken: 'BK-STATION-ROOM2-4E3F2A1B',
                isActive: true
            }
        });
        console.log('✅ Created 2 default BK stations: BK-STATION-ROOM1-9A8B7C6D & BK-STATION-ROOM2-4E3F2A1B');
    } else {
        const stations = await prisma.bkStation.findMany();
        console.log(`ℹ️ Already has ${stations.length} BK stations:`, stations.map(s => s.qrSecretToken));
    }
}

main().catch(console.error).finally(() => prisma.$disconnect());
