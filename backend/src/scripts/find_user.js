const { PrismaClient } = require('@prisma/client');
const bcrypt = require('bcryptjs');
const prisma = new PrismaClient();

async function main() {
    console.log("--- Searching for 604442 or GURU-604442 ---");
    const exact = await prisma.user.findMany({
        where: {
            OR: [
                { username: { contains: '604442' } },
                { name: { contains: '604442' } }
            ]
        }
    });
    console.log("Match 604442:", exact);

    const guruUsers = await prisma.user.findMany({
        where: {
            OR: [
                { username: { startsWith: 'GURU' } },
                { username: { startsWith: 'guru' } }
            ]
        },
        select: { id: true, username: true, name: true, role: true, password: true }
    });
    console.log("\nUsers starting with guru/GURU:");
    for (const u of guruUsers) {
        let pass = 'unknown';
        for (const p of ['password', 'password123', 'guru123', 'admin123', '123456']) {
            if (bcrypt.compareSync(p, u.password)) {
                pass = p;
                break;
            }
        }
        console.log(`- Username: "${u.username}", Name: "${u.name}", Role: "${u.role}", Password: "${pass}"`);
    }

    const allTeachers = await prisma.user.findMany({
        where: { role: 'TEACHER' },
        take: 10,
        select: { id: true, username: true, name: true, role: true, password: true }
    });
    console.log("\nSample Teachers (first 10):");
    for (const u of allTeachers) {
        let pass = 'unknown';
        for (const p of ['password', 'password123', 'guru123', 'admin123', '123456']) {
            if (bcrypt.compareSync(p, u.password)) {
                pass = p;
                break;
            }
        }
        console.log(`- Username: "${u.username}", Name: "${u.name}", Role: "${u.role}", Password: "${pass}"`);
    }
}

main().catch(console.error).finally(() => prisma.$disconnect());
