const { PrismaClient } = require('@prisma/client');
const p = new PrismaClient();
async function main() {
  const records = await p.attendance.findMany({
    take: 10,
    orderBy: { scanTime: 'desc' },
    select: { id: true, scanTime: true, lat: true, lng: true, note: true }
  });
  console.log(JSON.stringify(records, null, 2));
}
main().finally(() => p.$disconnect());
