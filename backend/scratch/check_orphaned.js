const fs = require('fs');
const path = require('path');
const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const uploadDir = path.resolve('uploads/device-files');
  const allDiskFiles = fs.readdirSync(uploadDir);
  console.log('Total files on disk:', allDiskFiles.length);

  let orphaned = 0;
  let valid = 0;

  for (const f of allDiskFiles) {
    const prefix = f.split('_')[0];
    const cr = await prisma.copyRequest.findUnique({
      where: { id: prefix }
    });
    if (!cr) {
      orphaned++;
    } else {
      valid++;
    }
  }

  console.log(`Summary: Valid=${valid}, Orphaned (Nyantol)=${orphaned}`);
}

main().catch(console.error).finally(() => prisma.$disconnect());
