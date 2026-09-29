const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const files = await prisma.deviceFile.findMany({
    where: {
      fileName: { startsWith: 'IMG-20260911' },
      fileSize: { gte: 1000000, lte: 2000000 }
    },
    include: { device: { include: { user: true } } },
    take: 10
  });

  console.log('Files starting with IMG-20260911 with size ~1.4MB:');
  for (const f of files) {
    console.log('Device:', f.device?.deviceName, '| User:', f.device?.user?.name, '| ID:', f.deviceId);
    console.log('  File:', f.fileName, '| size:', f.fileSize, '| human:', f.fileSizeHuman, '| path:', f.filePath);
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
