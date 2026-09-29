const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const exams = await prisma.exam.findMany({
        include: {
            subject: true,
            _count: { select: { questions: true, studentExams: true } }
        }
    });
    console.log('Total exams in DB:', exams.length);
    console.log('Sample exam:', exams[0] || 'No exams');
}

main().catch(console.error).finally(() => prisma.$disconnect());
