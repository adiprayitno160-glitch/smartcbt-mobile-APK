const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    console.log("=== CHECK ALL USERS WITH FARA / NAYSIF ===");
    const users = await prisma.user.findMany({
        where: {
            OR: [
                { name: { contains: 'FARA' } },
                { name: { contains: 'fara' } },
                { name: { contains: 'NAYSIF' } },
                { name: { contains: 'naysif' } }
            ]
        }
    });
    for (const u of users) {
        console.log(`User: ${u.username} | Name: ${u.name} | Role: ${u.role} | Class: ${u.className} | ClassId: ${u.classId}`);
    }

    console.log("\n=== CHECK ALL CLASSES ===");
    const classes = await prisma.class.findMany({
        select: { id: true, name: true, counselorName: true }
    });
    for (const c of classes) {
        console.log(`Class: ${c.name} (ID: ${c.id}) | BK: ${c.counselorName}`);
    }

    console.log("\n=== CHECK ATTENDANCES FOR FARA ===");
    const att = await prisma.attendance.findMany({
        where: {
            user: {
                OR: [
                    { name: { contains: 'FARA' } },
                    { username: '132560979' }
                ]
            }
        },
        take: 5
    });
    console.log("Attendances count:", att.length);

    console.log("\n=== CHECK STUDENT LEAVE / GATEPASS FOR FARA ===");
    const leaves = await prisma.studentLeaveRequest.findMany({
        where: {
            student: {
                OR: [
                    { name: { contains: 'FARA' } },
                    { username: '132560979' }
                ]
            }
        },
        take: 5
    });
    console.log("Leaves count:", leaves.length);

    console.log("\n=== CHECK CLASS VII-K STUDENTS ===");
    const viik = await prisma.user.findMany({
        where: { className: 'VII-K' },
        select: { username: true, name: true }
    });
    console.log("VII-K Students count:", viik.length);
    for (const s of viik) {
        if (s.name.toUpperCase().includes('FARA') || s.name.toUpperCase().includes('NAYSIF') || s.name.toUpperCase().includes('AZZAHRA')) {
            console.log("MATCH IN VII-K:", s);
        }
    }

    await prisma.$disconnect();
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
