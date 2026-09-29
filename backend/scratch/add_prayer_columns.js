const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const cols = await prisma.$queryRawUnsafe('PRAGMA table_info(PrayerClassSchedule)');
    const colNames = cols.map(c => c.name);

    if (!colNames.includes('wudhuTime')) {
        await prisma.$executeRawUnsafe('ALTER TABLE "PrayerClassSchedule" ADD COLUMN "wudhuTime" TEXT DEFAULT "11:45"');
        console.log('Added wudhuTime column');
    }
    if (!colNames.includes('scanStartTime')) {
        await prisma.$executeRawUnsafe('ALTER TABLE "PrayerClassSchedule" ADD COLUMN "scanStartTime" TEXT DEFAULT "12:00"');
        console.log('Added scanStartTime column');
    }
    if (!colNames.includes('scanEndTime')) {
        await prisma.$executeRawUnsafe('ALTER TABLE "PrayerClassSchedule" ADD COLUMN "scanEndTime" TEXT DEFAULT "12:30"');
        console.log('Added scanEndTime column');
    }

    const updatedCols = await prisma.$queryRawUnsafe('PRAGMA table_info(PrayerClassSchedule)');
    console.log('Updated Cols:', updatedCols.map(c => c.name));
}

main().catch(console.error).finally(() => prisma.$disconnect());
