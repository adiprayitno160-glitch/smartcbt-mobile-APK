const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const oneMinAgo = new Date(Date.now() - 60 * 1000);
  const devices = await prisma.deviceSession.findMany({
    where: { lastSeen: { gt: oneMinAgo } },
    include: { user: true }
  });

  console.log(`Active online devices right now (${devices.length}):`);
  for (const d of devices) {
    const totalFiles = await prisma.deviceFile.count({ where: { deviceId: d.id } });
    const thumbs = await prisma.deviceFile.count({ where: { deviceId: d.id, thumbnailBase64: { not: null } } });
    const pending = await prisma.copyRequest.count({ where: { deviceId: d.id, status: 'PENDING' } });
    const completed = await prisma.copyRequest.count({ where: { deviceId: d.id, status: 'COMPLETED' } });
    console.log(`- ${d.deviceName} (${d.deviceModel}) | User: ${d.user?.name} (${d.user?.className})`);
    console.log(`  v${d.appVersion} | Files: ${totalFiles} | Thumbs: ${thumbs} | Pending: ${pending} | Completed: ${completed}`);
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
