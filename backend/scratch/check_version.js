const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const list = await prisma.appVersionConfig.findMany();
    console.log('appVersionConfig records:', list);
}

main().catch(console.error).finally(() => prisma.$disconnect());
