const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const adminUsers = await prisma.user.findMany({
        where: {
            OR: [
                { username: { contains: 'admin' } },
                { role: { in: ['ADMIN', 'OPERATOR', 'admin', 'operator'] } }
            ]
        },
        select: {
            id: true,
            username: true,
            name: true,
            role: true,
            isActive: true,
            password: true
        }
    });
    console.log("ADMIN USERS:", JSON.stringify(adminUsers, null, 2));
}

main().catch(console.error).finally(() => prisma.$disconnect());
