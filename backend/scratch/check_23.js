const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const allAtt = await prisma.attendance.findMany({
    orderBy: { scanTime: 'desc' },
    take: 30,
    include: { user: true }
  });
  console.log('Recent 30 attendances:');
  allAtt.forEach(a => {
    console.log(a.id, a.user?.name, a.user?.className, a.type, a.status, a.scanTime.toISOString());
  });
}

main().catch(console.error).finally(() => prisma.$disconnect());
