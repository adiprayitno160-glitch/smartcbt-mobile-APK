import { Request, Response } from 'express';
import prisma from '../utils/db';
import path from 'path';
import fs from 'fs';

// 1. Guru Mengajukan Izin (Cuti, Sakit, Dinas Luar, Izin Pribadi)
export const createTeacherLeave = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { category, startDate, endDate, reason, affectedSchedules, assignmentDetails, attachmentBase64 } = req.body;

        if (!category || !startDate || !reason) {
            return res.status(400).json({ success: false, message: 'Kategori, Tanggal Mulai, dan Alasan wajib diisi.' });
        }

        let finalAttachmentUrl: string | null = null;
        if (attachmentBase64 && (attachmentBase64.startsWith('data:image/') || attachmentBase64.startsWith('data:application/pdf'))) {
            try {
                const uploadDir = path.join(process.cwd(), 'uploads', 'teacher_leaves');
                if (!fs.existsSync(uploadDir)) {
                    fs.mkdirSync(uploadDir, { recursive: true });
                }

                const matches = attachmentBase64.match(/^data:([a-zA-Z0-9/+-]+);base64,(.+)$/);
                if (matches) {
                    const mime = matches[1];
                    let ext = 'jpg';
                    if (mime.includes('png')) ext = 'png';
                    else if (mime.includes('pdf')) ext = 'pdf';

                    const buffer = Buffer.from(matches[2], 'base64');
                    const fileName = `izin_guru_${Date.now()}_${Math.random().toString(36).substring(2, 7)}.${ext}`;
                    const fullPath = path.join(uploadDir, fileName);
                    fs.writeFileSync(fullPath, buffer);
                    finalAttachmentUrl = `/uploads/teacher_leaves/${fileName}`;
                }
            } catch (fileErr) {
                console.warn('Gagal menyimpan file lampiran izin guru:', fileErr);
            }
        }

        const leave = await (prisma as any).teacherLeaveRequest.create({
            data: {
                teacherId: user.id,
                teacherName: user.name || 'Guru',
                category: category.toUpperCase(),
                startDate: new Date(startDate),
                endDate: endDate ? new Date(endDate) : new Date(startDate),
                reason,
                affectedSchedules: typeof affectedSchedules === 'object' ? JSON.stringify(affectedSchedules) : affectedSchedules,
                assignmentDetails: assignmentDetails || null,
                attachmentUrl: finalAttachmentUrl,
                status: 'PENDING'
            }
        });

        // Notifikasi ke seluruh operator sekolah
        try {
            await (prisma as any).notificationMessage.create({
                data: {
                    recipientRole: 'OPERATOR',
                    title: `📝 Pengajuan Izin Guru: ${user.name}`,
                    message: `Bpk/Ibu ${user.name} mengajukan izin (${category}) tanggal ${startDate}. Menunggu verifikasi operator.`,
                    category: 'TEACHER_LEAVE'
                }
            });
        } catch (notifErr) {}

        res.json({
            success: true,
            message: 'Permohonan izin berhasil diajukan dan sedang menunggu persetujuan Operator.',
            leave
        });
    } catch (error: any) {
        console.error('Error createTeacherLeave:', error);
        res.status(500).json({ success: false, message: 'Gagal mengajukan izin: ' + error.message });
    }
};

// 2. Guru Melihat Riwayat Izinnya Sendiri
export const getMyTeacherLeaves = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const leaves = await (prisma as any).teacherLeaveRequest.findMany({
            where: { teacherId: user.id },
            orderBy: { createdAt: 'desc' }
        });
        res.json({ success: true, leaves });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat riwayat izin: ' + error.message });
    }
};

// 3. Operator Melihat Seluruh Permohonan Izin Guru
export const getAllTeacherLeaves = async (req: Request, res: Response) => {
    try {
        const { status } = req.query;
        const whereClause: any = {};
        if (status && status !== 'ALL') {
            whereClause.status = String(status).toUpperCase();
        }

        const leaves = await (prisma as any).teacherLeaveRequest.findMany({
            where: whereClause,
            orderBy: { createdAt: 'desc' }
        });

        const pendingCount = await (prisma as any).teacherLeaveRequest.count({
            where: { status: 'PENDING' }
        });

        res.json({ success: true, pendingCount, leaves });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat izin guru: ' + error.message });
    }
};

// 4. Operator Menyetujui Izin Guru & Otomatis Disposisi ke Guru Piket
export const approveTeacherLeave = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const { approvalNotes, piketNotes } = req.body;
        const user = (req as any).user;

        const leave = await (prisma as any).teacherLeaveRequest.findUnique({ where: { id } });
        if (!leave) {
            return res.status(404).json({ success: false, message: 'Permohonan izin tidak ditemukan.' });
        }

        const updated = await (prisma as any).teacherLeaveRequest.update({
            where: { id },
            data: {
                status: 'APPROVED',
                approvedBy: user.name || 'Operator Sekolah',
                approvalNotes: approvalNotes || 'Disetujui dan diteruskan ke Guru Piket',
                approvedAt: new Date(),
                dispositionToPiket: true,
                piketNotes: piketNotes || `Tugas Mandiri: ${leave.assignmentDetails || 'Siswa belajar mandiri'}`
            }
        });

        // Parse jadwal kelas yang ditinggalkan dan buat entri otomatis di EmptyClassReport
        if (leave.affectedSchedules) {
            try {
                let schedules = [];
                try {
                    schedules = JSON.parse(leave.affectedSchedules);
                } catch {
                    schedules = [{ className: leave.affectedSchedules, periodLesson: 'Semua Jam' }];
                }

                if (Array.isArray(schedules)) {
                    for (const s of schedules) {
                        await (prisma as any).emptyClassReport.create({
                            data: {
                                className: s.className || 'Kelas',
                                periodLesson: s.periodLesson || s.timeRange || 'Jam Mengajar',
                                subjectName: s.subjectName || 'Mata Pelajaran',
                                scheduledTeacher: leave.teacherName,
                                teacherStatus: leave.category,
                                hasAssignment: Boolean(leave.assignmentDetails),
                                assignmentDetails: leave.assignmentDetails || 'Belajar Mandiri',
                                status: 'PENDING',
                                reporterName: `Disposisi Operator: ${user.name || 'Operator'}`
                            }
                        });
                    }
                }
            } catch (parseErr) {
                console.warn('Warning create emptyClassReports from leave:', parseErr);
            }
        }

        // Kirim notifikasi konfirmasi ke Guru yang bersangkutan
        try {
            await (prisma as any).notificationMessage.create({
                data: {
                    recipientId: leave.teacherId,
                    recipientRole: 'TEACHER',
                    title: `✅ Izin Disetujui: ${leave.category}`,
                    message: `Permohonan izin Anda telah disetujui oleh Operator (${user.name}). Tugas telah diteruskan ke Posko Guru Piket.`,
                    category: 'TEACHER_LEAVE'
                }
            });
            // Notifikasi ke Posko Piket
            await (prisma as any).notificationMessage.create({
                data: {
                    recipientRole: 'TEACHER',
                    title: `📋 Disposisi Piket: Bpk/Ibu ${leave.teacherName} Izin`,
                    message: `Bpk/Ibu ${leave.teacherName} izin (${leave.category}). Tugas mandiri: "${leave.assignmentDetails || '-'}" siap dibagikan ke kelas.`,
                    category: 'PIKET_ALERT'
                }
            });
        } catch (nErr) {}

        res.json({
            success: true,
            message: 'Izin berhasil disetujui dan telah didisposisikan ke Posko Guru Piket hari ini.',
            leave: updated
        });
    } catch (error: any) {
        console.error('Error approveTeacherLeave:', error);
        res.status(500).json({ success: false, message: 'Gagal menyetujui izin: ' + error.message });
    }
};

// 5. Operator Menolak Izin Guru
export const rejectTeacherLeave = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const { rejectionReason } = req.body;
        const user = (req as any).user;

        const leave = await (prisma as any).teacherLeaveRequest.findUnique({ where: { id } });
        if (!leave) {
            return res.status(404).json({ success: false, message: 'Permohonan izin tidak ditemukan.' });
        }

        const updated = await (prisma as any).teacherLeaveRequest.update({
            where: { id },
            data: {
                status: 'REJECTED',
                approvedBy: user.name || 'Operator Sekolah',
                approvalNotes: rejectionReason || 'Permohonan izin ditolak. Harap koordinasi langsung dengan pimpinan/operator.',
                approvedAt: new Date()
            }
        });

        // Notifikasi ke Guru
        try {
            await (prisma as any).notificationMessage.create({
                data: {
                    recipientId: leave.teacherId,
                    recipientRole: 'TEACHER',
                    title: `❌ Izin Ditolak: ${leave.category}`,
                    message: `Permohonan izin Anda tidak disetujui. Catatan: ${rejectionReason || 'Harap konfirmasi ke operator.'}`,
                    category: 'TEACHER_LEAVE'
                }
            });
        } catch (nErr) {}

        res.json({
            success: true,
            message: 'Permohonan izin telah ditolak.',
            leave: updated
        });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal menolak izin: ' + error.message });
    }
};

// 6. Feed Posko Guru Piket Hari Ini (Daftar Guru Izin & Disposisi Tugas Mandiri)
export const getPiketTodayFeed = async (req: Request, res: Response) => {
    try {
        const todayStart = new Date();
        todayStart.setHours(0, 0, 0, 0);
        const todayEnd = new Date();
        todayEnd.setHours(23, 59, 59, 999);

        // Ambil izin guru yang disetujui untuk hari ini
        const approvedLeaves = await (prisma as any).teacherLeaveRequest.findMany({
            where: {
                status: 'APPROVED',
                startDate: { lte: todayEnd },
                endDate: { gte: todayStart }
            },
            orderBy: { createdAt: 'desc' }
        });

        // Ambil laporan kelas kosong aktif
        const emptyClasses = await (prisma as any).emptyClassReport.findMany({
            where: {
                createdAt: { gte: todayStart },
                status: 'PENDING'
            },
            orderBy: { createdAt: 'desc' }
        });

        // Ambil guru piket yang bertugas hari ini
        const dayOfWeek = new Date().getDay();
        const piketTeachers = await prisma.piketSchedule.findMany({
            where: { dayOfWeek, isActive: true },
            include: { teacher: { select: { id: true, name: true, username: true } } }
        });

        res.json({
            success: true,
            todayDate: todayStart.toISOString().substring(0, 10),
            piketTeachers: piketTeachers.map(p => ({
                id: p.id,
                name: p.teacher.name,
                startTime: p.startTime,
                endTime: p.endTime,
                notes: p.notes
            })),
            leavesCount: approvedLeaves.length,
            leaves: approvedLeaves,
            emptyClassesCount: emptyClasses.length,
            emptyClasses
        });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat feed posko piket: ' + error.message });
    }
};
