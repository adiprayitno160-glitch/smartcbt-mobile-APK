const { PrismaClient } = require('@prisma/client');
const bcrypt = require('bcryptjs');
const prisma = new PrismaClient();

async function check() {
    const user = await prisma.user.findFirst({
        where: {
            OR: [
                { username: 'GURU-604442' },
                { username: 'guru-604442' },
                { username: { contains: '604442' } }
            ]
        }
    });

    console.log("User details:", JSON.stringify(user, null, 2));

    if (user) {
        const passwordsToTest = [
            'password123',
            'guru123',
            'admin123',
            '123456',
            '604442',
            'GURU-604442',
            'guru-604442',
            'password',
            'ARIF FAOZAN',
            'arif faozan'
        ];
        console.log("\nTesting passwords for:", user.username);
        for (const p of passwordsToTest) {
            const matches = await bcrypt.compare(p, user.password);
            console.log(`- "${p}": ${matches ? 'MATCH! SUCCESS' : 'NO'}`);
        }
    }

    // Also let's check other teachers named "ARIF FAOZAN" or similar
    const sameName = await prisma.user.findMany({
        where: { name: { contains: 'FAOZAN' } }
    });
    console.log("\nUsers with name FAOZAN:");
    for (const u of sameName) {
        console.log(`- Username: "${u.username}", Name: "${u.name}", Role: "${u.role}"`);
        for (const p of ['password123', 'guru123']) {
            if (await bcrypt.compare(p, u.password)) {
                console.log(`  -> Password matched: ${p}`);
            }
        }
    }
}

check().catch(console.error).finally(() => prisma.$disconnect());
