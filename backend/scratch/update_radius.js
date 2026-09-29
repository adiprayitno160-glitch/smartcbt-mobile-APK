const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
  const res = await prisma.class.updateMany({
    data: {
      radiusMeters: 75,
      lat: -8.125506,
      lng: 111.893526
    }
  });
  console.log('Successfully updated class radius to 75m for all classes! Count:', res.count);

  // Update gate settings emergencyReason to not bypass location
  await prisma.gateSetting.updateMany({
    data: {
      isEmergencyGateOut: false,
      emergencyReason: null
    }
  });
  console.log('Successfully reset gate emergency settings to normal strict geofence mode!');
}

main().finally(() => prisma.$disconnect());
