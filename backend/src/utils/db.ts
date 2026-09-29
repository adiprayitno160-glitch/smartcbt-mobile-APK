import { PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();

// Optimasi SQLite untuk Concurrency Tinggi & Nol Jeda (Zero Lock)
(async () => {
    try {
        await prisma.$queryRawUnsafe('PRAGMA journal_mode = WAL;');
        await prisma.$queryRawUnsafe('PRAGMA synchronous = NORMAL;');
        await prisma.$queryRawUnsafe('PRAGMA busy_timeout = 10000;');
        await prisma.$queryRawUnsafe('PRAGMA temp_store = MEMORY;');
        await prisma.$queryRawUnsafe('PRAGMA cache_size = -64000;'); // 64MB memory cache
        console.log('⚡ SQLite WAL Mode & High Concurrency PRAGMAs successfully activated.');
    } catch (e) {
        console.warn('SQLite PRAGMA init note:', e);
    }
})();

export default prisma;

