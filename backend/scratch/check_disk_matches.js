const fs = require('fs');
const path = require('path');
const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const uploadDir = path.resolve('uploads/device-files');
  const files = fs.readdirSync(uploadDir);

  // Look for any file containing 'Veera' or Tangerang or girl selfies in uploads
  console.log('Searching for files on disk:');
  const sample = files.filter(f => f.includes('IMG-20260910') || f.includes('IMG-20260911'));
  console.log('Total IMG-20260910 & IMG-20260911 on disk:', sample.length);
  for (const s of sample.slice(0, 20)) {
    const prefix = s.split('_')[0];
    const cr = await prisma.copyRequest.findUnique({
      where: { id: prefix },
      include: { device: { include: { user: true } } }
    });
    console.log('  ', s, '-> Device:', cr?.device?.deviceName, '| User:', cr?.device?.user?.name, '| Class:', cr?.device?.user?.className);
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
