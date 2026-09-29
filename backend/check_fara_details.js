const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const student = await prisma.user.findFirst({
        where: { username: '132560979' }
    });
    console.log("Student details:", student);

    const auditLogs = await prisma.auditLog.findMany({
        where: {
            OR: [
                { details: { contains: '132560979' } },
                { details: { contains: 'FARA' } },
                { details: { contains: 'NAYSIFA' } }
            ]
        },
        orderBy: { timestamp: 'desc' },
        take: 10
    });
    console.log("\nAudit Logs count:", auditLogs.length);
    for (const log of auditLogs) {
        console.log(log.timestamp, log.action, log.details);
    }

    const deviceSessions = await prisma.deviceSession.findMany({
        where: { userId: student.id }
    });
    console.log("\nDevice sessions for student:", deviceSessions);

    // Let's also check all students in class VII-K vs VII-A
    const countVIIA = await prisma.user.count({ where: { className: 'VII-A' } });
    const countVIIK = await prisma.user.count({ where: { className: 'VII-K' } });
    console.log("\nTotal students in VII-A:", countVIIA);
    console.log("Total students in VII-K:", countVIIK);

    await prisma.$disconnect();
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
