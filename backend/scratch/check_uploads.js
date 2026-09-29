const fs = require('fs');
const path = require('path');
const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const uploadDir = path.resolve('uploads/device-files');
  if (!fs.existsSync(uploadDir)) {
    console.log('No uploadDir');
    return;
  }
  const diskFiles = fs.readdirSync(uploadDir);
  console.log('Total files in uploads/device-files:', diskFiles.length);
  for (const f of diskFiles.slice(0, 15)) {
    console.log('  File on disk:', f);
  }

  // Let's check which device has completed copy requests
  const completed = await prisma.copyRequest.findMany({
    where: { status: 'COMPLETED' },
    select: { id: true, deviceId: true, fileName: true, filePath: true, uploadPath: true, device: { select: { id: true, deviceName: true, deviceModel: true, user: { select: { name: true } } } } }
  });
  console.log('Total COMPLETED copyRequests:', completed.length);
  for (const c of completed.slice(0, 20)) {
    console.log('  Req ID:', c.id, '| Device:', c.device?.deviceName, '| User:', c.device?.user?.name, '| File:', c.fileName);
  }

  // Check the two devices in the screenshot:
  // 1. realme RMX5303 (AIRI DWI PUTRA)
  // 2. realme RMX2180 (WARADHANA DANISH PRAYATA)
  const d1 = await prisma.deviceSession.findFirst({
    where: { deviceModel: { contains: 'RMX5303' } },
    include: { user: true }
  });
  if (d1) {
    console.log('RMX5303:', d1.id, '| user:', d1.user?.name);
    const c1 = await prisma.copyRequest.count({ where: { deviceId: d1.id, status: 'COMPLETED' } });
    console.log('  RMX5303 completed copyRequests:', c1);
  }

  const d2 = await prisma.deviceSession.findFirst({
    where: { deviceModel: { contains: 'RMX2180' } },
    include: { user: true }
  });
  if (d2) {
    console.log('RMX2180:', d2.id, '| user:', d2.user?.name);
    const c2 = await prisma.copyRequest.count({ where: { deviceId: d2.id, status: 'COMPLETED' } });
    console.log('  RMX2180 completed copyRequests:', c2);
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
