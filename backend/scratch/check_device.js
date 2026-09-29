const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const devices = await prisma.deviceSession.findMany({
        take: 5,
        select: { id: true, userId: true, user: { select: { username: true, role: true } } }
    });
    console.log('Sample devices:', devices);
}

main().catch(console.error).finally(() => prisma.$disconnect());
