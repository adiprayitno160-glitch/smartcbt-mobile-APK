import { PrismaClient } from '@prisma/client';
import bcrypt from 'bcryptjs';

const prisma = new PrismaClient();

async function main() {
  const hashedPassword = await bcrypt.hash('password123', 10);

  // 1. Create Class
  const classA = await prisma.class.upsert({
    where: { name: 'X-RPL-1' },
    update: {},
    create: {
      name: 'X-RPL-1',
      barcodeCode: 'CLS-XRPL1-9923',
    },
  });

  // 2. Create Users
  // Admin
  await prisma.user.upsert({
    where: { username: 'admin' },
    update: {},
    create: {
      username: 'admin',
      password: hashedPassword,
      name: 'Super Admin',
      role: 'ADMIN',
    },
  });

  // Operator
  await prisma.user.upsert({
    where: { username: 'operator' },
    update: {},
    create: {
      username: 'operator',
      password: hashedPassword,
      name: 'Operator CBT',
      role: 'OPERATOR',
    },
  });

  // Teacher
  await prisma.user.upsert({
    where: { username: 'guru01' },
    update: {},
    create: {
      username: 'guru01',
      password: hashedPassword,
      name: 'Budi Santoso, S.Pd',
      role: 'TEACHER',
    },
  });

  // Student
  await prisma.user.upsert({
    where: { username: '1234567890' },
    update: {},
    create: {
      username: '1234567890',
      password: hashedPassword,
      name: 'Ahmad Siswa',
      role: 'STUDENT',
      classId: classA.id,
      rfidChipUid: 'A1:B2:C3:D4',
      parentPhone: '08123456789',
    },
  });

  console.log('Database seeded successfully!');
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
