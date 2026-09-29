const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function main() {
    console.log('Clearing all elibrary and literacy/book records...');

    const bAssignments = prisma.studentBookAssignment ? await prisma.studentBookAssignment.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };
    const lLoans = prisma.libraryLoan ? await prisma.libraryLoan.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };
    const lReservations = prisma.libraryBookReservation ? await prisma.libraryBookReservation.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };
    const lBorrowings = prisma.libraryBorrowing ? await prisma.libraryBorrowing.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };

    const pCopies = prisma.packageBookCopy ? await prisma.packageBookCopy.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };
    const lCopies = prisma.libraryBookCopy ? await prisma.libraryBookCopy.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };
    const bCopies = prisma.bookCopy ? await prisma.bookCopy.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };

    const ebooks = prisma.ebook ? await prisma.ebook.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };
    const pBooks = prisma.packageBook ? await prisma.packageBook.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };
    const mBooks = prisma.libraryMasterBook ? await prisma.libraryMasterBook.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };
    const lBooks = prisma.libraryBook ? await prisma.libraryBook.deleteMany({}).catch(() => ({ count: 0 })) : { count: 0 };

    console.log('✅ Successfully cleared all elibrary and literacy data:');
    console.log('- StudentBookAssignment:', bAssignments.count);
    console.log('- LibraryLoan:', lLoans.count);
    console.log('- LibraryBookReservation:', lReservations.count);
    console.log('- LibraryBorrowing:', lBorrowings.count);
    console.log('- PackageBookCopy:', pCopies.count);
    console.log('- LibraryBookCopy:', lCopies.count);
    console.log('- BookCopy:', bCopies.count);
    console.log('- Ebook:', ebooks.count);
    console.log('- PackageBook:', pBooks.count);
    console.log('- LibraryMasterBook:', mBooks.count);
    console.log('- LibraryBook:', lBooks.count);
}

main().catch(console.error).finally(() => prisma.$disconnect());
