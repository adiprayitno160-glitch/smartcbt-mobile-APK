const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const onlineThreshold = new Date(Date.now() - 3 * 60 * 1000);
  const devices = await prisma.deviceSession.findMany({
    include: { user: true, _count: { select: { files: true } } },
    orderBy: { lastSeen: 'desc' }
  });

  console.log('Total devices in DB:', devices.length);
  const online = devices.filter(d => d.lastSeen > onlineThreshold && d.status !== 'BLOCKED');
  console.log('Online devices count:', online.length);
  for (const d of online) {
    console.log('ONLINE:', d.deviceName, '| user:', d.user?.name, '| class:', d.user?.className, '| files:', d._count.files, '| id:', d.id);
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
