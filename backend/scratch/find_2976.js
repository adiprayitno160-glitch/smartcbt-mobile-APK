const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const devices = await prisma.deviceSession.findMany({
    select: { id: true, deviceName: true, deviceModel: true, user: { select: { name: true } } }
  });

  for (const d of devices) {
    const total = await prisma.deviceFile.count({ where: { deviceId: d.id } });
    const waIn = await prisma.deviceFile.count({
      where: {
        deviceId: d.id,
        category: 'IMAGE',
        OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }, { fileName: { contains: '-WA' } }],
        NOT: [{ filePath: { contains: 'Sent' } }, { filePath: { contains: 'sent' } }]
      }
    });
    if (total === 2976 || waIn === 2976) {
      console.log('MATCH 2976:', d.deviceName, '| Model:', d.deviceModel, '| User:', d.user?.name, '| ID:', d.id);
    }
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
