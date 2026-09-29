const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const versions = await prisma.deviceSession.groupBy({
        by: ['appVersion'],
        _count: { appVersion: true }
    });
    console.log('App versions currently installed on devices:', versions);
}

main().catch(console.error).finally(() => prisma.$disconnect());
