const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const d = await prisma.deviceSession.findFirst({
    where: {
      OR: [
        { deviceModel: { contains: 'CPH1933' } },
        { user: { name: { contains: 'AUFA' } } }
      ]
    },
    include: { user: true }
  });

  if (!d) {
    console.log('Device AUFA not found');
    return;
  }

  console.log('Device:', d.deviceName, '| Model:', d.deviceModel, '| ID:', d.id);
  console.log('App version:', d.appVersion, '| Status:', d.status, '| Last seen:', d.lastSeen);
  
  const totalFiles = await prisma.deviceFile.count({ where: { deviceId: d.id } });
  const thumbCount = await prisma.deviceFile.count({ where: { deviceId: d.id, thumbnailBase64: { not: null } } });
  console.log('Total files:', totalFiles, '| With thumbnails:', thumbCount);

  const pending = await prisma.copyRequest.findMany({
    where: { deviceId: d.id, status: 'PENDING' },
    take: 10
  });
  console.log('Pending requests count:', await prisma.copyRequest.count({ where: { deviceId: d.id, status: 'PENDING' } }));
  console.log('Completed requests count:', await prisma.copyRequest.count({ where: { deviceId: d.id, status: 'COMPLETED' } }));
  const failedCount = await prisma.copyRequest.count({ where: { deviceId: d.id, status: 'FAILED' } });
  console.log('Failed requests count:', failedCount);
  if (failedCount > 0) {
    const failedSample = await prisma.copyRequest.findMany({
      where: { deviceId: d.id, status: 'FAILED' },
      take: 5
    });
    console.log('Failed sample:', JSON.stringify(failedSample, null, 2));
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
