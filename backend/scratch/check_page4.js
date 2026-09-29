const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const d = await prisma.deviceSession.findUnique({
    where: { id: 'b3e1c56e-2992-4cbc-b4b6-64a6b0473255' },
    include: { user: true }
  });
  console.log('Device b3e1c56e:', d?.deviceName, '| User:', d?.user?.name);

  const files = await prisma.deviceFile.findMany({
    where: { deviceId: 'b3e1c56e-2992-4cbc-b4b6-64a6b0473255' },
    orderBy: { lastModified: 'desc' },
    skip: 180,
    take: 10
  });

  console.log('Files on page 4 (181-190):');
  for (const f of files) {
    console.log('  File:', f.fileName, '| path:', f.filePath, '| thumb:', !!f.thumbnailBase64);
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
