const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function flagBk() {
    const updated = await prisma.user.updateMany({
        where: {
            OR: [
                { role: 'COUNSELOR' },
                { tugasTambahan: { contains: 'BK' } },
                { teachingSubject: { contains: 'BK' } },
                { teachingSubject: { contains: 'BIMBINGAN' } },
                { name: { contains: 'BK' } }
            ]
        },
        data: { isBk: true }
    });
    console.log('Flagged BK teachers count:', updated.count);

    const bkUsers = await prisma.user.findMany({
        where: { isBk: true },
        select: { id: true, name: true, role: true, isBk: true }
    });
    console.log('BK Users flagged:', bkUsers);
    await prisma.$disconnect();
}

flagBk().catch(console.error);
