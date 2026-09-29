import { Request, Response } from 'express';
import prisma from '../utils/db';
import crypto from 'crypto';

export const getTeacherTodaySchedule = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const teacherName = user?.name || 'Guru';
        const days = ['Minggu', 'Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat', 'Sabtu'];
        const now = new Date();
        const currentDay = days[now.getDay()];
        const currentTimeStr = now.toTimeString().substring(0, 5);

        // 1. Cek dari database ClassPeriodSchedule
        const dbSchedules = await (prisma as any).classPeriodSchedule.findMany({
            where: {
                day: currentDay,
                isBreak: false,
                OR: [
                    { teacherId: user?.id },
                    { teacherName: { contains: user?.name || '' } }
                ]
            },
            orderBy: { periodIndex: 'asc' }
        });

        let schedules = [];
        if (dbSchedules && dbSchedules.length > 0) {
            for (const s of dbSchedules) {
                const totalStudents = await prisma.user.count({
                    where: { role: 'STUDENT', className: s.className }
                });
                schedules.push({
                    id: s.id,
                    className: s.className,
                    subject: s.subjectName,
                    timeRange: `${s.startTime} - ${s.endTime}`,
                    startTime: s.startTime,
                    endTime: s.endTime,
                    room: s.roomName || `Ruang Kelas ${s.className}`,
                    totalStudents: totalStudents || 30,
                    status: (currentTimeStr >= s.startTime && currentTimeStr <= s.endTime) ? 'ACTIVE' : 'UPCOMING'
                });
            }
        } else {
            // Ambil kelas riil dari rombel siswa yang ada di database
            const distinctClasses = await prisma.user.findMany({
                where: { role: 'STUDENT' },
                select: { className: true },
                distinct: ['className']
            });
            const validClasses = distinctClasses.map(c => c.className).filter(Boolean) as string[];
            const selectedClasses = validClasses.length >= 4 ? validClasses.slice(0, 4) : (validClasses.length > 0 ? validClasses : ['VII-A', 'VII-B', 'VII-C', 'VII-D']);

            const defaultTimes = [
                { start: '07:30', end: '09:00' },
                { start: '09:15', end: '10:45' },
                { start: '11:00', end: '12:30' },
                { start: '13:00', end: '14:30' }
            ];

            for (let i = 0; i < selectedClasses.length; i++) {
                const cName = selectedClasses[i];
                const tSlot = defaultTimes[i % defaultTimes.length];
                const totalStudents = await prisma.user.count({
                    where: { role: 'STUDENT', className: cName }
                });
                schedules.push({
                    id: `sched-${i + 1}`,
                    className: cName,
                    subject: user?.subject || 'Mata Pelajaran',
                    timeRange: `${tSlot.start} - ${tSlot.end}`,
                    startTime: tSlot.start,
                    endTime: tSlot.end,
                    room: `Ruang Kelas ${cName}`,
                    totalStudents: totalStudents || 30,
                    status: (currentTimeStr >= tSlot.start && currentTimeStr <= tSlot.end) ? 'ACTIVE' : 'UPCOMING'
                });
            }
        }

        res.json({
            teacherName,
            day: currentDay,
            currentTime: currentTimeStr,
            schedules
        });
    } catch (error) {
        console.error('Error fetching teacher schedule:', error);
        res.status(500).json({ message: 'Gagal memuat jadwal guru' });
    }
};

export const generateDynamicQr = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { className, subjectName } = req.body;

        if (!className) {
            return res.status(400).json({ message: 'Kelas wajib dipilih' });
        }

        const now = new Date();
        const expiresAt = new Date(now.getTime() + 15 * 1000);
        const randomSalt = crypto.randomBytes(8).toString('hex');
        const tokenString = 'CBT_QR_' + className + '_' + now.getTime() + '_' + randomSalt;

        await (prisma as any).dynamicQrSession.create({
            data: {
                teacherId: user?.id || 'teacher-1',
                className,
                subjectName: subjectName || 'Mata Pelajaran',
                qrToken: tokenString,
                expiresAt,
                isActive: true
            }
        });

        res.json({
            qrToken: tokenString,
            refreshIntervalSeconds: 10,
            expiresAt: expiresAt.toISOString(),
            className,
            timestamp: now.getTime()
        });
    } catch (error) {
        console.error('Error generating dynamic QR:', error);
        res.status(500).json({ message: 'Gagal membuat QR Code dinamis' });
    }
};

export const scanStudentDynamicQr = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { qrToken } = req.body;

        if (!qrToken) {
            return res.status(400).json({ message: 'QR Token wajib disertakan' });
        }

        const qrSession = await (prisma as any).dynamicQrSession.findUnique({
            where: { qrToken }
        });

        if (!qrSession) {
            return res.status(400).json({ message: '❌ QR Code tidak valid atau sudah kadaluarsa.' });
        }

        if (new Date() > new Date(qrSession.expiresAt)) {
            return res.status(400).json({ message: '⏱️ QR Code sudah berganti. Silakan scan QR terbaru di layar HP Guru.' });
        }

        // Validasi: Siswa hanya dapat scan presensi di rombel kelasnya sendiri
        if (user.className && qrSession.className && user.className.trim().toUpperCase() !== qrSession.className.trim().toUpperCase()) {
            return res.status(403).json({
                message: `❌ Presensi Ditolak! Anda terdaftar di kelas ${user.className}. Anda tidak dapat melakukan presensi di kelas ${qrSession.className}.`
            });
        }

        const startOfDay = new Date();
        startOfDay.setHours(0, 0, 0, 0);
        const endOfDay = new Date();
        endOfDay.setHours(23, 59, 59, 999);

        const existingAttendance = await prisma.attendance.findFirst({
            where: {
                userId: user.id,
                type: 'CLASS_ATTENDANCE',
                scanTime: {
                    gte: startOfDay,
                    lte: endOfDay
                },
                note: { contains: qrSession.className }
            }
        });

        if (!existingAttendance) {
            await prisma.attendance.create({
                data: {
                    userId: user.id,
                    type: 'CLASS_ATTENDANCE',
                    method: 'QR_DYNAMIC_TEACHER',
                    status: 'PRESENT',
                    note: 'Presensi Matpel: ' + qrSession.subjectName + ' (' + qrSession.className + ') - Dinamis 10s'
                }
            });
        }

        res.json({
            message: '✅ Berhasil Presensi di Kelas ' + qrSession.className + ' - ' + qrSession.subjectName + '! Status: HADIR TEPAT WAKTU.',
            className: qrSession.className,
            studentName: user.name,
            timestamp: new Date().toISOString()
        });
    } catch (error) {
        console.error('Error recording QR scan:', error);
        res.status(500).json({ message: 'Gagal memproses presensi scan QR' });
    }
};

export const quickMarkAllAttendance = async (req: Request, res: Response) => {
    try {
        const { className, subjectName } = req.body;

        if (!className) {
            return res.status(400).json({ message: 'Kelas wajib dipilih' });
        }

        const students = await prisma.user.findMany({
            where: { role: 'STUDENT', className }
        });

        if (students.length === 0) {
            return res.status(404).json({ message: 'Tidak ada data siswa di kelas ' + className });
        }

        for (const s of students) {
            await prisma.attendance.create({
                data: {
                    userId: s.id,
                    type: 'CLASS_ATTENDANCE',
                    method: 'ONE_CLICK_TEACHER',
                    status: 'PRESENT',
                    note: 'Presensi 1-Klik Guru: ' + (subjectName || 'Matpel') + ' (' + className + ')'
                }
            });
        }

        res.json({
            message: '✅ Sukses! Seluruh ' + students.length + ' siswa di kelas ' + className + ' telah ditandai HADIR.',
            totalMarked: students.length,
            className
        });
    } catch (error) {
        console.error('Error quick mark attendance:', error);
        res.status(500).json({ message: 'Gagal melakukan absensi 1-klik' });
    }
};

export const updateSingleStudentAttendance = async (req: Request, res: Response) => {
    try {
        const { studentId, status, note, className } = req.body;
        if (!studentId || !status) {
            return res.status(400).json({ message: 'studentId dan status wajib diisi' });
        }

        await prisma.attendance.create({
            data: {
                userId: studentId,
                type: 'CLASS_ATTENDANCE',
                method: 'MANUAL_TEACHER',
                status,
                note: note || 'Presensi Guru di Kelas ' + (className || '-')
            }
        });

        res.json({ message: '✅ Status absensi siswa berhasil diubah menjadi: ' + status });
    } catch (error) {
        console.error('Error updating single attendance:', error);
        res.status(500).json({ message: 'Gagal memperbarui absensi siswa' });
    }
};

export const createTeachingJournal = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { className, subjectName, topic, competencyCode, description, photoUrl, presentCount, absentCount, lateCount } = req.body;

        if (!className || !topic || !description) {
            return res.status(400).json({ message: 'Kelas, Topik Materi, dan Uraian Jurnal wajib diisi' });
        }

        const journal = await (prisma as any).teacherJournal.create({
            data: {
                teacherId: user?.id || 'teacher-1',
                teacherName: user?.name || 'Guru Pengampu',
                className,
                subjectName: subjectName || 'Mata Pelajaran',
                topic,
                competencyCode: competencyCode || 'KD 3.1',
                description,
                photoUrl: photoUrl || null,
                presentCount: Number(presentCount) || 30,
                absentCount: Number(absentCount) || 0,
                lateCount: Number(lateCount) || 0
            }
        });

        res.json({
            message: '📘 Jurnal mengajar harian berhasil disimpan ke sistem administrasi sertifikasi guru!',
            journal
        });
    } catch (error) {
        console.error('Error creating teaching journal:', error);
        res.status(500).json({ message: 'Gagal menyimpan jurnal mengajar' });
    }
};

export const getTeachingJournals = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const journals = await (prisma as any).teacherJournal.findMany({
            where: { teacherId: user?.id || 'teacher-1' },
            orderBy: { teachingDate: 'desc' }
        });

        res.json(journals);
    } catch (error) {
        console.error('Error fetching teaching journals:', error);
        res.status(500).json({ message: 'Gagal memuat riwayat jurnal' });
    }
};

export const broadcastToClass = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { className, title, message } = req.body;

        if (!className || !title || !message) {
            return res.status(400).json({ message: 'Kelas, Judul, dan Pesan Pengumuman wajib diisi' });
        }

        const announcement = await prisma.announcement.create({
            data: {
                title: '[' + className + '] ' + title,
                content: message,
                category: 'PENTING',
                author: 'Guru Matpel (' + (user?.name || 'Guru') + ')',
                isPinned: true
            }
        });

        res.json({
            message: '📢 Pengumuman berhasil dibroadcast langsung ke seluruh siswa Kelas ' + className + '!',
            announcement
        });
    } catch (error) {
        console.error('Error broadcasting announcement:', error);
        res.status(500).json({ message: 'Gagal mengirim pengumuman kelas' });
    }
};

// 7. Modul E-File & Dokumen Guru Mandiri
// 7. Modul E-File & Dokumen Guru (Dua Arah: File Saya & File Masuk)
export const getTeacherEFiles = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        if (!user || !user.id) {
            return res.status(401).json({ message: 'Unauthorized' });
        }

        const files = await prisma.eFile.findMany({
            where: { userId: user.id },
            orderBy: { uploadedAt: 'desc' }
        });

        const mapItem = (f: any) => ({
            id: f.id,
            title: f.title,
            category: f.category,
            fileUrl: f.fileUrl,
            fileSize: f.fileSize || '1.0 MB',
            fileType: f.fileType || (f.fileUrl.endsWith('.pdf') ? 'PDF' : 'IMAGE'),
            description: f.description || '',
            isFromAdmin: Boolean(f.isFromAdmin),
            senderName: f.senderName || (f.isFromAdmin ? 'Operator Sekolah' : user.name),
            source: f.source || 'Tata Usaha',
            period: f.period || 'Insidental',
            isRead: Boolean(f.isRead),
            readAt: f.readAt ? f.readAt.toISOString() : null,
            uploadedAt: f.uploadedAt.toISOString()
        });

        const myFiles = files.filter(f => !f.isFromAdmin).map(mapItem);
        const incomingFiles = files.filter(f => Boolean(f.isFromAdmin)).map(mapItem);
        const unreadCount = incomingFiles.filter(f => !f.isRead).length;

        res.json({
            success: true,
            teacherName: user.name,
            count: files.length,
            unreadIncomingCount: unreadCount,
            myFiles,
            incomingFiles,
            files: files.map(mapItem) // Full backward compatibility
        });
    } catch (error) {
        console.error('Error fetching teacher e-files:', error);
        res.status(500).json({ message: 'Gagal memuat berkas e-file guru' });
    }
};

export const markTeacherEFileAsRead = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const id = String(req.params.id);
        if (!user || !user.id) {
            return res.status(401).json({ message: 'Unauthorized' });
        }

        await prisma.eFile.updateMany({
            where: { id, userId: user.id },
            data: { isRead: true, readAt: new Date() }
        });

        res.json({
            success: true,
            message: '✅ Berkas telah ditandai sudah dibaca'
        });
    } catch (error: any) {
        console.error('Error marking e-file as read:', error);
        res.status(500).json({ message: 'Gagal menandai berkas: ' + error.message });
    }
};

export const uploadTeacherEFile = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        if (!user || !user.id) {
            return res.status(401).json({ message: 'Unauthorized' });
        }

        const {
            title,
            category,
            fileBase64,
            fileName,
            description,
            period,
            source,
            targetType,
            targetTeacherId,
            targetClass,
            teacherId
        } = req.body;

        if (!title || !fileBase64) {
            return res.status(400).json({ message: 'Judul dokumen dan file wajib diisi.' });
        }

        // Tentukan daftar target ID penerima (SINGLE, ALL, atau CLASS)
        let targetTeacherIds: string[] = [];
        const isFromAdmin = user.role === 'ADMIN' || user.role === 'OPERATOR';

        if (isFromAdmin) {
            if (targetType === 'ALL') {
                const allTeachers = await prisma.user.findMany({
                    where: { role: 'TEACHER' },
                    select: { id: true }
                });
                targetTeacherIds = allTeachers.map(t => t.id);
            } else if (targetType === 'CLASS' && targetClass) {
                const isTingkat = targetClass.startsWith('TINGKAT_') || targetClass === 'VII' || targetClass === 'VIII' || targetClass === 'IX';
                const gradePrefix = targetClass.replace('TINGKAT_', '');
                
                let whereClause: any = { role: 'TEACHER' };
                if (isTingkat) {
                    whereClause.OR = [
                        { teachingClasses: { contains: gradePrefix } },
                        { className: { startsWith: gradePrefix } },
                        // If teacher has no specific class set, include all active teachers so nobody misses the announcement
                        { teachingClasses: null }
                    ];
                } else {
                    whereClause.OR = [
                        { teachingClasses: { contains: targetClass } },
                        { className: targetClass }
                    ];
                }

                let classTeachers = await prisma.user.findMany({
                    where: whereClause,
                    select: { id: true }
                });

                // Fallback to all teachers if none specifically matched
                if (classTeachers.length === 0) {
                    classTeachers = await prisma.user.findMany({
                        where: { role: 'TEACHER' },
                        select: { id: true }
                    });
                }
                targetTeacherIds = classTeachers.map(t => t.id);
            } else if (targetTeacherId || teacherId) {
                targetTeacherIds = [String(targetTeacherId || teacherId)];
            } else {
                targetTeacherIds = [user.id];
            }
        } else {
            targetTeacherIds = [user.id];
        }

        if (targetTeacherIds.length === 0) {
            return res.status(404).json({ message: 'Tidak ditemukan guru penerima untuk target ini.' });
        }

        const path = await import('path');
        const fs = await import('fs');

        const uploadDir = path.resolve(process.cwd(), 'uploads/teacher_files');
        if (!fs.existsSync(uploadDir)) {
            fs.mkdirSync(uploadDir, { recursive: true });
        }

        const ext = path.extname(fileName || '.pdf') || '.pdf';
        const isPdf = ext.toLowerCase() === '.pdf';
        const safeName = `efile_${Date.now()}_${Math.random().toString(36).substring(7)}${ext}`;
        const targetPath = path.join(uploadDir, safeName);
        const cleanBase64 = String(fileBase64).replace(/^data:.*?;base64,/, '');
        const buffer = Buffer.from(cleanBase64, 'base64');
        fs.writeFileSync(targetPath, buffer);

        const sizeInMb = (buffer.length / (1024 * 1024)).toFixed(1);
        const sizeStr = buffer.length > 1024 * 1024 ? `${sizeInMb} MB` : `${Math.round(buffer.length / 1024)} KB`;

        const senderInfo = isFromAdmin ? (user.name || 'Operator Sekolah') : user.name;
        const docSource = source || (isFromAdmin ? 'Tata Usaha' : 'Mandiri Guru');
        const docPeriod = period || 'Insidental';

        const createdFiles = [];
        for (const tId of targetTeacherIds) {
            const newFile = await prisma.eFile.create({
                data: {
                    userId: tId,
                    title,
                    category: category || (isFromAdmin ? 'SURAT_TUGAS' : 'MODUL_AJAR'),
                    fileUrl: `/uploads/teacher_files/${safeName}`,
                    fileSize: sizeStr,
                    fileType: isPdf ? 'PDF' : 'IMAGE',
                    description: description || '',
                    isFromAdmin: isFromAdmin,
                    senderName: senderInfo,
                    source: docSource,
                    period: docPeriod,
                    isRead: !isFromAdmin // File mandiri guru otomatis dianggap sudah dibaca oleh diri sendiri
                }
            });
            createdFiles.push(newFile);
        }

        res.json({
            success: true,
            message: isFromAdmin
                ? `✅ Berkas "${title}" berhasil didistribusikan ke ${createdFiles.length} Guru!`
                : `✅ Berkas "${title}" berhasil disimpan ke File Saya!`,
            count: createdFiles.length,
            data: createdFiles[0]
        });
    } catch (error: any) {
        console.error('Error uploading teacher e-file:', error);
        res.status(500).json({ message: 'Gagal mengunggah berkas guru: ' + error.message });
    }
};

export const deleteTeacherEFile = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const id = String(req.params.id);

        if (!user || !user.id) {
            return res.status(401).json({ message: 'Unauthorized' });
        }

        const efile = await prisma.eFile.findUnique({ where: { id } });
        if (!efile) {
            return res.status(404).json({ message: 'Dokumen tidak ditemukan.' });
        }

        if (efile.userId !== user.id && user.role !== 'ADMIN') {
            return res.status(403).json({ message: 'Anda tidak memiliki hak untuk menghapus dokumen ini.' });
        }

        try {
            const path = await import('path');
            const fs = await import('fs');
            const fullPath = path.resolve(process.cwd(), efile.fileUrl.replace(/^\//, ''));
            if (fs.existsSync(fullPath)) {
                fs.unlinkSync(fullPath);
            }
        } catch (e) {
            console.warn('File removal warning:', e);
        }

        await prisma.eFile.delete({ where: { id } });

        res.json({
            success: true,
            message: '🗑️ Dokumen guru berhasil dihapus dari E-File.'
        });
    } catch (error: any) {
        console.error('Error deleting teacher e-file:', error);
        res.status(500).json({ message: 'Gagal menghapus dokumen guru: ' + error.message });
    }
};

// 1. Mengambil Daftar Kelas yang Diampu Guru Beserta Jumlah Siswa
export const getTeacherClasses = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const todayOnly = req.query.todayOnly === 'true' || req.query.today === 'true';
        let classesList: string[] = [];

        if (todayOnly) {
            const dayNames = ['Minggu', 'Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat', 'Sabtu'];
            const todayDayName = dayNames[new Date().getDay()];

            // Cari kelas yang dijadwalkan diajar oleh guru ini hari ini
            const todaySchedules = await prisma.classPeriodSchedule.findMany({
                where: {
                    day: todayDayName,
                    OR: [
                        { teacherId: user.id },
                        { teacherName: { contains: user.name } }
                    ]
                }
            });

            const scheduledClasses = Array.from(new Set(todaySchedules.map(s => s.className)));
            if (scheduledClasses.length > 0) {
                classesList = scheduledClasses;
            }
        }

        if (classesList.length === 0) {
            if (user.teachingClasses) {
                classesList = user.teachingClasses.split(',').map((c: string) => c.trim()).filter(Boolean);
            }
            if (user.className && !classesList.includes(user.className)) {
                classesList.unshift(user.className);
            }
            if (classesList.length === 0) {
                classesList = ['VII-A', 'VII-B', 'VII-C'];
            }
        }

        const classesWithStats = await Promise.all(classesList.map(async (cls) => {
            const studentCount = await prisma.user.count({
                where: { role: 'STUDENT', className: cls }
            });
            const isHomeroom = user.className === cls;
            return {
                className: cls,
                subject: user.teachingSubject || 'Matematika',
                totalStudents: studentCount || 30,
                isHomeroom,
                roleLabel: isHomeroom ? 'Wali Kelas & Guru Pengampu' : 'Guru Pengampu'
            };
        }));

        res.json({
            success: true,
            teacherName: user.name,
            subject: user.teachingSubject || 'Matematika',
            homeroomClass: user.className || null,
            classes: classesWithStats
        });
    } catch (error) {
        console.error('Error fetching teacher classes:', error);
        res.status(500).json({ success: false, message: 'Gagal memuat kelas yang diampu' });
    }
};

// 2. Mengambil Seluruh Siswa Berdasarkan Kelas Rombel
export const getStudentsByClass = async (req: Request, res: Response) => {
    try {
        const className = String(req.params.className || req.query.className || 'VII-A');
        const students = await prisma.user.findMany({
            where: { role: 'STUDENT', className },
            orderBy: { name: 'asc' },
            select: {
                id: true,
                name: true,
                username: true,
                nisn: true,
                className: true,
                gender: true,
                points: true,
                latestHeightCm: true,
                latestWeightKg: true,
                bloodType: true,
                parentPhone: true,
                fatherName: true,
                motherName: true
            }
        });

        res.json({
            success: true,
            className,
            totalStudents: students.length,
            students
        });
    } catch (error) {
        console.error('Error fetching students by class:', error);
        res.status(500).json({ success: false, message: 'Gagal memuat daftar siswa' });
    }
};

// 3. Ringkasan Khusus Guru Wali Kelas
export const getHomeroomSummary = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        let homeroomClass = user.className;

        if (!homeroomClass) {
            // Cek apakah guru ini terdaftar sebagai wali kelas di tabel siswa
            const sampleStudent = await prisma.user.findFirst({
                where: {
                    role: 'STUDENT',
                    OR: [
                        { homeroomTeacher: { contains: user.name } },
                        { homeroomTeacher: { contains: user.username } }
                    ]
                },
                select: { className: true }
            });
            if (sampleStudent?.className) {
                homeroomClass = sampleStudent.className;
            }
        }

        // Jika masih null, cek tabel Class jika ada
        if (!homeroomClass) {
            const classObj = await prisma.class.findFirst({
                where: {
                    name: user.teachingClasses ? { in: user.teachingClasses.split(',').map((c: string) => c.trim()) } : undefined
                }
            });
            if (classObj) homeroomClass = classObj.name;
        }

        if (!homeroomClass) {
            return res.json({
                success: true,
                isHomeroom: false,
                message: 'Guru ini tidak ditugaskan sebagai wali kelas.'
            });
        }

        const students = await prisma.user.findMany({
            where: { role: 'STUDENT', className: homeroomClass },
            orderBy: { name: 'asc' },
            select: {
                id: true,
                name: true,
                nisn: true,
                nis: true,
                points: true,
                gender: true,
                parentPhone: true,
                fatherName: true,
                motherName: true,
                address: true,
                bloodType: true,
                profilePicUrl: true
            }
        });

        // Cek presensi hari ini untuk setiap siswa di kelas
        const todayStart = new Date();
        todayStart.setHours(0, 0, 0, 0);
        const todayEnd = new Date();
        todayEnd.setHours(23, 59, 59, 999);

        const todayAttendances = await prisma.attendance.findMany({
            where: {
                userId: { in: students.map(s => s.id) },
                date: { gte: todayStart, lte: todayEnd }
            }
        });

        const attMap = new Map();
        todayAttendances.forEach(a => attMap.set(a.userId, a));

        const enrichedStudents = students.map(s => {
            const att = attMap.get(s.id);
            return {
                ...s,
                todayStatus: att ? att.status : 'BELUM',
                todayInTime: att?.inTime || null,
                todayOutTime: att?.outTime || null
            };
        });

        // Cek permohonan izin orang tua yang pending
        const pendingLeaves = await prisma.studentLeaveRequest.findMany({
            where: {
                className: homeroomClass,
                status: 'PENDING'
            },
            orderBy: { createdAt: 'desc' }
        });

        res.json({
            success: true,
            isHomeroom: true,
            homeroomClass,
            totalStudents: enrichedStudents.length,
            pendingLeavesCount: pendingLeaves.length,
            pendingLeaves,
            students: enrichedStudents
        });
    } catch (error) {
        console.error('Error fetching homeroom summary:', error);
        res.status(500).json({ success: false, message: 'Gagal memuat ringkasan wali kelas' });
    }
};

// 4. Ringkasan Khusus Guru Piket Hari Ini
export const getPiketSummary = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const now = new Date();
        const currentDay = now.getDay();

        // Cari jadwal piket hari ini (berdasarkan teacherId atau kecocokan nama/username)
        const piketToday = await prisma.piketSchedule.findFirst({
            where: {
                dayOfWeek: currentDay,
                isActive: true,
                OR: [
                    { teacherId: user.id },
                    { teacherName: { contains: user.name } },
                    { teacherName: { contains: user.username } }
                ]
            }
        });

        const isTaggedPiket = Boolean(piketToday) || user.role === 'PIKET' || user.role === 'GURU_PIKET';

        // Ambil laporan jam kosong hari ini
        const todayStart = new Date();
        todayStart.setHours(0, 0, 0, 0);

        const openEmptyClasses = await prisma.emptyClassReport.findMany({
            where: {
                createdAt: { gte: todayStart },
                status: 'PENDING'
            },
            orderBy: { createdAt: 'desc' }
        });

        res.json({
            success: true,
            isPiketToday: isTaggedPiket,
            piketDetails: piketToday || null,
            openEmptyClassesCount: openEmptyClasses.length,
            openEmptyClasses
        });
    } catch (error) {
        console.error('Error fetching piket summary:', error);
        res.status(500).json({ success: false, message: 'Gagal memuat ringkasan piket' });
    }
};

// 5. Riwayat Log Broadcast Pengumuman Guru
export const getTeacherBroadcastHistory = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const announcements = await prisma.announcement.findMany({
            where: {
                OR: [
                    { author: { contains: user.name } },
                    { author: { contains: 'Guru Matpel' } }
                ]
            },
            orderBy: { createdAt: 'desc' },
            take: 30
        });

        res.json({
            success: true,
            broadcasts: announcements
        });
    } catch (error) {
        console.error('Error fetching broadcast history:', error);
        res.status(500).json({ success: false, message: 'Gagal memuat riwayat broadcast' });
    }
};

// 6. Rekap Presensi Khusus Guru Mapel (Berdasarkan Mata Pelajaran yang Diampu)
export const getTeacherSubjectAttendanceRecap = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const dbTeacher = await prisma.user.findUnique({ where: { id: user.id } });
        const teachingSubject = dbTeacher?.teachingSubject || user.teachingSubject || 'Matematika';

        let classList: string[] = [];
        if (dbTeacher?.teachingClasses) {
            classList = dbTeacher.teachingClasses.split(',').map((c: string) => c.trim()).filter(Boolean);
        }
        if (dbTeacher?.className && !classList.includes(dbTeacher.className)) {
            classList.unshift(dbTeacher.className);
        }
        if (classList.length === 0) {
            classList = ['VII-A', 'VII-B', 'VII-C', 'VII-D'];
        }

        // Ambil data dari sesi KBM jam pelajaran yang pernah dibuat oleh guru ini atau untuk mapel ini
        const sessions = await (prisma as any).classPeriodSession.findMany({
            where: {
                OR: [
                    { teacherId: user.id },
                    { subjectName: teachingSubject }
                ]
            },
            include: {
                attendances: true
            },
            orderBy: { inTime: 'desc' },
            take: 100
        });

        // Agregasi per kelas
        const recapByClass: any = {};
        for (const cls of classList) {
            recapByClass[cls] = {
                className: cls,
                subject: teachingSubject,
                subjectName: teachingSubject,
                totalSessions: 0,
                presentCount: 0,
                absentCount: 0,
                lateCount: 0,
                sickCount: 0,
                permissionCount: 0,
                percentage: 100
            };
        }

        for (const sess of sessions) {
            if (!recapByClass[sess.className]) {
                recapByClass[sess.className] = {
                    className: sess.className,
                    subject: sess.subjectName || teachingSubject,
                    subjectName: sess.subjectName || teachingSubject,
                    totalSessions: 0,
                    presentCount: 0,
                    absentCount: 0,
                    lateCount: 0,
                    sickCount: 0,
                    permissionCount: 0,
                    percentage: 100
                };
            }
            recapByClass[sess.className].totalSessions++;
            for (const att of sess.attendances || []) {
                const st = (att.status || 'PRESENT').toUpperCase();
                if (st === 'PRESENT' || st === 'AUTO_PRESENT' || st === 'HADIR') {
                    recapByClass[sess.className].presentCount++;
                } else if (st === 'SICK' || st === 'SAKIT') {
                    recapByClass[sess.className].sickCount++;
                } else if (st === 'PERMISSION' || st === 'IZIN') {
                    recapByClass[sess.className].permissionCount++;
                } else if (st === 'LATE' || st === 'TERLAMBAT') {
                    recapByClass[sess.className].lateCount++;
                } else {
                    recapByClass[sess.className].absentCount++;
                }
            }
        }

        // Hitung persentase kehadiran per kelas
        const classResults = Object.values(recapByClass).map((c: any) => {
            const totalHadir = c.presentCount;
            const totalTidakHadir = c.absentCount + c.sickCount + c.permissionCount;
            const totalAbsensi = totalHadir + totalTidakHadir;
            const pct = totalAbsensi > 0 ? Math.round((totalHadir / totalAbsensi) * 100) : (c.totalSessions > 0 ? 100 : 0);
            return {
                ...c,
                percentage: pct
            };
        });

        res.json({
            success: true,
            teacherName: dbTeacher?.name || user.name,
            subject: teachingSubject,
            subjectName: teachingSubject,
            classes: classResults
        });
    } catch (error) {
        console.error('Error fetching subject attendance recap:', error);
        res.status(500).json({ success: false, message: 'Gagal memuat rekap presensi guru mapel' });
    }
};

function calculateDistanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
    const R = 6371e3; // meters
    const phi1 = (lat1 * Math.PI) / 180;
    const phi2 = (lat2 * Math.PI) / 180;
    const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
    const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;
    const a =
        Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
        Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
}

/**
 * Endpoint Presensi Geolocation Harian Guru (Datang & Pulang via Tombol 1-Tap GPS, Tanpa Scan Kamera)
 */
export const teacherGeolocationAttendance = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { lat, lng, isFakeGps } = req.body;

        if (isFakeGps === true) {
            return res.status(403).json({
                success: false,
                message: '🚨 Presensi Ditolak! Terdeteksi Fake GPS / Mock Location pada perangkat Anda.'
            });
        }

        // Koordinat Kampus SMPN 1 Boyolangu
        const schoolLat = -8.125506;
        const schoolLng = 111.893526;
        const maxRadiusMeters = 200; // Radius toleransi lingkungan sekolah

        if (lat != null && lng != null && String(lat) !== '' && String(lng) !== '') {
            const distance = calculateDistanceMeters(Number(lat), Number(lng), schoolLat, schoolLng);
            if (distance > maxRadiusMeters) {
                return res.status(403).json({
                    success: false,
                    message: `📍 Presensi Ditolak! Anda terdeteksi berada di luar area SMPN 1 Boyolangu (Jarak: ${Math.round(distance)} meter). Presensi hanya dapat dilakukan di lingkungan sekolah.`
                });
            }
        }

        // Ambil rentang waktu hari ini (00:00 - 23:59 WIB)
        const now = new Date();
        const startOfDay = new Date(now);
        startOfDay.setHours(0, 0, 0, 0);
        const endOfDay = new Date(now);
        endOfDay.setHours(23, 59, 59, 999);

        // Cari presensi guru hari ini
        const todayAttendances = await prisma.attendance.findMany({
            where: {
                userId: user.id,
                scanTime: {
                    gte: startOfDay,
                    lte: endOfDay
                }
            },
            orderBy: { scanTime: 'asc' }
        });

        const hasIn = todayAttendances.find(a => a.type === 'GATE_IN');
        const hasOut = todayAttendances.find(a => a.type === 'GATE_OUT');

        const timeStr = now.toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' }) + ' WIB';

        if (!hasIn) {
            // Catat Presensi DATANG
            const newAtt = await prisma.attendance.create({
                data: {
                    userId: user.id,
                    type: 'GATE_IN',
                    method: 'GPS',
                    status: 'PRESENT',
                    scanTime: now,
                    lat: lat ? Number(lat) : schoolLat,
                    lng: lng ? Number(lng) : schoolLng,
                    isFakeGps: Boolean(isFakeGps),
                    note: `Presensi Datang Guru (1-Tap Geolocation) • ${timeStr}`
                }
            });

            return res.json({
                success: true,
                type: 'GATE_IN',
                message: `✔ Presensi DATANG Berhasil! Tercatat pada ${timeStr} via Geolocation GPS.`,
                timeStr,
                attendance: newAtt
            });
        } else if (!hasOut) {
            // Catat Presensi PULANG
            const newAtt = await prisma.attendance.create({
                data: {
                    userId: user.id,
                    type: 'GATE_OUT',
                    method: 'GPS',
                    status: 'PRESENT',
                    scanTime: now,
                    lat: lat ? Number(lat) : schoolLat,
                    lng: lng ? Number(lng) : schoolLng,
                    isFakeGps: Boolean(isFakeGps),
                    note: `Presensi Pulang Guru (1-Tap Geolocation) • ${timeStr}`
                }
            });

            return res.json({
                success: true,
                type: 'GATE_OUT',
                message: `✔ Presensi PULANG Berhasil! Tercatat pada ${timeStr} via Geolocation GPS.`,
                timeStr,
                attendance: newAtt
            });
        } else {
            return res.json({
                success: false,
                message: 'Presensi kehadiran hari ini sudah lengkap (Datang & Pulang).',
                isCompleted: true
            });
        }
    } catch (error: any) {
        console.error('Error in teacherGeolocationAttendance:', error);
        return res.status(500).json({ success: false, message: 'Gagal mencatat presensi guru: ' + error.message });
    }
};

/**
 * Status Presensi Harian Guru Hari Ini (Untuk APK Guru)
 */
export const getTeacherTodayAttendance = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const now = new Date();
        const startOfDay = new Date(now);
        startOfDay.setHours(0, 0, 0, 0);
        const endOfDay = new Date(now);
        endOfDay.setHours(23, 59, 59, 999);

        const todayAttendances = await prisma.attendance.findMany({
            where: {
                userId: user.id,
                scanTime: {
                    gte: startOfDay,
                    lte: endOfDay
                }
            },
            orderBy: { scanTime: 'asc' }
        });

        const inRecord = todayAttendances.find(a => a.type === 'GATE_IN');
        const outRecord = todayAttendances.find(a => a.type === 'GATE_OUT');

        const inTimeStr = inRecord ? new Date(inRecord.scanTime).toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' }) + ' WIB' : null;
        const outTimeStr = outRecord ? new Date(outRecord.scanTime).toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' }) + ' WIB' : null;

        return res.json({
            success: true,
            hasIn: !!inRecord,
            inTime: inTimeStr,
            hasOut: !!outRecord,
            outTime: outTimeStr,
            status: inRecord ? (outRecord ? 'COMPLETED' : 'CHECKED_IN') : 'NOT_CHECKED_IN'
        });
    } catch (error: any) {
        console.error('Error in getTeacherTodayAttendance:', error);
        return res.status(500).json({ success: false, message: 'Gagal memuat status presensi guru' });
    }
};


