const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const f59 = await prisma.deviceFile.findMany({
    where: {
      fileName: { startsWith: 'IMG-20260910' },
      fileSize: { gte: 58000, lte: 62000 }
    },
    include: { device: { include: { user: true } } }
  });

  console.log('Matches for 59 KB IMG-20260910:');
  for (const f of f59) {
    console.log('Device:', f.device?.deviceName, '| User:', f.device?.user?.name, '| ID:', f.deviceId);
    console.log('  File:', f.fileName, '| size:', f.fileSize, '| human:', f.fileSizeHuman, '| path:', f.filePath);
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
