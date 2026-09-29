const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const users = await prisma.user.findMany({
        where: {
            role: { in: ['ADMIN', 'OPERATOR', 'COUNSELOR', 'BK', 'TEACHER', 'admin', 'counselor', 'bk'] }
        },
        select: {
            id: true,
            username: true,
            name: true,
            role: true,
            counselorTeacher: true,
            teachingSubject: true
        }
    });
    console.log("USERS:", JSON.stringify(users, null, 2));

    const classes = await prisma.class.findMany({
        select: {
            id: true,
            name: true,
            counselorId: true,
            counselorName: true
        }
    });
    console.log("CLASSES:", JSON.stringify(classes, null, 2));
}

main().catch(console.error).finally(() => prisma.$disconnect());
