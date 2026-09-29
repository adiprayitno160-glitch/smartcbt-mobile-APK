import { Request, Response } from 'express';
import prisma from '../utils/db';
import { logAudit } from '../services/auditLogger';

// 1. Siswa / Ketua Kelas Melaporkan Jam Kosong via APK (Maksimal 1 Laporan per Kelas per JP)
export const reportEmptyClass = async (req: Request, res: Response) => {
    try {
        const { className, periodLesson, subjectName, scheduledTeacher, teacherStatus, hasAssignment, assignmentDetails, reportedBy, reporterName } = req.body;
        const user = (req as any).user;
        const reporterDisplay = reporterName || reportedBy || (user ? `Ketua Kelas a.n. ${user.name} (${user.className || className})` : `Pengurus Kelas ${className}`);

        if (!className || !periodLesson || !subjectName || !scheduledTeacher) {
            return res.status(400).json({ message: 'Data laporan tidak lengkap' });
        }

        // Batasi: Maksimal 1 Laporan per Kelas per Jam Pelajaran (Hari Ini)
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        const existingReport = await prisma.emptyClassReport.findFirst({
            where: {
                className,
                periodLesson,
                createdAt: { gte: today },
                status: { in: ['PENDING', 'GURU_INVAL_ASSIGNED', 'TUGAS_MANDIRI'] }
            }
        });

        if (existingReport) {
            return res.status(400).json({
                success: false,
                alreadyReported: true,
                message: `⚠️ Laporan Dibatasi: Kelas ${className} pada jam ini (${periodLesson}) sudah dilaporkan oleh siswa lain dan saat ini sedang dalam penanganan Guru Piket.`
            });
        }

        const report = await (prisma as any).emptyClassReport.create({
            data: {
                className,
                periodLesson,
                subjectName,
                scheduledTeacher,
                teacherStatus: (teacherStatus as any) || 'BELUM_HADIR',
                hasAssignment: hasAssignment || false,
                assignmentDetails: (assignmentDetails as any) || null,
                reporterName: reporterDisplay,
                status: 'PENDING'
            }
        });

        (report as any).reporterName = reporterDisplay;

        await logAudit(req, 'REPORT_EMPTY_CLASS', `Class: ${className}, Subject: ${subjectName}`, {
            periodLesson, scheduledTeacher, teacherStatus, hasAssignment
        });

        // Broadcast to TV Display via SSE
        global.emitEmptyClassUpdate && global.emitEmptyClassUpdate({
            type: 'EMPTY_CLASS_REPORTED',
            report
        });

        // Cari guru piket yang bertugas hari ini untuk dikirimkan notifikasi ke APK mereka
        try {
            const now = new Date();
            const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
            const wibDate = new Date(utc + (3600000 * 7));
            const currentDay = wibDate.getDay();

            const onDutyTeachers = await prisma.piketSchedule.findMany({
                where: {
                    dayOfWeek: currentDay,
                    isActive: true
                },
                include: { teacher: true }
            });

            for (const duty of onDutyTeachers) {
                await prisma.notificationMessage.create({
                    data: {
                        recipientId: duty.teacherId,
                        recipientRole: 'TEACHER',
                        title: `⚠️ KELAS KOSONG: ${className}`,
                        message: `Jam ${periodLesson} - Mapel ${subjectName}. Guru terjadwal: ${scheduledTeacher} (${teacherStatus}). Segera buka APK untuk tindakan Inval / Tugas.`,
                        category: 'EMPTY_CLASS'
                    }
                });
            }
        } catch (notifErr) {
            console.error('Error notifying piket teachers:', notifErr);
        }

        res.json({
            success: true,
            message: 'Laporan kelas kosong berhasil dikirim ke Guru Piket dan terpampang di Monitor Ruang Guru.',
            report
        });
    } catch (error) {
        console.error('Error reporting empty class:', error);
        res.status(500).json({ message: 'Gagal mengirim laporan kelas kosong' });
    }
};

// 1.B Cek Status Laporan Jam Kosong Kelas (Untuk Disable Tombol Lapor di Siswa)
export const getEmptyClassStatus = async (req: Request, res: Response) => {
    try {
        const className = (req.query.className as string) || (req as any).user?.className;
        const periodLesson = req.query.periodLesson as string;

        if (!className) {
            return res.status(400).json({ message: 'className wajib disediakan' });
        }

        const today = new Date();
        today.setHours(0, 0, 0, 0);

        const whereCondition: any = {
            className,
            createdAt: { gte: today },
            status: { in: ['PENDING', 'GURU_INVAL_ASSIGNED', 'TUGAS_MANDIRI'] }
        };

        if (periodLesson) {
            whereCondition.periodLesson = periodLesson;
        }

        const report = await prisma.emptyClassReport.findFirst({
            where: whereCondition,
            orderBy: { createdAt: 'desc' }
        });

        res.json({
            success: true,
            className,
            isReported: !!report,
            report: report || null,
            message: report 
                ? `Kelas ${className} pada jam ini sudah dilaporkan (${report.periodLesson} - ${report.subjectName}).`
                : `Belum ada laporan jam kosong untuk kelas ${className}.`
        });
    } catch (error) {
        res.status(500).json({ message: 'Gagal memeriksa status laporan kelas' });
    }
};

// 1.C Monitoring Real-Time Status Seluruh Kelas (HIJAU jika Guru Hadir, MERAH jika Laporan Siswa / > 10 Menit Kosong)
export const getClassMonitoringStatus = async (req: Request, res: Response) => {
    try {
        const now = new Date();
        const days = ['Minggu', 'Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat', 'Sabtu'];
        const currentDay = days[now.getDay()];
        const todayStart = new Date(now);
        todayStart.setHours(0, 0, 0, 0);

        // Ambil semua daftar kelas aktif
        const dbClasses = await prisma.class.findMany({
            orderBy: { name: 'asc' }
        });
        const defaultClassNames = ['VII-A', 'VII-B', 'VII-C', 'VII-D', 'VII-E', 'VII-F', 'VII-G', 'VII-H', 'VII-I', 'VII-J', 'VII-K'];
        const classNames = dbClasses.length > 0 ? dbClasses.map(c => c.name) : defaultClassNames;

        // Ambil sesi KBM aktif hari ini
        const activeSessions = await (prisma as any).classPeriodSession.findMany({
            where: {
                status: 'ACTIVE',
                inTime: { gte: todayStart }
            }
        });

        // Ambil laporan jam kosong hari ini
        const emptyReports = await prisma.emptyClassReport.findMany({
            where: {
                createdAt: { gte: todayStart }
            },
            orderBy: { createdAt: 'desc' }
        });

        // Ambil jadwal pelajaran hari ini
        const schedulesToday = await (prisma as any).classPeriodSchedule.findMany({
            where: {
                day: currentDay,
                isBreak: false
            }
        });

        const currentHour = now.getHours();
        const currentMinute = now.getMinutes();
        const currentTotalMinutes = currentHour * 60 + currentMinute;

        const results = classNames.map((clsName, idx) => {
            const camNumber = String(idx + 1).padStart(2, '0');

            // 1. Cek Sesi KBM Aktif (Guru Hadir di Kelas)
            const session = activeSessions.find((s: any) => s.className && s.className.toUpperCase() === clsName.toUpperCase());
            if (session) {
                return {
                    className: clsName,
                    cam: `CAM ${camNumber}`,
                    status: 'GREEN',
                    badge: 'NORMAL (TERISI & GURU HADIR)',
                    isAlarm: false,
                    teacherName: session.teacherName || 'Guru Pengampu',
                    subjectName: session.subjectName,
                    periodIndex: session.periodIndex,
                    timeRange: session.timeRange || `${session.startTimeStr || ''} - ${session.endTimeStr || ''}`,
                    inTime: session.inTime,
                    details: `KBM aktif dimulai pukul ${new Date(session.inTime).toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' })} WIB`
                };
            }

            // 2. Cek Laporan Siswa Jam Kosong yang PENDING
            const pendingReport = emptyReports.find(r => r.className && r.className.toUpperCase() === clsName.toUpperCase() && r.status === 'PENDING');
            if (pendingReport) {
                const repBy = (pendingReport as any).reporterName || `Ketua Kelas ${clsName}`;
                return {
                    className: clsName,
                    cam: `CAM ${camNumber}`,
                    status: 'RED',
                    badge: 'KELAS KOSONG! (LAPORAN SISWA)',
                    isAlarm: true,
                    alarmReason: 'STUDENT_REPORTED',
                    teacherName: pendingReport.scheduledTeacher,
                    subjectName: pendingReport.subjectName,
                    periodIndex: pendingReport.periodLesson,
                    reportId: pendingReport.id,
                    reportedBy: repBy,
                    hasAssignment: pendingReport.hasAssignment,
                    assignmentDetails: pendingReport.assignmentDetails,
                    details: `Laporan oleh ${repBy}: Guru ${pendingReport.scheduledTeacher} (${pendingReport.teacherStatus})`
                };
            }

            // 3. Cek Laporan Siswa yang Sedang Ditangani (INVAL)
            const handledReport = emptyReports.find(r => r.className && r.className.toUpperCase() === clsName.toUpperCase() && r.status !== 'PENDING' && r.status !== 'RESOLVED');
            if (handledReport) {
                return {
                    className: clsName,
                    cam: `CAM ${camNumber}`,
                    status: 'BLUE',
                    badge: 'DITANGANI GURU INVAL',
                    isAlarm: false,
                    teacherName: handledReport.substituteTeacher || 'Guru Piket',
                    subjectName: handledReport.subjectName,
                    periodIndex: handledReport.periodLesson,
                    details: `Guru Inval ${handledReport.substituteTeacher} menggantikan ${handledReport.scheduledTeacher}`
                };
            }

            // 4. Cek Jadwal Hari Ini: Jika Jadwal Sedang Berjalan dan Lewat 10 Menit Tanpa Guru -> Otomatis MERAH!
            const classSchedules = schedulesToday.filter((s: any) => s.className && s.className.toUpperCase() === clsName.toUpperCase());
            for (const sch of classSchedules) {
                if (sch.startTime && sch.endTime && sch.startTime.includes(':') && sch.endTime.includes(':')) {
                    const [sH, sM] = sch.startTime.split(':').map(Number);
                    const [eH, eM] = sch.endTime.split(':').map(Number);
                    const startTotal = sH * 60 + sM;
                    const endTotal = eH * 60 + eM;

                    if (currentTotalMinutes >= startTotal && currentTotalMinutes <= endTotal) {
                        const elapsedMinutes = currentTotalMinutes - startTotal;
                        if (elapsedMinutes >= 10) {
                            // LEBIH DARI 10 MENIT GURU BELUM HADIR -> OTOMATIS MERAH!
                            return {
                                className: clsName,
                                cam: `CAM ${camNumber}`,
                                status: 'RED',
                                badge: 'KELAS KOSONG! (GURU BELUM HADIR > 10 MENIT)',
                                isAlarm: true,
                                alarmReason: 'TIMEOUT_10_MINUTES',
                                elapsedMinutes,
                                teacherName: sch.teacherName || 'Belum Terisi',
                                subjectName: sch.subjectName || 'Mata Pelajaran',
                                periodIndex: `JP ${sch.periodIndex || 1}`,
                                timeRange: `${sch.startTime} - ${sch.endTime}`,
                                details: `Jadwal dimulai pukul ${sch.startTime} (${elapsedMinutes} menit lalu). Belum ada konfirmasi kehadiran guru di ruang kelas.`
                            };
                        } else {
                            // Menunggu Guru (Baru Dimulai < 10 Menit) -> KUNING / AMBER
                            return {
                                className: clsName,
                                cam: `CAM ${camNumber}`,
                                status: 'AMBER',
                                badge: 'MENUNGGU GURU MASUK KELAS',
                                isAlarm: false,
                                elapsedMinutes,
                                teacherName: sch.teacherName || 'Guru Pengampu',
                                subjectName: sch.subjectName || 'Mata Pelajaran',
                                periodIndex: `JP ${sch.periodIndex || 1}`,
                                timeRange: `${sch.startTime} - ${sch.endTime}`,
                                details: `Jam pelajaran baru dimulai ${elapsedMinutes} menit lalu. Menunggu guru klik IN Kelas.`
                            };
                        }
                    }
                }
            }

            // 5. Default: Ruang Kelas Normal / Belum Ada Sesi
            return {
                className: clsName,
                cam: `CAM ${camNumber}`,
                status: 'IDLE',
                badge: 'TIDAK ADA JADWAL KBM',
                isAlarm: false,
                teacherName: '-',
                subjectName: 'Istirahat / Di Luar Jam KBM',
                details: 'Ruang kelas tidak memiliki jam pelajaran aktif saat ini.'
            };
        });

        res.json({
            success: true,
            currentTime: now.toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' }),
            currentDay,
            totalClasses: results.length,
            classes: results
        });
    } catch (error) {
        console.error('Error fetching class monitoring status:', error);
        res.status(500).json({ message: 'Gagal memuat status monitoring kelas' });
    }
};

// 2. Mengambil Laporan Hari Ini (Untuk TV Display / Piket Dashboard)
export const getEmptyClassReports = async (req: Request, res: Response) => {
    try {
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        const reports = await prisma.emptyClassReport.findMany({
            where: {
                createdAt: {
                    gte: today
                }
            },
            orderBy: { createdAt: 'desc' }
        });

        res.json(reports);
    } catch (error) {
        console.error('Error fetching empty class reports:', error);
        res.status(500).json({ message: 'Gagal memuat laporan' });
    }
};

// 3. Guru Piket / Kurikulum Mengambil Tindakan (Klaim Inval)
export const handleEmptyClass = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const { substituteTeacher, actionType, substituteNotes } = req.body; // actionType = 'GURU_INVAL_ASSIGNED' or 'TUGAS_MANDIRI'
        const user = (req as any).user;

        const report = await prisma.emptyClassReport.findUnique({ where: { id: id as string } });
        if (!report) return res.status(404).json({ message: 'Laporan tidak ditemukan' });

        const updated = await prisma.emptyClassReport.update({
            where: { id: id as string },
            data: {
                status: (actionType as any) || 'GURU_INVAL_ASSIGNED',
                substituteTeacher: (substituteTeacher as any) || (user ? user.name : null),
                substituteNotes: (substituteNotes as any) || null,
                handledBy: user ? user.name : 'Tim Kurikulum',
                handledAt: new Date()
            }
        });

        // Catat ke Jurnal Guru Terjadwal secara otomatis (Opsional)
        // Disini kita bisa buat record di TeacherJournal

        await logAudit(req, 'HANDLE_EMPTY_CLASS', `Class: ${report.className}, Inval: ${updated.substituteTeacher}`, {
            actionType, substituteTeacher
        });

        // Broadcast to TV Display via SSE
        global.emitEmptyClassUpdate && global.emitEmptyClassUpdate(updated);

        res.json({
            success: true,
            message: `Kelas berhasil ditangani. Guru Inval: ${updated.substituteTeacher}`,
            report: updated
        });
    } catch (error) {
        console.error('Error handling empty class:', error);
        res.status(500).json({ message: 'Gagal menindaklanjuti laporan' });
    }
};

// 4. SSE Stream untuk Display Monitor Ruang Guru
export const streamEmptyClassMonitor = (req: Request, res: Response) => {
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();

    const initialData = JSON.stringify({ type: 'CONNECTED' });
    res.write(`data: ${initialData}\n\n`);

    const listener = (data: any) => {
        res.write(`data: ${JSON.stringify(data)}\n\n`);
    };

    if (!global.emptyClassClients) global.emptyClassClients = [];
    global.emptyClassClients.push(listener);

    req.on('close', () => {
        global.emptyClassClients = global.emptyClassClients.filter(l => l !== listener);
    });
};

// Global emitter function
declare global {
    var emptyClassClients: ((data: any) => void)[];
    var emitEmptyClassUpdate: (report: any) => void;
}
if (!global.emitEmptyClassUpdate) {
    global.emitEmptyClassUpdate = (report: any) => {
        if (global.emptyClassClients) {
            global.emptyClassClients.forEach(client => client({ type: 'UPDATE', report }));
        }
    };
}