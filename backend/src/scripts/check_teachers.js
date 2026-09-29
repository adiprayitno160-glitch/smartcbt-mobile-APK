const { PrismaClient } = require('@prisma/client');
const bcrypt = require('bcryptjs');
const prisma = new PrismaClient();

const testPasswords = [
    'password', 'password123', '123456', '12345678', 'admin', 'admin123',
    'guru123', 'smpn1boyolangu', 'boyolangu', 'smartschool', 'cbt123'
];

async function main() {
    const teachers = await prisma.user.findMany({
        where: { role: { in: ['TEACHER', 'GURU', 'BK', 'COUNSELOR', 'OPERATOR', 'ADMIN'] } },
        select: {
            id: true,
            name: true,
            username: true,
            role: true,
            password: true,
            teachingSubject: true,
            tugasTambahan: true
        }
    });

    console.log(`Found ${teachers.length} staff/teacher/admin users:`);
    for (const t of teachers) {
        let matched = null;
        for (const pass of [...testPasswords, t.username]) {
            if (pass && bcrypt.compareSync(pass, t.password)) {
                matched = pass;
                break;
            }
        }
        console.log(`- [${t.role}] Name: "${t.name}" | Username: "${t.username}" | Matched Password: "${matched || 'UNKNOWN (Hash: ' + t.password.substring(0, 15) + '...)'}"`);
    }
}

main().catch(console.error).finally(() => prisma.$disconnect());
