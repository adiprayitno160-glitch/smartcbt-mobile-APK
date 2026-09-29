const fs = require('fs');
const path = require('path');
const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const uploadDir = path.resolve('uploads/device-files');
  if (!fs.existsSync(uploadDir)) return;

  const allDiskFiles = fs.readdirSync(uploadDir);
  console.log('Total files on disk:', allDiskFiles.length);

  let deletedCount = 0;
  for (const f of allDiskFiles) {
    const prefix = f.split('_')[0];
    const cr = await prisma.copyRequest.findUnique({
      where: { id: prefix }
    });
    if (!cr) {
      try {
        fs.unlinkSync(path.join(uploadDir, f));
        deletedCount++;
      } catch (err) {
        console.error('Failed to unlink:', f, err);
      }
    }
  }
  console.log(`Cleaned up ${deletedCount} orphaned/nyantol files from uploads/device-files.`);

  // Fix the 2 falsely completed copyRequests
  const fake1 = await prisma.copyRequest.updateMany({
    where: {
      id: 'e8544b33-e51a-4fd6-85a0-88b0e5336627'
    },
    data: {
      status: 'PENDING',
      uploadPath: null,
      fileSize: null
    }
  });

  const fake2 = await prisma.copyRequest.updateMany({
    where: {
      id: 'fedf71d9-1b18-440a-9517-80df27d74440'
    },
    data: {
      status: 'PENDING',
      uploadPath: null,
      fileSize: null
    }
  });

  console.log('Reset fake completed copy requests:', fake1.count + fake2.count);
}

main().catch(console.error).finally(() => prisma.$disconnect());
