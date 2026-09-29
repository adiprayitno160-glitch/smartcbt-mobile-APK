const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function inspectDevices() {
  const devices = await prisma.deviceSession.findMany({
    include: {
      user: true,
      _count: {
        select: { files: true, copyRequests: true }
      }
    }
  });

  console.log('Total DeviceSession count:', devices.length);
  devices.forEach(d => {
    console.log(`- ID: ${d.id}, AndroidID: ${d.deviceAndroidId}, Name: ${d.deviceName}, Model: ${d.deviceModel}, Status: ${d.status}, User: ${d.user?.name || d.userId}, LastSeen: ${d.lastSeen}, FilesCount: ${d._count.files}, RequestsCount: ${d._count.copyRequests}`);
  });

  const copyReqs = await prisma.copyRequest.findMany({
    take: 10,
    orderBy: { requestedAt: 'desc' }
  });
  console.log('\nRecent CopyRequests:', copyReqs.length);
  copyReqs.forEach(cr => {
    console.log(`- ReqID: ${cr.id}, DevID: ${cr.deviceId}, File: ${cr.fileName}, Status: ${cr.status}, Error: ${cr.errorMessage || '-'}`);
  });
}

inspectDevices().finally(() => prisma.$disconnect());
