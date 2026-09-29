const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();
const fs = require('fs');

async function main() {
  const reqs = await prisma.copyRequest.findMany({
    where: { status: 'COMPLETED' },
    take: 5
  });
  console.log('Sample Completed Requests:', JSON.stringify(reqs, null, 2));
  for (const r of reqs) {
    if (r.uploadPath) {
      console.log('uploadPath:', r.uploadPath, 'exists:', fs.existsSync(r.uploadPath));
    }
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
