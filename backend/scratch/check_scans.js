const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  console.log('=== GATE SETTINGS ===');
  const gateSettings = await prisma.gateSetting.findMany();
  console.log(JSON.stringify(gateSettings, null, 2));

  console.log('\n=== RECENT ATTENDANCE SCANS (LAST 20) ===');
  const attendances = await prisma.attendance.findMany({
    orderBy: { scanTime: 'desc' },
    take: 20,
    include: {
      user: { select: { name: true, className: true } }
    }
  });
  console.log(JSON.stringify(attendances.map(a => ({
    id: a.id,
    user: a.user?.name,
    class: a.user?.className,
    type: a.type,
    method: a.method,
    status: a.status,
    scanTime: a.scanTime,
    lat: a.lat,
    lng: a.lng,
    isFakeGps: a.isFakeGps
  })), null, 2));

  console.log('\n=== CLASS PERIOD ATTENDANCE (LAST 10) ===');
  const classPeriod = await prisma.classPeriodAttendance.findMany({
    orderBy: { createdAt: 'desc' },
    take: 10
  });
  console.log(JSON.stringify(classPeriod, null, 2));

  console.log('\n=== CLASSES COORDINATES (SAMPLE) ===');
  const classes = await prisma.class.findMany({
    select: { name: true, lat: true, lng: true, radiusMeters: true, isGpsLocked: true }
  });
  console.log(JSON.stringify(classes.slice(0, 5), null, 2));
}

main().finally(() => prisma.$disconnect());
