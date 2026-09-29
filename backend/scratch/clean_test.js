const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();
prisma.deviceSession.deleteMany({ where: { deviceAndroidId: 'test_verify_9206833c43ae0fa7' } })
    .then(r => console.log('Cleaned test records:', r))
    .finally(() => prisma.$disconnect());
