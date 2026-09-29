import prisma from '../utils/db';

async function check() {
  const devices = await prisma.deviceSession.findMany({
    select: { id: true, deviceAndroidId: true, deviceName: true, status: true, lastSeen: true }
  });
  console.log('Devices:', JSON.stringify(devices, null, 2));

  const copyStats = await prisma.copyRequest.groupBy({
    by: ['status'],
    _count: true
  });
  console.log('CopyRequest stats by status:', JSON.stringify(copyStats, null, 2));

  const failedRequests = await prisma.copyRequest.findMany({
    where: { status: 'FAILED' },
    take: 10,
    orderBy: { completedAt: 'desc' }
  });
  console.log('Sample FAILED CopyRequests:', JSON.stringify(failedRequests.map(r => ({ fileName: r.fileName, error: r.errorMessage })), null, 2));

  const pendingRequests = await prisma.copyRequest.findMany({
    where: { status: 'PENDING' },
    take: 10,
    orderBy: { requestedAt: 'desc' }
  });
  console.log('Sample PENDING CopyRequests:', JSON.stringify(pendingRequests.map(r => ({ id: r.id, fileName: r.fileName, filePath: r.filePath })), null, 2));

  const completedRequests = await prisma.copyRequest.findMany({
    where: { status: 'COMPLETED' },
    take: 5,
    orderBy: { completedAt: 'desc' }
  });
  console.log('Sample COMPLETED CopyRequests:', JSON.stringify(completedRequests.map(r => ({ fileName: r.fileName, size: r.fileSize, path: r.uploadPath })), null, 2));

  const totalFiles = await prisma.deviceFile.count();
  console.log('Total DeviceFile records in DB:', totalFiles);

  process.exit(0);
}

check().catch(e => {
  console.error(e);
  process.exit(1);
});
