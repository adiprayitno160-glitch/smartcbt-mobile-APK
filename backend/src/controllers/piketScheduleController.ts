import { Request, Response } from 'express';
import prisma from '../utils/db';
import { logAudit } from '../services/auditLogger';

const DAY_NAMES = ['MINGGU', 'SENIN', 'SELASA', 'RABU', 'KAMIS', 'JUMAT', 'SABTU'];

// Helper mendapatkan nomor hari saat ini (1 = Senin ... 6 = Sabtu, 0 = Minggu)
export const getCurrentDayOfWeek = (): number => {
    // Waktu Indonesia Barat (WIB = UTC+7)
    const now = new Date();
    const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
    const wibDate = new Date(utc + (3600000 * 7));
    return wibDate.getDay();
};

// 1. Ambil Seluruh Jadwal Piket Mingguan (Senin - Sabtu)
export const getWeeklyPiketSchedules = async (req: Request, res: Response) => {
    try {
        const schedules = await prisma.piketSchedule.findMany({
            include: {
                teacher: {
                    select: {
                        id: true,
                        name: true,
                        username: true,
                        teachingSubject: true,
                        teachingClasses: true,
                        role: true
                    }
                }
            },
            orderBy: [
                { dayOfWeek: 'asc' },
                { startTime: 'asc' }
            ]
        });

        // Hitung statistik per hari
        const dayCounts: Record<number, number> = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0, 6: 0 };
        schedules.forEach(s => {
            if (dayCounts[s.dayOfWeek] !== undefined) {
                dayCounts[s.dayOfWeek]++;
            }
        });

        res.json({
            success: true,
            total: schedules.length,
            dayCounts,
            schedules
        });
    } catch (error) {
        console.error('Error fetching piket schedules:', error);
        res.status(500).json({ message: 'Gagal memuat jadwal piket' });
    }
};

// 2. Operator Menambahkan Jadwal Guru Piket
export const createPiketSchedule = async (req: Request, res: Response) => {
    try {
        const { teacherId, dayOfWeek, startTime, endTime, notes } = req.body;
        const operatorUser = (req as any).user;

        if (!teacherId || dayOfWeek === undefined) {
            return res.status(400).json({ message: 'Guru dan Hari Piket wajib diisi' });
        }

        const parsedDay = parseInt(dayOfWeek);
        const resolvedDayName = DAY_NAMES[parsedDay] || 'SENIN';

        // Validasi guru ada
        const teacher = await prisma.user.findUnique({ where: { id: teacherId } });
        if (!teacher) {
            return res.status(404).json({ message: 'Data guru tidak ditemukan' });
        }

        const newSchedule = await prisma.piketSchedule.create({
            data: {
                teacherId,
                dayOfWeek: parsedDay,
                dayName: resolvedDayName,
                startTime: startTime || '06:30',
                endTime: endTime || '15:00',
                notes: notes || null,
                isActive: true,
                createdBy: operatorUser ? operatorUser.name : 'Operator'
            },
            include: {
                teacher: {
                    select: { id: true, name: true, username: true }
                }
            }
        });

        await logAudit(req, 'CREATE_PIKET_SCHEDULE', `Teacher: ${teacher.name}, Day: ${resolvedDayName}`, {
            dayOfWeek: parsedDay,
            notes
        });

        res.json({
            success: true,
            message: `Jadwal piket hari ${resolvedDayName} berhasil ditambahkan untuk ${teacher.name}`,
            schedule: newSchedule
        });
    } catch (error: any) {
        console.error('Error creating piket schedule:', error);
        res.status(500).json({ message: 'Gagal menambahkan jadwal piket: ' + error.message });
    }
};

// 3. Operator Mengubah Jadwal Piket
export const updatePiketSchedule = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const { teacherId, dayOfWeek, startTime, endTime, notes, isActive } = req.body;

        const existing = await prisma.piketSchedule.findUnique({ where: { id: id as string } });
        if (!existing) {
            return res.status(404).json({ message: 'Jadwal piket tidak ditemukan' });
        }

        const parsedDay = dayOfWeek !== undefined ? parseInt(dayOfWeek) : existing.dayOfWeek;
        const resolvedDayName = DAY_NAMES[parsedDay] || existing.dayName;

        const updated = await prisma.piketSchedule.update({
            where: { id: id as string },
            data: {
                teacherId: teacherId || existing.teacherId,
                dayOfWeek: parsedDay,
                dayName: resolvedDayName,
                startTime: startTime !== undefined ? startTime : existing.startTime,
                endTime: endTime !== undefined ? endTime : existing.endTime,
                notes: notes !== undefined ? notes : existing.notes,
                isActive: isActive !== undefined ? isActive : existing.isActive
            },
            include: {
                teacher: {
                    select: { id: true, name: true, username: true }
                }
            }
        });

        res.json({
            success: true,
            message: 'Jadwal piket berhasil diperbarui',
            schedule: updated
        });
    } catch (error) {
        console.error('Error updating piket schedule:', error);
        res.status(500).json({ message: 'Gagal memperbarui jadwal piket' });
    }
};

// 4. Operator Menghapus Jadwal Piket
export const deletePiketSchedule = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        await prisma.piketSchedule.delete({ where: { id: id as string } });
        res.json({ success: true, message: 'Jadwal piket berhasil dihapus' });
    } catch (error) {
        console.error('Error deleting piket schedule:', error);
        res.status(500).json({ message: 'Gagal menghapus jadwal piket' });
    }
};

// 5. Mengambil Guru Piket Aktif Hari Ini (Bisa diakses Guru & Operator)
export const getTodayOnDutyTeachers = async (req: Request, res: Response) => {
    try {
        const currentDay = getCurrentDayOfWeek();
        const dayName = DAY_NAMES[currentDay] || 'SENIN';

        const onDuty = await prisma.piketSchedule.findMany({
            where: {
                dayOfWeek: currentDay,
                isActive: true
            },
            include: {
                teacher: {
                    select: {
                        id: true,
                        name: true,
                        username: true,
                        teachingSubject: true,
                        teachingClasses: true
                    }
                }
            }
        });

        res.json({
            success: true,
            currentDay,
            dayName,
            totalOnDuty: onDuty.length,
            teachers: onDuty.map(s => ({
                scheduleId: s.id,
                teacherId: s.teacher.id,
                name: s.teacher.name,
                nip: s.teacher.username,
                subject: s.teacher.teachingSubject || '-',
                classes: s.teacher.teachingClasses || '-',
                startTime: s.startTime,
                endTime: s.endTime,
                notes: s.notes
            }))
        });
    } catch (error) {
        console.error('Error fetching today piket teachers:', error);
        res.status(500).json({ message: 'Gagal memuat guru piket hari ini' });
    }
};

// 6. Cek Status Piket Guru yang Sedang Login (Dipanggil oleh APK Guru)
export const checkTeacherPiketStatus = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        if (!user) return res.status(401).json({ message: 'Unauthorized' });

        const currentDay = getCurrentDayOfWeek();
        const dayName = DAY_NAMES[currentDay] || 'SENIN';

        // Cek apakah user ada di jadwal piket hari ini
        const schedule = await prisma.piketSchedule.findFirst({
            where: {
                teacherId: user.id,
                dayOfWeek: currentDay,
                isActive: true
            }
        });

        // Hitung laporan kelas kosong hari ini yang PENDING
        const todayStart = new Date();
        todayStart.setHours(0, 0, 0, 0);

        const pendingCount = await prisma.emptyClassReport.count({
            where: {
                createdAt: { gte: todayStart },
                status: 'PENDING'
            }
        });

        // Ambil juga rekan guru piket hari ini
        const onDuty = await prisma.piketSchedule.findMany({
            where: { dayOfWeek: currentDay, isActive: true },
            include: { teacher: { select: { id: true, name: true, username: true } } }
        });

        res.json({
            success: true,
            isPiketToday: !!schedule,
            isOnDutyToday: !!schedule,
            dayOfWeek: dayName,
            dayName,
            notes: schedule?.notes || null,
            scheduleDetails: schedule ? {
                startTime: schedule.startTime,
                endTime: schedule.endTime,
                notes: schedule.notes
            } : null,
            pendingEmptyClassesCount: pendingCount,
            pendingReportsCount: pendingCount,
            onDutyTeachers: onDuty.map(d => ({
                id: d.teacher.id,
                name: d.teacher.name,
                username: d.teacher.username,
                notes: d.notes
            }))
        });
    } catch (error) {
        console.error('Error checking teacher piket status:', error);
        res.status(500).json({ message: 'Gagal memeriksa status piket' });
    }
};

// 7. Mengambil Laporan Kelas Kosong Khusus untuk Guru Piket di APK
export const getTeacherPiketReports = async (req: Request, res: Response) => {
    try {
        const todayStart = new Date();
        todayStart.setHours(0, 0, 0, 0);

        const reports = await prisma.emptyClassReport.findMany({
            where: {
                createdAt: { gte: todayStart }
            },
            orderBy: { createdAt: 'desc' }
        });

        res.json({
            success: true,
            count: reports.length,
            reports
        });
    } catch (error) {
        console.error('Error fetching piket reports for teacher:', error);
        res.status(500).json({ message: 'Gagal memuat laporan piket' });
    }
};
