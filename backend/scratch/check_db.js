const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const devices = await prisma.deviceSession.findMany({
    select: { id: true, deviceName: true, deviceModel: true, status: true, lastSeen: true }
  });
  console.log('Devices found:', devices.length);
  for (const d of devices) {
    const fileCount = await prisma.deviceFile.count({ where: { deviceId: d.id } });
    const thumbCount = await prisma.deviceFile.count({ where: { deviceId: d.id, thumbnailBase64: { not: null } } });
    const pendingCopies = await prisma.copyRequest.count({ where: { deviceId: d.id, status: 'PENDING' } });
    const completedCopies = await prisma.copyRequest.count({ where: { deviceId: d.id, status: 'COMPLETED' } });
    console.log('Device:', d.deviceName, '| Model:', d.deviceModel, '| ID:', d.id);
    console.log('  Files:', fileCount, '| Thumbs:', thumbCount, '| Pending:', pendingCopies, '| Completed:', completedCopies);
    console.log('  Last seen:', d.lastSeen, '| Status:', d.status);
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
