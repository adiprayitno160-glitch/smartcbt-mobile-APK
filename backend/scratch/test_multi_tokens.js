const path = require('path');
const prisma = require(path.join(__dirname, '../dist/utils/db')).default;
const { getClassExamToken } = require(path.join(__dirname, '../dist/controllers/cbtController'));

async function testTokens() {
  const [exams, classes] = await Promise.all([
    prisma.exam.findMany({
      include: { subject: true, _count: { select: { questions: true } } },
      orderBy: { createdAt: 'desc' },
      take: 2
    }),
    prisma.class.findMany({ orderBy: { name: 'asc' } })
  ]);

  console.log(`Found ${classes.length} classes in database:`);
  console.log(classes.map(c => c.name).join(', '));

  exams.forEach(e => {
    console.log(`\n=== Exam: ${e.title} (${e.id}) ===`);
    console.log(`Master Token: ${e.token}, isTokenActive: ${e.isTokenActive}`);
    console.log('Sample Class Tokens (11 classes):');
    classes.forEach(c => {
      const cToken = getClassExamToken(e.id, c.name, e.token);
      console.log(`  - Kelas ${c.name}: ${cToken}`);
    });
  });
}

testTokens().then(() => process.exit(0)).catch(err => {
  console.error(err);
  process.exit(1);
});
