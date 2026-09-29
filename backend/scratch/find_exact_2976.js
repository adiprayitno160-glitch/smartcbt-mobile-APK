const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const devices = await prisma.deviceSession.findMany({ select: { id: true, deviceName: true } });
  for (const d of devices) {
    // Check various folder conditions
    const folders = ['ALL', 'WA Images', 'WA Images (Sent)', 'WA Video', 'Kamera', 'Screenshot', 'Unduhan', 'WA Docs', 'Dokumen'];
    for (const f of folders) {
      let where = { deviceId: d.id, NOT: [{ fileName: { startsWith: '.' } }, { filePath: { contains: '.Statuses' } }] };
      if (f === 'WA Images') {
        where.AND = [
          { category: 'IMAGE' },
          { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }, { fileName: { contains: '-WA' } }, { fileName: { contains: '_WA' } }] },
          { NOT: { filePath: { contains: 'Sent' } } },
          { NOT: { filePath: { contains: 'sent' } } }
        ];
      }
      const count = await prisma.deviceFile.count({ where });
      if (count === 2976) {
        console.log(`Device: ${d.deviceName} (${d.id}), Folder: ${f} -> EXACTLY 2976 FILES!`);
        const sample = await prisma.deviceFile.findMany({ where, skip: 180, take: 5, orderBy: { lastModified: 'desc' } });
        console.log('Sample files at 181-185:');
        for (const s of sample) {
          console.log('  ', s.fileName, '|', s.filePath);
        }
      }
    }
  }
}

main().catch(console.error).finally(() => prisma.$disconnect());
