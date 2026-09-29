import { Request, Response } from 'express';
import prisma from '../utils/db';

// 1. Presensi Manual Siswa Tanpa HP oleh Guru BK
export const submitBkManualAttendance = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { studentId, type, status, reasonNoPhone, notes, lateMinutes } = req.body;

        if (!studentId) {
            return res.status(400).json({ success: false, message: 'Siswa wajib dipilih.' });
        }

        const student = await prisma.user.findUnique({ where: { id: studentId } });
        if (!student) {
            return res.status(404).json({ success: false, message: 'Siswa tidak ditemukan.' });
        }

        const attType = (type || 'GATE_IN').toUpperCase();
        const attStatus = (status || 'PRESENT').toUpperCase();
        const noteDetail = `[Manual BK: ${user.name}] Alasan Tanpa HP: ${reasonNoPhone || 'HP Tertinggal di Rumah'}. ${notes || ''}`.trim();

        const today = new Date();
        const attendance = await prisma.attendance.create({
            data: {
                userId: student.id,
                type: attType,
                method: 'MANUAL_BK',
                status: attStatus,
                scanTime: today,
                note: noteDetail
            }
        });

        // Kirim notifikasi realtime ke akun Orang Tua
        try {
            await (prisma as any).notificationMessage.create({
                data: {
                    recipientRole: 'PARENT',
                    studentId: student.id,
                    studentName: student.name,
                    className: student.className,
                    title: `📱 Presensi Khusus (Tanpa HP): ${student.name}`,
                    message: `Ananda ${student.name} telah dicatat kehadirannya secara resmi oleh Guru BK (${user.name}) dengan keterangan: ${reasonNoPhone || 'Tidak membawa HP'}. Status: ${attStatus === 'PRESENT' ? 'Hadir' : attStatus}.`,
                    category: 'ATTENDANCE'
                }
            });
        } catch (nErr) {}

        res.json({
            success: true,
            message: `Presensi manual ananda ${student.name} berhasil dicatat oleh Guru BK.`,
            attendance
        });
    } catch (error: any) {
        console.error('Error submitBkManualAttendance:', error);
        res.status(500).json({ success: false, message: 'Gagal mencatat presensi manual: ' + error.message });
    }
};

// 2. Riwayat Presensi Manual Guru BK Hari Ini
export const getBkManualTodayLog = async (req: Request, res: Response) => {
    try {
        const todayStart = new Date();
        todayStart.setHours(0, 0, 0, 0);

        const logs = await prisma.attendance.findMany({
            where: {
                method: 'MANUAL_BK',
                scanTime: { gte: todayStart }
            },
            include: {
                user: {
                    select: {
                        id: true,
                        name: true,
                        nisn: true,
                        className: true,
                        profilePicUrl: true
                    }
                }
            },
            orderBy: { scanTime: 'desc' }
        });

        res.json({ success: true, count: logs.length, logs });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat log presensi BK: ' + error.message });
    }
};

// 3. Daftar Permohonan Izin Siswa (dari Orang Tua) yang Menunggu Verifikasi BK
export const getPendingStudentLeavesForBk = async (req: Request, res: Response) => {
    try {
        const { className, status } = req.query;
        const whereClause: any = {};

        if (status && status !== 'ALL') {
            whereClause.status = String(status).toUpperCase();
        } else if (!status) {
            whereClause.status = 'PENDING';
        }

        if (className && className !== 'ALL') {
            whereClause.className = String(className).trim();
        }

        const leaves = await (prisma as any).studentLeaveRequest.findMany({
            where: whereClause,
            orderBy: { createdAt: 'desc' }
        });

        const pendingCount = await (prisma as any).studentLeaveRequest.count({
            where: { status: 'PENDING' }
        });

        res.json({ success: true, pendingCount, leaves });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat izin siswa: ' + error.message });
    }
};

// 4. Rekap Presensi 360 Derajat untuk Halaman BK & Portal (Gerbang, Mapel, & Sholat)
export const getBkUnifiedAttendanceRecap = async (req: Request, res: Response) => {
    try {
        const { className, date } = req.query;
        const targetDateStr = String(date || new Date().toISOString().substring(0, 10));

        const startOfDay = new Date(`${targetDateStr}T00:00:00.000Z`);
        const endOfDay = new Date(`${targetDateStr}T23:59:59.999Z`);

        let studentFilter: any = { role: 'STUDENT' };
        if (className && className !== 'ALL') {
            studentFilter.className = String(className).trim();
        }

        const students = await prisma.user.findMany({
            where: studentFilter,
            select: {
                id: true,
                name: true,
                nisn: true,
                gender: true,
                className: true,
                profilePicUrl: true
            },
            orderBy: [{ className: 'asc' }, { name: 'asc' }]
        });

        const studentIds = students.map(s => s.id);

        // 1. Data Presensi Gerbang (Gate In / Gate Out)
        const gateAttendances = await prisma.attendance.findMany({
            where: {
                userId: { in: studentIds },
                scanTime: { gte: startOfDay, lte: endOfDay }
            },
            orderBy: { scanTime: 'asc' }
        });

        // 2. Data Presensi Jam Pelajaran (Mapel)
        const mapelAttendances = await (prisma as any).classPeriodAttendance.findMany({
            where: {
                studentId: { in: studentIds },
                createdAt: { gte: startOfDay, lte: endOfDay }
            },
            include: {
                session: {
                    select: { subjectName: true, periodIndex: true, timeRange: true }
                }
            }
        });

        // 3. Data Presensi Sholat Berjamaah & Keputrian
        const prayerAttendances = await (prisma as any).studentPrayerAttendance.findMany({
            where: {
                studentId: { in: studentIds },
                date: targetDateStr
            }
        });

        const gateMap = new Map<string, any[]>();
        gateAttendances.forEach(g => {
            const list = gateMap.get(g.userId) || [];
            list.push(g);
            gateMap.set(g.userId, list);
        });

        const mapelMap = new Map<string, any[]>();
        mapelAttendances.forEach((m: any) => {
            const list = mapelMap.get(m.studentId) || [];
            list.push(m);
            mapelMap.set(m.studentId, list);
        });

        const prayerMap = new Map<string, any>();
        prayerAttendances.forEach((p: any) => prayerMap.set(p.studentId, p));

        let totalHadirGerbang = 0;
        let totalTerlambatGerbang = 0;
        let totalSakitIzin = 0;
        let totalAlpa = 0;

        let totalSholat = 0;
        let totalHaid = 0;
        let totalBelumSholat = 0;

        const unifiedList = students.map(s => {
            const gates = gateMap.get(s.id) || [];
            const gateIn = gates.find(g => g.type === 'GATE_IN');
            const gateOut = gates.find(g => g.type === 'GATE_OUT');

            let gateStatus = 'BELUM_MASUK';
            if (gateIn) {
                gateStatus = gateIn.status;
                if (gateIn.status === 'PRESENT') totalHadirGerbang++;
                else if (gateIn.status === 'LATE') totalTerlambatGerbang++;
                else if (gateIn.status === 'SICK' || gateIn.status === 'PERMISSION') totalSakitIzin++;
            } else {
                totalAlpa++;
            }

            const mapels = mapelMap.get(s.id) || [];
            const prayer = prayerMap.get(s.id);
            const prayerStatus = prayer ? prayer.status : 'BELUM_PRESENSI';

            if (prayerStatus === 'SHOLAT_BERJAMAAH') totalSholat++;
            else if (prayerStatus === 'BERHALANGAN_HAID') totalHaid++;
            else totalBelumSholat++;

            return {
                id: s.id,
                name: s.name,
                nisn: s.nisn,
                gender: (s.gender || 'L').toUpperCase(),
                className: s.className,
                profilePicUrl: s.profilePicUrl,
                // Pilar 1: Gerbang
                gateStatus,
                gateInTime: gateIn?.scanTime || null,
                gateOutTime: gateOut?.scanTime || null,
                gateMethod: gateIn?.method || null,
                // Pilar 2: Mapel
                mapelCount: mapels.length,
                mapels: mapels.map((m: any) => ({
                    subject: m.session?.subjectName || 'Mapel',
                    period: m.session?.periodIndex || 1,
                    status: m.status
                })),
                // Pilar 3: Sholat
                prayerType: prayer?.prayerType || (new Date().getDay() === 5 ? 'JUMAT' : 'DHUHUR'),
                prayerStatus,
                prayerRecordedAt: prayer?.createdAt || null,
                prayerMethod: prayer?.method || null
            };
        });

        res.json({
            success: true,
            date: targetDateStr,
            className: className || 'ALL',
            summary: {
                totalStudents: students.length,
                gate: {
                    hadir: totalHadirGerbang,
                    terlambat: totalTerlambatGerbang,
                    sakitIzin: totalSakitIzin,
                    belumMasuk: totalAlpa
                },
                sholat: {
                    sholatBerjamaah: totalSholat,
                    berhalanganHaid: totalHaid,
                    belumSholat: totalBelumSholat
                }
            },
            students: unifiedList
        });
    } catch (error: any) {
        console.error('Error getBkUnifiedAttendanceRecap:', error);
        res.status(500).json({ success: false, message: 'Gagal memuat rekap presensi BK: ' + error.message });
    }
};
