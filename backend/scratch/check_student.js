const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const student = await prisma.user.findFirst({
        where: { role: 'STUDENT' },
        select: {
            id: true,
            username: true,
            name: true,
            className: true,
            nisn: true,
            nis: true,
            gender: true,
            religion: true,
            pob: true,
            dob: true,
            address: true,
            fatherName: true,
            motherName: true,
            parentPhone: true,
            bloodType: true,
            allergies: true,
            points: true,
            profilePicUrl: true
        }
    });
    console.log("SAMPLE STUDENT:", student);
}

main().catch(console.error).finally(() => prisma.$disconnect());
