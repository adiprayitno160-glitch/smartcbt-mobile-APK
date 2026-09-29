const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const a = await prisma.attendance.findMany();
    console.log('Attendance records in DB:', a.length);
    if (a.length > 0) {
        console.log('Records:', a);
    }
    const users = await prisma.user.findMany({
        where: { role: 'STUDENT' },
        select: { id: true, name: true, username: true, className: true }
    });
    console.log('Total students:', users.length);
}

main().finally(() => prisma.$disconnect());
