const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const reqs = await prisma.copyRequest.findMany({
    where: { status: 'COMPLETED' },
    select: { id: true, deviceId: true, fileName: true, uploadPath: true, device: { select: { deviceName: true, user: { select: { name: true } } } } }
  });

  const fakeCompleted = [];
  for (const r of reqs) {
    if (r.uploadPath) {
      const diskFilename = r.uploadPath.split('\\').pop().split('/').pop();
      const filePrefix = diskFilename.split('_')[0];
      if (filePrefix !== r.id) {
        fakeCompleted.push({
          reqId: r.id,
          deviceId: r.deviceId,
          user: r.device?.user?.name,
          fileName: r.fileName,
          diskFile: diskFilename,
          actualOwnerReqId: filePrefix
        });
      }
    }
  }

  console.log('Total COMPLETED requests:', reqs.length);
  console.log('Total FALSELY completed requests:', fakeCompleted.length);
  for (const f of fakeCompleted) {
    console.log(JSON.stringify(f));
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
