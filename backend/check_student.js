const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const students = await prisma.user.findMany({
        where: {
            OR: [
                { name: { contains: 'fara' } },
                { name: { contains: 'fata' } },
                { name: { contains: 'naysifia' } },
                { username: { contains: 'naysifia' } },
                { name: { contains: 'Fara' } },
                { name: { contains: 'Fata' } }
            ]
        },
        include: {
            class: true
        }
    });

    console.log("Found students count:", students.length);
    for (const s of students) {
        console.log("--- STUDENT ---");
        console.log("ID:", s.id);
        console.log("Username:", s.username);
        console.log("Name:", s.name);
        console.log("Role:", s.role);
        console.log("User.className:", s.className);
        console.log("User.classId:", s.classId);
        console.log("classRelation.name:", s.class ? s.class.name : null);
        console.log("classRelation.id:", s.class ? s.class.id : null);
        console.log("deviceBindingId:", s.deviceBindingId);
    }

    await prisma.$disconnect();
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
