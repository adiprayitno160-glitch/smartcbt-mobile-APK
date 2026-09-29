import { Request, Response } from 'express';
import prisma from '../utils/db';
import fs from 'fs';
import path from 'path';
import { getSchoolSignatures } from '../utils/schoolSignatures';

// 1. Ambil Semua Surat Resmi (Untuk Admin & Operator)
export const getOfficialLetters = async (req: Request, res: Response) => {
    try {
        const letters = await (prisma as any).officialLetter.findMany({
            include: {
                confirmations: true,
                _count: { select: { confirmations: true } }
            },
            orderBy: { createdAt: 'desc' }
        });
        res.json(letters);
    } catch (error) {
        console.error('Error fetching official letters:', error);
        res.status(500).json({ message: 'Gagal memuat daftar surat resmi' });
    }
};

// 2. Buat & Kirim Surat Resmi (Admin & Operator)
// Target: ALL (/semua), GRADE (/pertingkat cth: 7, 8, 9), CLASS (/kelas cth: VII-A), STUDENT (/siswa cth: NISN/userId)
export const sendOfficialLetter = async (req: Request, res: Response) => {
    try {
        const { letterNo, title, content, targetType, targetValue, pdfBase64, customSenderName } = req.body;
        const currentUser = (req as any).user;

        if (!title || !targetType) {
            return res.status(400).json({ message: 'Judul Surat dan Sasaran Penerima wajib diisi!' });
        }

        const validTargets = ['ALL', 'GRADE', 'CLASS', 'STUDENT'];
        if (!validTargets.includes(targetType)) {
            return res.status(400).json({ message: 'Tipe sasaran tidak valid. Gunakan ALL, GRADE, CLASS, atau STUDENT.' });
        }

        if (targetType !== 'ALL' && (!targetValue || String(targetValue).trim().length === 0)) {
            return res.status(400).json({ message: 'Target rincian (Tingkat, Kelas, atau Siswa) wajib ditentukan!' });
        }

        const uploadDir = path.join(process.cwd(), 'uploads', 'letters');
        if (!fs.existsSync(uploadDir)) {
            fs.mkdirSync(uploadDir, { recursive: true });
        }

        let fileUrl = '';
        let fileSize = '1.2 MB';

        // Simpan file PDF (jika dikirim dalam format base64 dari form web)
        if (pdfBase64 && (pdfBase64.startsWith('data:application/pdf') || pdfBase64.startsWith('data:image/'))) {
            const matches = pdfBase64.match(/^data:([a-zA-Z0-9/+-]+);base64,(.+)$/);
            if (matches) {
                const mime = matches[1];
                let ext = 'pdf';
                if (mime.includes('image/png')) ext = 'png';
                else if (mime.includes('image/jpeg')) ext = 'jpg';

                const buffer = Buffer.from(matches[2], 'base64');
                const safeName = `Surat_${Date.now()}_${Math.random().toString(36).substring(2, 7)}.${ext}`;
                const fullPath = path.join(uploadDir, safeName);
                fs.writeFileSync(fullPath, buffer);
                fileUrl = `/uploads/letters/${safeName}`;

                const sizeInKb = Math.round(buffer.length / 1024);
                fileSize = sizeInKb > 1024 ? (sizeInKb / 1024).toFixed(1) + ' MB' : sizeInKb + ' KB';
            }
        }

        const sigs = await getSchoolSignatures();

        // Jika tidak ada upload berkas PDF spesifik, buat template PDF resmi default berbasis nomor surat
        if (!fileUrl) {
            const safeName = `Surat_Resmi_${Date.now()}.pdf`;
            const dummyContent = `%PDF-1.4\n% ${sigs.schoolName.toUpperCase()} - SURAT RESMI SEKOLAH\n1 0 obj << /Title (${title}) /Author (${sigs.schoolName} - ${sigs.headmasterName}) >> endobj\ntrailer << /Root 1 0 R >> %%EOF`;
            const fullPath = path.join(uploadDir, safeName);
            fs.writeFileSync(fullPath, Buffer.from(dummyContent, 'utf-8'));
            fileUrl = `/uploads/letters/${safeName}`;
            fileSize = '320 KB';
        }

        const finalLetterNo = letterNo && letterNo.trim().length > 0 ? letterNo.trim() : '';
        const senderName = customSenderName && customSenderName.trim().length > 0 ? customSenderName.trim() : '';

        const letter = await (prisma as any).officialLetter.create({
            data: {
                letterNo: finalLetterNo,
                title,
                content: content || 'Pemberitahuan resmi dari pihak sekolah untuk orang tua / wali siswa.',
                targetType,
                targetValue: targetType === 'ALL' ? null : String(targetValue).trim(),
                fileUrl,
                fileSize,
                senderRole: currentUser?.role || 'ADMIN',
                senderName
            }
        });

        // Kirimkan notifikasi ke seluruh target siswa terkait
        try {
            let studentFilter: any = { role: 'STUDENT' };
            if (targetType === 'CLASS' && targetValue) {
                studentFilter.className = String(targetValue).trim();
            } else if (targetType === 'GRADE' && targetValue) {
                const gr = String(targetValue).trim();
                studentFilter.OR = [
                    { className: { startsWith: gr } },
                    { className: { startsWith: gr === '7' ? 'VII' : (gr === '8' ? 'VIII' : 'IX') } }
                ];
            } else if (targetType === 'STUDENT' && targetValue) {
                const val = String(targetValue).trim();
                studentFilter.OR = [
                    { id: val },
                    { nisn: val },
                    { username: val },
                    { name: { contains: val } }
                ];
            }

            const targetStudents = await prisma.user.findMany({
                where: studentFilter,
                select: { id: true, name: true, className: true }
            });

            if (targetStudents.length > 0) {
                const notifData = targetStudents.map(s => ({
                    recipientId: s.id,
                    recipientRole: 'STUDENT',
                    studentId: s.id,
                    studentName: s.name,
                    className: s.className,
                    title: `📢 ${title}`,
                    message: `${content || 'Surat edaran resmi sekolah'} (Buka menu Pengumuman di aplikasi untuk melihat berkas PDF)`,
                    category: 'OFFICIAL_LETTER'
                }));
                await (prisma as any).notificationMessage.createMany({
                    data: notifData
                });
            }
        } catch (notifErr) {
            console.error('Error sending letter notifications:', notifErr);
        }

        res.json({
            message: `✅ Surat resmi "${title}" berhasil dikirimkan ke sasaran [${targetType} ${targetValue || ''}]!`,
            letter
        });
    } catch (error) {
        console.error('Error sending official letter:', error);
        res.status(500).json({ message: 'Gagal mengirimkan surat resmi' });
    }
};

// 3. Hapus Surat Resmi (Admin & Operator)
export const deleteOfficialLetter = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const letter = await (prisma as any).officialLetter.findUnique({ where: { id } });
        if (!letter) {
            return res.status(404).json({ message: 'Surat resmi tidak ditemukan' });
        }

        // Hapus berkas fisik jika ada
        if (letter.fileUrl && letter.fileUrl.startsWith('/uploads/letters/')) {
            const filePath = path.join(process.cwd(), letter.fileUrl);
            if (fs.existsSync(filePath)) {
                try { fs.unlinkSync(filePath); } catch (e) {}
            }
        }

        await (prisma as any).officialLetter.delete({ where: { id } });
        res.json({ message: 'Surat resmi berhasil dihapus dari sistem' });
    } catch (error) {
        console.error('Error deleting official letter:', error);
        res.status(500).json({ message: 'Gagal menghapus surat resmi' });
    }
};

// 4. Ambil Surat Masuk untuk Orang Tua Siswa (APK Orang Tua & Portal Orang Tua)
export const getParentLetters = async (req: any, res: Response) => {
    try {
        const currentUser = req.user;
        let student: any = null;

        if (currentUser.role === 'STUDENT') {
            student = await prisma.user.findUnique({ where: { id: currentUser.id } });
        } else {
            const queryChild = req.query.childId || req.query.nisn;
            if (queryChild) {
                student = await prisma.user.findFirst({
                    where: {
                        OR: [
                            { id: String(queryChild) },
                            { username: String(queryChild) },
                            { nisn: String(queryChild) }
                        ],
                        role: 'STUDENT'
                    }
                });
            } else {
                student = await prisma.user.findFirst({
                    where: {
                        role: 'STUDENT',
                        OR: [
                            { parentPhone: currentUser.username },
                            { fatherName: currentUser.name },
                            { motherName: currentUser.name }
                        ]
                    }
                });
                if (!student) {
                    student = await prisma.user.findFirst({ where: { role: 'STUDENT' } });
                }
            }
        }

        if (!student) {
            return res.json({ letters: [], child: null, count: 0 });
        }

        const className = (student.className || 'VII-A').toUpperCase();
        let gradeNumber = '7';
        if (className.includes('VIII') || className.startsWith('8')) gradeNumber = '8';
        else if (className.includes('IX') || className.startsWith('9')) gradeNumber = '9';
        else gradeNumber = '7';

        // Query surat yang relevan
        const allLetters = await (prisma as any).officialLetter.findMany({
            include: {
                confirmations: true
            },
            orderBy: { createdAt: 'desc' },
            take: 50
        });

        const relevantLetters = allLetters.filter((letter: any) => {
            if (letter.targetType === 'ALL') return true;
            if (letter.targetType === 'GRADE') {
                const targetGrade = String(letter.targetValue || '').trim();
                return targetGrade === gradeNumber || targetGrade === `Kelas ${gradeNumber}` || className.includes(targetGrade);
            }
            if (letter.targetType === 'CLASS') {
                const targetCls = String(letter.targetValue || '').trim().toUpperCase();
                return targetCls === className || targetCls === `KELAS ${className}`;
            }
            if (letter.targetType === 'STUDENT') {
                const targetVal = String(letter.targetValue || '').trim().toLowerCase();
                return targetVal === student.id.toLowerCase() || 
                       targetVal === (student.nisn || '').toLowerCase() || 
                       targetVal === (student.username || '').toLowerCase() ||
                       (student.name && student.name.toLowerCase().includes(targetVal));
            }
            return false;
        }).map((letter: any) => {
            const myConf = (letter.confirmations || []).find((c: any) => c.studentId === student.id);
            return {
                ...letter,
                isConfirmed: Boolean(myConf),
                confirmedAt: myConf ? myConf.confirmedAt : null
            };
        });

        res.json({
            child: {
                id: student.id,
                name: student.name,
                className: student.className,
                nisn: student.nisn || student.username,
                grade: gradeNumber
            },
            count: relevantLetters.length,
            letters: relevantLetters
        });
    } catch (error) {
        console.error('Error fetching parent letters:', error);
        res.status(500).json({ message: 'Gagal memuat surat resmi untuk orang tua' });
    }
};

// 5. Konfirmasi Surat Resmi oleh Orang Tua (APK Orang Tua)
export const confirmOfficialLetter = async (req: any, res: Response) => {
    try {
        const { id } = req.params;
        const { studentId, notes } = req.body;
        const currentUser = req.user;

        const letter = await (prisma as any).officialLetter.findUnique({ where: { id } });
        if (!letter) {
            return res.status(404).json({ message: 'Surat resmi tidak ditemukan' });
        }

        let student = null;
        if (studentId) {
            student = await prisma.user.findUnique({ where: { id: studentId } });
        }
        if (!student) {
            student = await prisma.user.findFirst({
                where: {
                    role: 'STUDENT',
                    OR: [
                        { parentPhone: currentUser.username },
                        { fatherName: currentUser.name },
                        { motherName: currentUser.name }
                    ]
                }
            });
        }

        const sId = student?.id || studentId || currentUser.id;
        const sName = student?.name || 'Siswa';
        const pName = currentUser.name || 'Orang Tua / Wali';

        // Cek apakah sudah pernah konfirmasi
        let conf = await (prisma as any).letterConfirmation.findFirst({
            where: {
                letterId: id,
                studentId: sId
            }
        });

        if (conf) {
            conf = await (prisma as any).letterConfirmation.update({
                where: { id: conf.id },
                data: {
                    notes: notes || conf.notes,
                    confirmedAt: new Date()
                }
            });
        } else {
            conf = await (prisma as any).letterConfirmation.create({
                data: {
                    letterId: id,
                    parentId: currentUser.id,
                    studentId: sId,
                    parentName: pName,
                    studentName: sName,
                    status: 'CONFIRMED',
                    notes: notes || 'Telah dibaca dan disetujui orang tua siswa via APK'
                }
            });
        }

        res.json({
            message: '✅ Terima kasih! Konfirmasi dan persetujuan surat resmi berhasil disimpan.',
            confirmation: conf
        });
    } catch (error) {
        console.error('Error confirming official letter:', error);
        res.status(500).json({ message: 'Gagal mengonfirmasi surat resmi' });
    }
};
