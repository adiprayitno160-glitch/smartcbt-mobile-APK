const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const leaves = await prisma.studentLeaveRequest.findMany({
        where: {
            OR: [
                { studentName: { contains: 'FARA' } },
                { studentId: '187a9c52-beac-44ef-8204-1b88d4595c3f' }
            ]
        }
    });
    console.log("Leaves for FARA:", leaves);

    // Check device files or device sessions for student
    const dev = await prisma.deviceSession.findMany({
        where: { userId: '187a9c52-beac-44ef-8204-1b88d4595c3f' }
    });
    console.log("Device session for Fara:", dev);

    await prisma.$disconnect();
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
