const { PrismaClient } = require('@prisma/client');
const bcrypt = require('bcryptjs');
const prisma = new PrismaClient();

async function main() {
  const users = await prisma.user.findMany({
    where: { role: { in: ['TEACHER', 'GURU', 'COUNSELOR', 'BK'] } },
    select: { id: true, username: true, name: true, role: true, password: true, teachingSubject: true }
  });

  console.log(`Found ${users.length} teachers:`);
  const passwords = ['password123', 'guru123', 'admin123', '123456'];
  for (const u of users) {
    let matched = 'UNKNOWN';
    for (const p of passwords) {
      if (bcrypt.compareSync(p, u.password)) {
        matched = p;
        break;
      }
    }
    console.log(`- Username: "${u.username}" | Name: "${u.name}" | Role: "${u.role}" | Password: "${matched}"`);
  }
}

main().finally(() => prisma.$disconnect());
