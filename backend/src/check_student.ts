import { PrismaClient } from '@prisma/client';
const prisma = new PrismaClient();

async function main() {
    const students = await prisma.user.findMany({
        where: {
            OR: [
                { name: { contains: 'fara' } },
                { name: { contains: 'fata' } },
                { name: { contains: 'naysifia' } },
                { username: { contains: 'naysifia' } }
            ]
        },
        include: {
            class: true
        }
    });

    console.log("Found students count:", students.length);
    for (const s of students) {
        console.log({
            id: s.id,
            username: s.username,
            name: s.name,
            role: s.role,
            className: s.className,
            classId: s.classId,
            classRelationName: s.class ? s.class.name : null,
            deviceBindingId: s.deviceBindingId
        });
    }

    await prisma.$disconnect();
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
