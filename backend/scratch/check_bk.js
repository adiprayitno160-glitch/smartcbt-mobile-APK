const { PrismaClient } = require('@prisma/client');
const bcrypt = require('bcryptjs');
const prisma = new PrismaClient();

async function main() {
    const bkUser = await prisma.user.findUnique({ where: { username: 'bk' } });
    console.log("BK USER:", bkUser);
    if (bkUser) {
        console.log("bk / bk:", bcrypt.compareSync('bk', bkUser.password));
        console.log("bk / bk123:", bcrypt.compareSync('bk123', bkUser.password));
        console.log("bk / password123:", bcrypt.compareSync('password123', bkUser.password));
    }
}

main().catch(console.error).finally(() => prisma.$disconnect());
