const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function testSchedule() {
    const exam = await prisma.exam.findFirst();
    if (!exam) {
        console.log('No exam found');
        return;
    }
    console.log('Testing exam:', exam.id, exam.title);

    // Simulate what scheduleNationalExam does:
    const updateData = {
        title: exam.title,
        durationMinutes: 90,
        minDurationMinutes: 30,
        assignedClasses: 'ALL',
        sessionName: 'Sesi 1',
        executionDate: '2026-09-21',
        startTimeStr: '08:00',
        endTimeStr: '10:00',
        isTokenActive: true,
        randomizeQuestions: true,
        randomizeOptions: true,
        showScoreToStudent: false,
        maxStrikes: 3
    };

    try {
        const res = await prisma.exam.update({
            where: { id: exam.id },
            data: updateData
        });
        console.log('Success updating exam schedule:', res.title, 'token:', res.token);
    } catch(err) {
        console.error('Error updating exam schedule:', err);
    }
}

testSchedule().finally(() => prisma.$disconnect());
