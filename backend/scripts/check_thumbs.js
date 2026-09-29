const { PrismaClient } = require('@prisma/client');
const p = new PrismaClient();

async function main() {
  const total = await p.deviceFile.count();
  const withThumb = await p.deviceFile.count({ where: { thumbnailBase64: { not: null } } });
  const completedCopies = await p.copyRequest.count({ where: { status: 'COMPLETED' } });
  console.log(`TOTAL FILES: ${total}`);
  console.log(`FILES WITH THUMBNAIL: ${withThumb}`);
  console.log(`COMPLETED COPY TRANSFERS: ${completedCopies}`);

  // Check per device
  const devices = await p.deviceSession.findMany({
    include: {
      user: { select: { name: true, className: true } },
      _count: { select: { files: true } }
    },
    orderBy: { files: { _count: 'desc' } },
    take: 10
  });

  console.log('--- TOP 10 DEVICES ---');
  for (const d of devices) {
    const dThumbs = await p.deviceFile.count({ where: { deviceId: d.id, thumbnailBase64: { not: null } } });
    console.log(`${d.user?.name || d.deviceName} (${d.user?.className}): Total=${d._count.files}, withThumb=${dThumbs}`);
  }
}

main().finally(() => p.$disconnect());
