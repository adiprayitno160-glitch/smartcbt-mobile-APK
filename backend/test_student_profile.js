const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    const user = await prisma.user.findUnique({
      where: { username: '132560979' },
      select: {
        id: true,
        username: true,
        name: true,
        role: true,
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
        profilePicUrl: true,
        homeroomTeacher: true,
        counselorTeacher: true
      }
    });

    console.log("getStudentProfile query result:");
    console.log(user);

    await prisma.$disconnect();
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
