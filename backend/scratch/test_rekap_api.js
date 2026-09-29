const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function testRekap() {
  const dateStr = '2026-09-23';
  const [y, m, d] = dateStr.split('-').map(Number);
  const startOfDay = new Date(y, m - 1, d, 0, 0, 0, 0);
  const endOfDay = new Date(y, m - 1, d, 23, 59, 59, 999);

  console.log('Querying date range:', startOfDay.toISOString(), 'to', endOfDay.toISOString());

  const logs = await prisma.attendance.findMany({
    where: {
      scanTime: { gte: startOfDay, lte: endOfDay }
    },
    include: { user: true }
  });

  console.log('Total attendance logs found on 2026-09-23:', logs.length);

  // Group by user
  const byUser = {};
  logs.forEach(l => {
    const uname = l.user?.name || l.userId;
    if (!byUser[uname]) byUser[uname] = [];
    byUser[uname].push(l);
  });

  console.log('Distinct users who scanned:', Object.keys(byUser).length);
  for (const [name, ulogs] of Object.entries(byUser)) {
    const inRec = ulogs.find(l => l.type === 'GATE_IN');
    const outRec = ulogs.find(l => l.type === 'GATE_OUT');
    console.log(`- ${name} (${ulogs[0].user?.className}): IN=${inRec ? inRec.status : 'NO'}, OUT=${outRec ? outRec.status : 'NO'}`);
  }
}

testRekap().finally(() => prisma.$disconnect());
