import { Request, Response } from 'express';
import prisma from '../utils/db';
import { logAudit } from '../services/auditLogger';
import fs from 'fs';
import path from 'path';

// 1. Orang Tua Mengajukan Izin / Sakit Anak
export const createParentLeaveRequest = async (req: Request, res: Response) => {
    try {
        const { studentId, category, startDate, endDate, reason, attachmentUrl } = req.body;
        const user = (req as any).user;

        if (!studentId || !category || !startDate || !reason) {
            return res.status(400).json({ message: 'Siswa, Kategori Izin, Tanggal, dan Alasan wajib diisi' });
        }

        const student = await prisma.user.findUnique({
            where: { id: studentId }
        });

        if (!student) {
            return res.status(404).json({ message: 'Data siswa tidak ditemukan' });
        }

        // Validasi BOLA/IDOR: Orang tua hanya bisa mengajukan izin untuk anak kandungnya sendiri
        if (user && user.role === 'PARENT') {
            const cleanNisn = (user.username || '').replace(/^[Pp]/, '').trim();
            const isChild = await prisma.user.findFirst({
                where: {
                    id: studentId,
                    role: 'STUDENT',
                    OR: [
                        { nisn: cleanNisn },
                        { username: cleanNisn },
                        ...(user.nisn ? [{ nisn: user.nisn }] : []),
                        { parentPhone: user.username },
                        { fatherName: user.name },
                        { motherName: user.name }
                    ]
                }
            });
            if (!isChild) {
                return res.status(403).json({ message: 'Akses Ditolak: Anda hanya dapat mengajukan permohonan izin untuk ananda sendiri.' });
            }
        }

        const parentName = user ? user.name : (student.fatherName || student.motherName || 'Orang Tua / Wali');
        const parentPhone = student.parentPhone || '';

        const start = new Date(startDate);
        const end = endDate ? new Date(endDate) : new Date(startDate);

        const diffMillis = Math.abs(end.getTime() - start.getTime());
        const diffDays = Math.ceil(diffMillis / (1000 * 60 * 60 * 24)) + 1;
        const isSickCategory = category === 'SICK' || category === 'SAKIT';

        // 1. Validasi Sakit >= 3 Hari Wajib Surat Dokter
        if (isSickCategory && diffDays >= 3 && !attachmentUrl) {
            return res.status(400).json({
                message: `Permohonan izin sakit selama 3 hari atau lebih (${diffDays} hari) wajib melampirkan foto fisik Surat Keterangan Dokter.`
            });
        }

        // 2. Validasi Perpanjangan Sakit (belum sembuh dalam 7 hari terakhir)
        if (isSickCategory && !attachmentUrl) {
            const sevenDaysAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000);
            const recentSick = await (prisma as any).studentLeaveRequest.findFirst({
                where: {
                    studentId: student.id,
                    category: { in: ['SICK', 'SAKIT'] },
                    status: { not: 'REJECTED' },
                    createdAt: { gte: sevenDaysAgo }
                }
            });
            if (recentSick) {
                return res.status(400).json({
                    message: 'Untuk perpanjangan izin sakit siswa (belum sembuh), Anda wajib melampirkan Surat Keterangan Dokter terbaru.'
                });
            }
        }

        let finalAttachmentUrl = attachmentUrl || null;
        if (finalAttachmentUrl && (finalAttachmentUrl.startsWith('data:image/') || finalAttachmentUrl.startsWith('data:application/pdf'))) {
            try {
                const uploadDir = path.join(process.cwd(), 'uploads', 'leaves');
                if (!fs.existsSync(uploadDir)) {
                    fs.mkdirSync(uploadDir, { recursive: true });
                }

                const matches = finalAttachmentUrl.match(/^data:(image\/[a-zA-Z0-9+]+|application\/pdf);base64,(.+)$/);
                if (matches) {
                    const mime = matches[1];
                    let ext = 'jpg';
                    if (mime === 'image/png') ext = 'png';
                    else if (mime === 'application/pdf') ext = 'pdf';
                    else if (mime === 'image/webp') ext = 'webp';

                    const buffer = Buffer.from(matches[2], 'base64');
                    const fileName = `leave_${Date.now()}_${Math.random().toString(36).substring(2, 8)}.${ext}`;
                    const filePath = path.join(uploadDir, fileName);
                    fs.writeFileSync(filePath, buffer);
                    finalAttachmentUrl = `/uploads/leaves/${fileName}`;
                }
            } catch (err) {
                console.error('Error saving leave request attachment:', err);
            }
        }

        const leaveReq = await (prisma as any).studentLeaveRequest.create({
            data: {
                studentId: student.id,
                studentName: student.name,
                className: student.className || 'VII-A',
                parentName,
                parentPhone,
                category: category || 'SICK',
                startDate: start,
                endDate: end,
                reason,
                attachmentUrl: finalAttachmentUrl,
                status: 'PENDING'
            }
        });


        await logAudit(req, 'PARENT_LEAVE_REQUEST', `Student: ${student.name}`, {
            category,
            startDate,
            endDate,
            parentName
        });

        res.json({
            success: true,
            message: `Permohonan ${category === 'SICK' ? 'Sakit' : 'Izin'} untuk ${student.name} berhasil diajukan dan sedang menunggu verifikasi Wali Kelas / Guru BK.`,
            leaveRequest: leaveReq
        });
    } catch (error) {
        console.error('Error creating leave request:', error);
        res.status(500).json({ message: 'Gagal mengajukan permohonan izin' });
    }
};

// 2. Mengambil Riwayat Izin untuk Orang Tua / Siswa
export const getParentLeaveRequests = async (req: Request, res: Response) => {
    try {
        const { studentId } = req.query;
        const user = (req as any).user;

        const whereCondition: any = {};
        if (user && user.role === 'STUDENT') {
            whereCondition.studentId = user.id;
        } else if (user && user.role === 'PARENT') {
            const cleanNisn = (user.username || '').replace(/^[Pp]/, '').trim();
            const children = await prisma.user.findMany({
                where: {
                    role: 'STUDENT',
                    OR: [
                        { nisn: cleanNisn },
                        { username: cleanNisn },
                        ...(user.nisn ? [{ nisn: user.nisn }] : []),
                        { parentPhone: user.username },
                        { fatherName: user.name },
                        { motherName: user.name }
                    ]
                },
                select: { id: true }
            });
            const allowedStudentIds = children.map(c => c.id);

            if (studentId) {
                if (!allowedStudentIds.includes(String(studentId))) {
                    return res.status(403).json({ message: 'Akses ditolak untuk data siswa ini' });
                }
                whereCondition.studentId = String(studentId);
            } else {
                whereCondition.studentId = { in: allowedStudentIds };
            }
        } else if (studentId) {
            whereCondition.studentId = String(studentId);
        }

        const list = await (prisma as any).studentLeaveRequest.findMany({
            where: whereCondition,
            orderBy: { createdAt: 'desc' }
        });

        res.json(list);
    } catch (error) {
        res.status(500).json({ message: 'Gagal memuat riwayat permohonan izin' });
    }
};

// 3. Mengambil Antrean Izin untuk Guru / Wali Kelas / Guru BK
export const getTeacherLeaveRequests = async (req: Request, res: Response) => {
    try {
        const { className, status } = req.query;
        const whereCondition: any = {};

        if (className && className !== 'ALL') {
            whereCondition.className = String(className);
        }
        if (status && status !== 'ALL') {
            whereCondition.status = String(status);
        }

        const list = await (prisma as any).studentLeaveRequest.findMany({
            where: whereCondition,
            orderBy: { createdAt: 'desc' }
        });

        res.json(list);
    } catch (error) {
        res.status(500).json({ message: 'Gagal memuat daftar verifikasi izin' });
    }
};

// 4. Verifikasi Permohonan Izin oleh Wali Kelas / Guru BK (Approve / Reject)
export const verifyLeaveRequest = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const { status, rejectionNote } = req.body; // 'APPROVED' or 'REJECTED'
        const user = (req as any).user;

        if (!status || (status !== 'APPROVED' && status !== 'REJECTED')) {
            return res.status(400).json({ message: 'Status verifikasi harus APPROVED atau REJECTED' });
        }

        const leaveReq = await (prisma as any).studentLeaveRequest.findUnique({
            where: { id }
        });

        if (!leaveReq) {
            return res.status(404).json({ message: 'Permohonan izin tidak ditemukan' });
        }

        const verifiedBy = user ? `${user.name} (${user.role})` : 'Wali Kelas / BK';

        const updated = await (prisma as any).studentLeaveRequest.update({
            where: { id },
            data: {
                status,
                verifiedBy,
                verifiedAt: new Date(),
                rejectionNote: status === 'REJECTED' ? (rejectionNote || 'Dokumen surat tidak lengkap') : null
            }
        });

        // JIKA DISETUJUI (APPROVED): Otomatis Masukkan ke Presensi Kelas (Attendance)
        if (status === 'APPROVED') {
            const attStatus = (leaveReq.category === 'SICK' || leaveReq.category === 'SAKIT') ? 'SICK' : 'PERMISSION';
            const noteText = `Izin Resmi Orang Tua (${leaveReq.parentName}) disetujui ${verifiedBy}: ${leaveReq.reason}`;

            // Generate tanggal dari start s.d end
            const curDate = new Date(leaveReq.startDate);
            const endDate = new Date(leaveReq.endDate);

            while (curDate <= endDate) {
                const dayStart = new Date(curDate);
                dayStart.setHours(0, 0, 0, 0);
                const dayEnd = new Date(curDate);
                dayEnd.setHours(23, 59, 59, 999);

                // Bersihkan record ABSENT/GATE_IN yang mungkin ada pada tanggal ini agar tidak duplikat
                await prisma.attendance.deleteMany({
                    where: {
                        userId: leaveReq.studentId,
                        scanTime: { gte: dayStart, lte: dayEnd }
                    }
                });

                // Buat record GATE_IN
                const inTime = new Date(curDate);
                inTime.setHours(7, 0, 0, 0);
                await prisma.attendance.create({
                    data: {
                        userId: leaveReq.studentId,
                        type: 'GATE_IN',
                        method: 'PARENT_PERMISSION',
                        status: attStatus,
                        scanTime: inTime,
                        note: noteText
                    }
                });

                // Buat record GATE_OUT agar presensi tuntas dan tidak divonis Alpa
                const outTime = new Date(curDate);
                outTime.setHours(14, 30, 0, 0);
                await prisma.attendance.create({
                    data: {
                        userId: leaveReq.studentId,
                        type: 'GATE_OUT',
                        method: 'PARENT_PERMISSION',
                        status: attStatus,
                        scanTime: outTime,
                        note: noteText
                    }
                });

                curDate.setDate(curDate.getDate() + 1);
            }

            // Sinkronisasi otomatis ke seluruh sesi jam pelajaran (ClassPeriodSession) kelas tersebut hari ini
            try {
                const todaySessions = await (prisma as any).classPeriodSession.findMany({
                    where: { className: leaveReq.className }
                });
                for (const sess of todaySessions) {
                    await (prisma as any).classPeriodAttendance.upsert({
                        where: {
                            sessionId_studentId: {
                                sessionId: sess.id,
                                studentId: leaveReq.studentId
                            }
                        },
                        update: {
                            status: attStatus,
                            method: 'PARENT_PERMISSION'
                        },
                        create: {
                            sessionId: sess.id,
                            studentId: leaveReq.studentId,
                            studentName: leaveReq.studentName,
                            className: leaveReq.className,
                            status: attStatus,
                            method: 'PARENT_PERMISSION'
                        }
                    });
                }
            } catch (sessErr) {
                console.warn('Sync to period sessions non-fatal:', sessErr);
            }
        }

        // 1. Kirim Notifikasi Balik Langsung ke SISWA di Aplikasi Mobile
        try {
            await prisma.notificationMessage.create({
                data: {
                    recipientId: leaveReq.studentId,
                    recipientRole: 'STUDENT',
                    studentId: leaveReq.studentId,
                    studentName: leaveReq.studentName,
                    className: leaveReq.className,
                    category: 'LEAVE_VERIFICATION',
                    title: status === 'APPROVED' ? '🎉 Surat Izin / Sakit Anda Telah Disetujui!' : '⚠️ Permohonan Izin Belum Disetujui',
                    message: status === 'APPROVED'
                        ? `Alhamdulillah, permohonan izin ${leaveReq.category === 'SICK' ? 'Sakit' : 'Izin'} Anda (${leaveReq.reason || 'Sesuai Surat Keterangan'}) telah diverifikasi dan DISETUJUI oleh ${verifiedBy}. Status presensi otomatis tercatat resmi di sistem absensi sekolah.`
                        : `Mohon maaf, permohonan izin Anda belum disetujui oleh ${verifiedBy}. Catatan: ${rejectionNote || 'Dokumen surat tidak lengkap'}.`
                }
            });
        } catch (stErr) {
            console.warn('Student notif non-fatal:', stErr);
        }

        // 2. Temukan akun user Parent yang terdaftar untuk dikirimi notifikasi
        let parentUserId: string | undefined;
        try {
            const studentUser = await prisma.user.findUnique({
                where: { id: leaveReq.studentId },
                select: { nisn: true, username: true, parentPhone: true, fatherName: true, motherName: true }
            });
            if (studentUser) {
                const parentUser = await prisma.user.findFirst({
                    where: {
                        role: 'PARENT',
                        OR: [
                            ...(studentUser.nisn ? [{ username: `P${studentUser.nisn}` }, { username: studentUser.nisn }, { nisn: studentUser.nisn }] : []),
                            ...(studentUser.parentPhone ? [{ username: studentUser.parentPhone }] : []),
                            ...(studentUser.fatherName ? [{ name: studentUser.fatherName }] : []),
                            ...(studentUser.motherName ? [{ name: studentUser.motherName }] : [])
                        ]
                    },
                    select: { id: true }
                });
                if (parentUser) parentUserId = parentUser.id;
            }
        } catch (pErr) {
            console.warn('Parent user lookup non-fatal:', pErr);
        }

        // Kirim Notifikasi Balik ke ORANG TUA / WALI MURID di Aplikasi Mobile
        try {
            await prisma.notificationMessage.create({
                data: {
                    recipientId: parentUserId || leaveReq.studentId,
                    recipientPhone: leaveReq.parentPhone,
                    recipientRole: 'PARENT',
                    studentId: leaveReq.studentId,
                    studentName: leaveReq.studentName,
                    className: leaveReq.className,
                    category: 'LEAVE_VERIFICATION',
                    title: status === 'APPROVED' ? '✅ Permohonan Izin Anak Disetujui Sekolah' : '❌ Permohonan Izin Anak Ditolak',
                    message: status === 'APPROVED'
                        ? `Alhamdulillah, permohonan izin ${leaveReq.category === 'SICK' ? 'Sakit' : 'Izin'} ananda ${leaveReq.studentName} (${leaveReq.className}) telah diverifikasi & DISETUJUI oleh ${verifiedBy}. Kehadiran ananda otomatis tercatat resmi di rekap presensi kelas.`
                        : `Mohon maaf, permohonan izin ananda ${leaveReq.studentName} belum dapat disetujui oleh sekolah. Catatan: ${rejectionNote || 'Dokumen surat tidak lengkap'}.`
                }
            });
        } catch (parErr) {
            console.warn('Parent notif non-fatal:', parErr);
        }

        // 3. Kirim Notifikasi ke Ketua Kelas & Pengurus Kelas (Untuk Rekap Manual Kelas)
        if (status === 'APPROVED') {
            try {
                await prisma.notificationMessage.create({
                    data: {
                        recipientRole: 'STUDENT',
                        className: leaveReq.className,
                        studentId: leaveReq.studentId,
                        studentName: leaveReq.studentName,
                        category: 'ATTENDANCE_MANUAL_RECAP',
                        title: '📋 Rekap Manual Presensi Kelas',
                        message: `Teman sekelas Anda, ${leaveReq.studentName} (${leaveReq.className}), telah resmi diverifikasi ${leaveReq.category === 'SICK' ? 'Sakit' : 'Izin'} oleh ${verifiedBy}. Silakan Ketua Kelas mencatat di buku presensi manual kelas.`
                    }
                });
            } catch (rkErr) {}
        }

        // 4. Kirim Notifikasi ke Wali Kelas yang membina rombel ini
        try {
            const waliKelas = await prisma.user.findFirst({
                where: {
                    role: 'TEACHER',
                    OR: [
                        { className: leaveReq.className },
                        { teachingClasses: { contains: leaveReq.className } }
                    ]
                }
            });
            if (waliKelas) {
                await prisma.notificationMessage.create({
                    data: {
                        recipientId: waliKelas.id,
                        recipientRole: 'TEACHER',
                        className: leaveReq.className,
                        studentId: leaveReq.studentId,
                        studentName: leaveReq.studentName,
                        category: 'ATTENDANCE',
                        title: `📋 Izin Siswa Diverifikasi: ${leaveReq.studentName}`,
                        message: `Permohonan izin ${leaveReq.category === 'SICK' ? 'Sakit' : 'Izin'} ananda ${leaveReq.studentName} (${leaveReq.className}) telah diverifikasi ${status === 'APPROVED' ? 'DISETUJUI' : 'DITOLAK'} oleh ${verifiedBy}. Status presensi otomatis tersinkronisasi ke jurnal mengajar guru.`
                    }
                });
            }
        } catch (wkErr) {
            console.warn('Wali kelas notif non-fatal:', wkErr);
        }

        // 5. SMART NOTIFIKASI KE SEMUA GURU MENGAJAR DI KELAS TERSEBUT PADA RENTANG HARI IZIN SAKIT
        if (status === 'APPROVED') {
            try {
                const dayNames = ['Minggu', 'Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat', 'Sabtu'];
                const cur = new Date(leaveReq.startDate);
                const end = new Date(leaveReq.endDate);
                const affectedDaysSet = new Set<string>();

                while (cur <= end) {
                    const dName = dayNames[cur.getDay()];
                    if (dName && dName !== 'Minggu') {
                        affectedDaysSet.add(dName);
                    }
                    cur.setDate(cur.getDate() + 1);
                }

                const affectedDays = Array.from(affectedDaysSet);

                // Cari jadwal pelajaran untuk kelas tersebut di hari-hari yang terdampak
                const schedules = await prisma.classPeriodSchedule.findMany({
                    where: {
                        className: leaveReq.className,
                        day: { in: affectedDays }
                    }
                });

                // Kumpulkan seluruh Guru Pengampu (Teacher ID / Teacher Name)
                const notifiedTeacherIds = new Set<string>();
                for (const sch of schedules) {
                    if (sch.teacherId && !notifiedTeacherIds.has(sch.teacherId)) {
                        notifiedTeacherIds.add(sch.teacherId);
                    }
                }

                // Tambahkan guru yang memiliki teachingClasses mengandung kelas ini jika belum ada jadwal spesifik
                const subjectTeachers = await prisma.user.findMany({
                    where: {
                        role: 'TEACHER',
                        teachingClasses: { contains: leaveReq.className }
                    },
                    select: { id: true, name: true, teachingSubject: true }
                });

                for (const t of subjectTeachers) {
                    notifiedTeacherIds.add(t.id);
                }

                const startDateFormatted = new Date(leaveReq.startDate).toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric' });
                const endDateFormatted = new Date(leaveReq.endDate).toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric' });
                const dateSpanStr = startDateFormatted === endDateFormatted ? startDateFormatted : `${startDateFormatted} s/d ${endDateFormatted}`;

                for (const teacherId of notifiedTeacherIds) {
                    await prisma.notificationMessage.create({
                        data: {
                            recipientId: teacherId,
                            recipientRole: 'TEACHER',
                            className: leaveReq.className,
                            studentId: leaveReq.studentId,
                            studentName: leaveReq.studentName,
                            category: 'ATTENDANCE_LEAVE',
                            title: `🏥 Siswa Izin Sakit: ${leaveReq.studentName} (${leaveReq.className})`,
                            message: `Pemberitahuan KBM: Ananda ${leaveReq.studentName} (${leaveReq.className}) izin ${leaveReq.category === 'SICK' ? 'SAKIT' : 'RESMI'} pada ${dateSpanStr}. Izin telah diverifikasi oleh ${verifiedBy}. Siswa tidak hadir pada jam pelajaran Anda.`
                        }
                    });
                }
            } catch (tNotifErr) {
                console.warn('Teacher period schedule notif non-fatal:', tNotifErr);
            }
        }

        await logAudit(req, 'VERIFY_LEAVE_REQUEST', `Student: ${leaveReq.studentName}`, {
            status,
            verifiedBy,
            requestId: id
        });

        res.json({
            success: true,
            message: status === 'APPROVED'
                ? `✅ Izin ${leaveReq.studentName} berhasil DISETUJUI dan otomatis tercatat di rekap absensi kelas.`
                : `❌ Izin ${leaveReq.studentName} DITOLAK. Alasan: ${rejectionNote || 'Dokumen tidak valid'}.`,
            leaveRequest: updated
        });
    } catch (error) {
        console.error('Error verifying leave request:', error);
        res.status(500).json({ message: 'Gagal memverifikasi permohonan izin' });
    }
};

// 4. Guru BK / Wali Kelas / Admin Menghapus Histori Permohonan Izin / Sakit
export const deleteLeaveRequest = async (req: Request, res: Response) => {
    try {
        const id = req.params.id as string;
        const user = (req as any).user;

        const leaveReq = await prisma.studentLeaveRequest.findUnique({
            where: { id }
        });

        if (!leaveReq) {
            return res.status(404).json({ message: 'Data surat izin/sakit tidak ditemukan' });
        }

        // Hapus file fisik lampiran jika tersimpan lokal di server
        if (leaveReq.attachmentUrl && leaveReq.attachmentUrl.startsWith('/uploads/leaves/')) {
            try {
                const filePath = path.join(process.cwd(), leaveReq.attachmentUrl);
                if (fs.existsSync(filePath)) {
                    fs.unlinkSync(filePath);
                }
            } catch (fileErr) {
                console.warn('Gagal menghapus file lampiran izin:', fileErr);
            }
        }

        await prisma.studentLeaveRequest.delete({
            where: { id }
        });

        await logAudit(req, 'DELETE_LEAVE_REQUEST', `Student: ${leaveReq.studentName}`, {
            requestId: id,
            category: leaveReq.category,
            deletedBy: user?.name || 'Guru BK/Admin'
        });

        res.json({
            success: true,
            message: `✅ Histori permohonan izin ananda ${leaveReq.studentName} berhasil dihapus dari sistem.`
        });
    } catch (error) {
        console.error('Error deleting leave request:', error);
        res.status(500).json({ message: 'Gagal menghapus histori surat izin' });
    }
};