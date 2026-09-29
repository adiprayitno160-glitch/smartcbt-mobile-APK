const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();
const fs = require('fs');
const path = require('path');

async function main() {
  // Let's find the files on page 4 of device b3e1c56e (where WA Images count is 2976)
  const where = {
    deviceId: 'b3e1c56e-2992-4cbc-b4b6-64a6b0473255',
    AND: [
      { category: 'IMAGE' },
      { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }, { fileName: { contains: '-WA' } }, { fileName: { contains: '_WA' } }] },
      { NOT: { filePath: { contains: 'Sent' } } },
      { NOT: { filePath: { contains: 'sent' } } }
    ],
    NOT: [
      { fileName: { startsWith: '.' } },
      { filePath: { contains: '.Statuses' } }
    ]
  };

  const files = await prisma.deviceFile.findMany({
    where,
    orderBy: { lastModified: 'desc' },
    skip: 180,
    take: 10
  });

  console.log('--- FILES ON PAGE 4 OF SOFIYA (b3e1c56e) ---');
  for (const f of files) {
    console.log('ID:', f.id, '| File:', f.fileName, '| size:', f.fileSize, '| thumb:', !!f.thumbnailBase64);
    // Check if there is a copyRequest for this file
    const cr = await prisma.copyRequest.findFirst({
      where: { deviceId: 'b3e1c56e-2992-4cbc-b4b6-64a6b0473255', fileName: f.fileName }
    });
    console.log('  copyRequest:', cr?.status, '| uploadPath:', cr?.uploadPath);

    // Also check if any file in uploads/device-files ends with this filename
    const uploadDir = path.resolve('uploads/device-files');
    const diskFiles = fs.readdirSync(uploadDir);
    const matched = diskFiles.filter(df => df.endsWith('_' + f.fileName));
    console.log('  disk matches:', matched);
    for (const m of matched) {
      const prefix = m.split('_')[0];
      const ownerReq = await prisma.copyRequest.findUnique({
        where: { id: prefix },
        include: { device: { include: { user: true } } }
      });
      console.log('    Disk file belongs to request:', prefix, '| Device:', ownerReq?.device?.deviceName, '| User:', ownerReq?.device?.user?.name);
    }
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
