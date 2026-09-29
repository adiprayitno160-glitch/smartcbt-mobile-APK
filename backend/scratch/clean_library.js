const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();
async function cleanLibrary() {
    try {
        const b = await prisma.libraryBorrowing.deleteMany({});
        const c = await prisma.bookCopy.deleteMany({});
        const k = await prisma.libraryBook.deleteMany({});
        console.log('CLEANED_LIBRARY:', { borrowings: b.count, copies: c.count, books: k.count });
    } catch(e) {
        console.error('ERROR:', e.message);
    } finally {
        await prisma.$disconnect();
    }
}
cleanLibrary();
