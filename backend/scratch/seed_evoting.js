const { PrismaClient } = require('@prisma/client');
const fs = require('fs');
const path = require('path');

const prisma = new PrismaClient();

async function main() {
    const candidatesDir = path.join(__dirname, '../uploads/candidates');
    if (!fs.existsSync(candidatesDir)) {
        fs.mkdirSync(candidatesDir, { recursive: true });
    }

    // Buat placeholder SVG/JPEG jika belum ada
    const sampleImagePath = path.join(__dirname, '../uploads/sample_real.jpg');
    for (let i = 1; i <= 3; i++) {
        const destPath = path.join(candidatesDir, `paslon${i}.jpg`);
        if (!fs.existsSync(destPath)) {
            if (fs.existsSync(sampleImagePath)) {
                fs.copyFileSync(sampleImagePath, destPath);
            }
        }
    }

    // 1. Enable modul e-voting
    await prisma.settings.upsert({
        where: { key: 'module_evoting_active' },
        update: { value: 'true' },
        create: { key: 'module_evoting_active', value: 'true' }
    });

    await prisma.settings.upsert({
        where: { key: 'evoting_title' },
        update: { value: 'Pemilihan Ketua & Wakil Ketua OSIS SMPN 1 Boyolangu 2026/2027' },
        create: { key: 'evoting_title', value: 'Pemilihan Ketua & Wakil Ketua OSIS SMPN 1 Boyolangu 2026/2027' }
    });

    // 2. Seed Kandidat 1
    await prisma.candidate.upsert({
        where: { candidateNumber: 1 },
        update: {
            chairmanName: 'Ahmad Fauzi Pratama (VIII-A)',
            viceChairmanName: 'Nadia Safitri (VII-B)',
            vision: 'Mewujudkan OSIS yang Religius, Inovatif, Berkarakter Juara, dan Adaptif terhadap Teknologi Digital.',
            mission: JSON.stringify([
                'Meningkatkan kedisiplinan dan akhlak mulia siswa melalui kegiatan ibadah bersama.',
                'Mengoptimalkan peran ekstrakurikuler dalam mewadahi bakat sains, seni, dan olahraga.',
                'Mengembangkan kanal aspirasi siswa digital yang transparan dan responsif.',
                'Menyelenggarakan gerakan Sekolah Bersih Tanpa Sampah Plastik.'
            ]),
            photoUrl: '/uploads/candidates/paslon1.jpg',
            isActive: true
        },
        create: {
            candidateNumber: 1,
            chairmanName: 'Ahmad Fauzi Pratama (VIII-A)',
            viceChairmanName: 'Nadia Safitri (VII-B)',
            vision: 'Mewujudkan OSIS yang Religius, Inovatif, Berkarakter Juara, dan Adaptif terhadap Teknologi Digital.',
            mission: JSON.stringify([
                'Meningkatkan kedisiplinan dan akhlak mulia siswa melalui kegiatan ibadah bersama.',
                'Mengoptimalkan peran ekstrakurikuler dalam mewadahi bakat sains, seni, dan olahraga.',
                'Mengembangkan kanal aspirasi siswa digital yang transparan dan responsif.',
                'Menyelenggarakan gerakan Sekolah Bersih Tanpa Sampah Plastik.'
            ]),
            photoUrl: '/uploads/candidates/paslon1.jpg',
            isActive: true
        }
    });

    // 3. Seed Kandidat 2
    await prisma.candidate.upsert({
        where: { candidateNumber: 2 },
        update: {
            chairmanName: 'Dimas Wahyu Nugroho (VIII-C)',
            viceChairmanName: 'Zahra Anindya Putri (VII-D)',
            vision: 'Membangun Generasi Muda Boyolangu yang Berprestasi Akademik, Berbudaya, dan Solidaritas Tinggi.',
            mission: JSON.stringify([
                'Memfasilitasi forum belajar antarkelas untuk persiapan olimpiade sains dan seni.',
                'Menghidupkan festival literasi sekolah bersama duta baca dan perpustakaan.',
                'Menciptakan lingkungan sekolah ramah anak bebas perundungan (anti-bullying).',
                'Mengadakan kompetisi Class Meeting yang kreatif, sportif, dan inklusif.'
            ]),
            photoUrl: '/uploads/candidates/paslon2.jpg',
            isActive: true
        },
        create: {
            candidateNumber: 2,
            chairmanName: 'Dimas Wahyu Nugroho (VIII-C)',
            viceChairmanName: 'Zahra Anindya Putri (VII-D)',
            vision: 'Membangun Generasi Muda Boyolangu yang Berprestasi Akademik, Berbudaya, dan Solidaritas Tinggi.',
            mission: JSON.stringify([
                'Memfasilitasi forum belajar antarkelas untuk persiapan olimpiade sains dan seni.',
                'Menghidupkan festival literasi sekolah bersama duta baca dan perpustakaan.',
                'Menciptakan lingkungan sekolah ramah anak bebas perundungan (anti-bullying).',
                'Mengadakan kompetisi Class Meeting yang kreatif, sportif, dan inklusif.'
            ]),
            photoUrl: '/uploads/candidates/paslon2.jpg',
            isActive: true
        }
    });

    // 4. Seed Kandidat 3
    await prisma.candidate.upsert({
        where: { candidateNumber: 3 },
        update: {
            chairmanName: 'Rizky Aditya Putra (VIII-E)',
            viceChairmanName: 'Clarissa Maharani (VII-A)',
            vision: 'Transformasi OSIS yang Kreatif, Berwawasan Global, dan Menjadi Teladan Integritas.',
            mission: JSON.stringify([
                'Mengadakan workshop public speaking dan kepemimpinan bagi seluruh pengurus kelas.',
                'Mendorong partisipasi siswa dalam pameran riset dan karya seni tahunan.',
                'Menggalang program bakti sosial dan aksi peduli sesama di lingkungan sekitar sekolah.',
                'Mengawal demokrasi sekolah yang jujur, adil, dan berintegritas tinggi.'
            ]),
            photoUrl: '/uploads/candidates/paslon3.jpg',
            isActive: true
        },
        create: {
            candidateNumber: 3,
            chairmanName: 'Rizky Aditya Putra (VIII-E)',
            viceChairmanName: 'Clarissa Maharani (VII-A)',
            vision: 'Transformasi OSIS yang Kreatif, Berwawasan Global, dan Menjadi Teladan Integritas.',
            mission: JSON.stringify([
                'Mengadakan workshop public speaking dan kepemimpinan bagi seluruh pengurus kelas.',
                'Mendorong partisipasi siswa dalam pameran riset dan karya seni tahunan.',
                'Menggalang program bakti sosial dan aksi peduli sesama di lingkungan sekitar sekolah.',
                'Mengawal demokrasi sekolah yang jujur, adil, dan berintegritas tinggi.'
            ]),
            photoUrl: '/uploads/candidates/paslon3.jpg',
            isActive: true
        }
    });

    console.log('✅ Seeding kandidat paslon dan feature toggle berhasil!');
}

main()
    .catch(e => {
        console.error(e);
        process.exit(1);
    })
    .finally(() => prisma.$disconnect());
