const { PrismaClient } = require('C:/APK/backend/node_modules/@prisma/client');
const prisma = new PrismaClient();

async function checkReq() {
    const r = await prisma.copyRequest.findUnique({
        where: { id: 'a79204f2-7eba-46ae-bd5c-93db249c26e1' }
    });
    console.log('CopyRequest:', JSON.stringify(r, null, 2));
    await prisma.$disconnect();
}
checkReq().catch(console.error);
