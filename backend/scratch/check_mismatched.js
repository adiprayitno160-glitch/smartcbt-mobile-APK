const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const reqs = await prisma.copyRequest.findMany({
    where: { status: 'COMPLETED', uploadPath: { not: null } },
    select: { id: true, deviceId: true, fileName: true, uploadPath: true, device: { select: { deviceName: true } } }
  });

  let mismatched = 0;
  for (const r of reqs) {
    const filename = r.uploadPath.split('\\').pop().split('/').pop();
    if (!filename.startsWith(r.id)) {
      mismatched++;
      if (mismatched <= 10) {
        console.log('Mismatched copyRequest:', r.id, '| Device:', r.device?.deviceName, '| File on disk:', filename);
      }
    }
  }
  console.log('Total completed copyRequests:', reqs.length, '| Mismatched uploadPath:', mismatched);
}

main().catch(console.error).finally(() => prisma.$disconnect());
