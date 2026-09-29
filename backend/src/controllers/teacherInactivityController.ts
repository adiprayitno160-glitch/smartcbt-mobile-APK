import { Request, Response } from 'express';
import prisma from '../utils/db';

export const getTeacherInactivityReport = async (req: Request, res: Response) => {
    try {
        const queryMonth = req.query.month ? parseInt(req.query.month as string) : new Date().getMonth() + 1; // 1-12
        const queryYear = req.query.year ? parseInt(req.query.year as string) : new Date().getFullYear();
        const search = (req.query.search as string || '').toLowerCase().trim();

        // 1. Tentukan rentang tanggal awal dan akhir bulan
        const startDate = new Date(queryYear, queryMonth - 1, 1, 0, 0, 0, 0);
        const endDate = new Date(queryYear, queryMonth, 0, 23, 59, 59, 999);

        // Jika bulan yang dipilih adalah bulan berjalan, hitung hari efektif sampai hari ini
        const now = new Date();
        const isCurrentMonth = now.getFullYear() === queryYear && (now.getMonth() + 1) === queryMonth;
        const cutoffDate = isCurrentMonth ? now : endDate;

        // 2. Hitung jumlah hari kerja efektif (Senin - Sabtu, abaikan Minggu)
        let effectiveWorkingDays = 0;
        const curIter = new Date(startDate);
        while (curIter <= cutoffDate) {
            const day = curIter.getDay();
            if (day !== 0) { // Bukan hari Minggu
                effectiveWorkingDays++;
            }
            curIter.setDate(curIter.getDate() + 1);
        }
        if (effectiveWorkingDays === 0) effectiveWorkingDays = 1;

        // 3. Ambil seluruh Guru yang aktif
        const teachers = await prisma.user.findMany({
            where: {
                role: { in: ['TEACHER', 'COUNSELOR'] },
                isActive: true
            },
            select: {
                id: true,
                name: true,
                username: true,
                role: true,
                teachingSubject: true,
                teachingClasses: true
            },
            orderBy: { name: 'asc' }
        });

        // 4. Ambil seluruh presensi Guru di bulan tersebut
        const attendances = await prisma.attendance.findMany({
            where: {
                scanTime: { gte: startDate, lte: endDate },
                user: { role: { in: ['TEACHER', 'COUNSELOR'] } }
            },
            select: {
                id: true,
                userId: true,
                type: true,
                status: true,
                scanTime: true,
                note: true
            }
        });

        // 5. Ambil seluruh laporan kelas kosong di bulan tersebut
        const emptyClassReports = await prisma.emptyClassReport.findMany({
            where: {
                createdAt: { gte: startDate, lte: endDate }
            },
            select: {
                id: true,
                className: true,
                periodLesson: true,
                subjectName: true,
                scheduledTeacher: true,
                teacherStatus: true,
                hasAssignment: true,
                assignmentDetails: true,
                status: true,
                substituteTeacher: true,
                reporterName: true,
                createdAt: true
            }
        });

        // 6. Agregasi data per guru
        const results = teachers.map(teacher => {
            const teacherAtts = attendances.filter(a => a.userId === teacher.id);
            const gateIns = teacherAtts.filter(a => a.type === 'GATE_IN');

            const presentCount = gateIns.filter(a => a.status === 'PRESENT').length;
            const lateCount = gateIns.filter(a => a.status === 'LATE').length;
            const sickCount = teacherAtts.filter(a => a.status === 'SICK').length;
            const permitCount = teacherAtts.filter(a => a.status === 'PERMISSION' || a.status === 'PERMIT').length;
            const recordedAbsentCount = teacherAtts.filter(a => a.status === 'ABSENT').length;

            // Total hari hadir (tepat waktu + terlambat)
            const totalRecordedPresent = presentCount + lateCount;

            // Hari tidak hadir = hari efektif dikurangi hari hadir
            const unrecordedAbsence = Math.max(0, effectiveWorkingDays - (totalRecordedPresent + sickCount + permitCount));
            const totalAbsentDays = sickCount + permitCount + recordedAbsentCount + unrecordedAbsence;

            // Persentase ketidakhadiran & keterlambatan
            const absencePercentage = Math.min(100, Math.round((totalAbsentDays / effectiveWorkingDays) * 100));
            const latePercentage = Math.min(100, Math.round((lateCount / effectiveWorkingDays) * 100));

            // Cocokkan laporan kelas kosong berdasarkan nama guru atau NIP
            const teacherEmptyReports = emptyClassReports.filter(r => {
                const sched = (r.scheduledTeacher || '').toLowerCase();
                const tName = teacher.name.toLowerCase();
                const tUser = teacher.username.toLowerCase();
                return sched.includes(tName) || tName.includes(sched) || (tUser && sched.includes(tUser));
            });

            // Kelas apa saja yang ditinggalkan / kosong
            const missedClassesSet = new Set<string>();
            teacherEmptyReports.forEach(r => {
                missedClassesSet.add(r.className);
            });
            const missedClasses = Array.from(missedClassesSet);

            // Status evaluasi ketertiban
            let evaluationStatus = 'SANGAT_DISIPLIN';
            if (absencePercentage >= 20 || teacherEmptyReports.length >= 5) {
                evaluationStatus = 'PERLU_PEMBINAAN_KHUSUS';
            } else if (absencePercentage >= 10 || latePercentage >= 15 || teacherEmptyReports.length >= 3) {
                evaluationStatus = 'PERHATIAN_KURIKULUM';
            } else if (absencePercentage > 0 || lateCount > 0) {
                evaluationStatus = 'CUKUP_DISIPLIN';
            }

            return {
                teacherId: teacher.id,
                name: teacher.name,
                nip: teacher.username,
                role: teacher.role,
                subject: teacher.teachingSubject || '-',
                teachingClasses: teacher.teachingClasses || '-',
                effectiveWorkingDays,
                presentCount,
                lateCount,
                latePercentage,
                sickCount,
                permitCount,
                totalAbsentDays,
                absencePercentage,
                missedClassesCount: teacherEmptyReports.length,
                missedClasses,
                evaluationStatus,
                emptyReportsDetails: teacherEmptyReports.map(r => ({
                    id: r.id,
                    className: r.className,
                    periodLesson: r.periodLesson,
                    subjectName: r.subjectName,
                    teacherStatus: r.teacherStatus,
                    hasAssignment: r.hasAssignment,
                    substituteTeacher: r.substituteTeacher,
                    date: r.createdAt
                }))
            };
        });

        // Filter search jika ada
        const filteredResults = search 
            ? results.filter(r => r.name.toLowerCase().includes(search) || r.nip.toLowerCase().includes(search) || r.subject.toLowerCase().includes(search))
            : results;

        // KPI Summary untuk seluruh guru
        const totalTeachers = results.length;
        const avgAbsencePercentage = totalTeachers > 0 ? Math.round(results.reduce((acc, r) => acc + r.absencePercentage, 0) / totalTeachers) : 0;
        const avgLatePercentage = totalTeachers > 0 ? Math.round(results.reduce((acc, r) => acc + r.latePercentage, 0) / totalTeachers) : 0;
        const totalSchoolEmptyClasses = emptyClassReports.length;

        res.json({
            success: true,
            month: queryMonth,
            year: queryYear,
            effectiveWorkingDays,
            summary: {
                totalTeachers,
                avgAbsencePercentage,
                avgLatePercentage,
                totalSchoolEmptyClasses
            },
            teachers: filteredResults
        });
    } catch (error: any) {
        console.error('Error fetching teacher inactivity report:', error);
        res.status(500).json({ success: false, message: 'Gagal memuat rekap ketidakaktifan guru: ' + error.message });
    }
};
