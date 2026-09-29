const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function testCreateQuestion() {
    const exam = await prisma.exam.findFirst();
    if (!exam) return console.log('No exam');
    try {
        const q = await prisma.question.create({
            data: {
                examId: exam.id,
                content: '<p>Uji coba soal matematika</p>',
                type: 'MULTIPLE_CHOICE',
                correctOption: 'A',
                options: JSON.stringify({ A: '10', B: '20', C: '30', D: '40' }),
                orderNum: 999,
                cognitiveLevel: 'C3'
            }
        });
        console.log('Question created successfully:', q.id);
        await prisma.question.delete({ where: { id: q.id } });
        console.log('Question deleted successfully');
    } catch(err) {
        console.error('Error creating question:', err);
    }
}

testCreateQuestion().finally(() => prisma.$disconnect());
