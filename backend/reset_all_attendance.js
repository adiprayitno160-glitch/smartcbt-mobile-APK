const { PrismaClient } = require('@prisma/client');
const prisma = new PrismaClient();

async function resetAllAttendance() {
    console.log('=== MEMULAI RESET TOTAL SELURUH DATA PRESENSI SEKOLAH ===\n');

    const results = {};

    // 1. Reset Presensi Harian Utama (Gate-In, Gate-Out, GPS, Barcode, RFID)
    try {
        const deletedAttendance = await prisma.attendance.deleteMany({});
        results['Attendance (Gate-In/Out & Harian)'] = deletedAttendance.count;
    } catch (e) {
        results['Attendance'] = `Error: ${e.message}`;
    }

    // 2. Reset Presensi Sholat Mushola / Masjid Sekolah
    try {
        if (prisma.studentPrayerAttendance) {
            const deletedPrayer = await prisma.studentPrayerAttendance.deleteMany({});
            results['StudentPrayerAttendance (Sholat Mushola)'] = deletedPrayer.count;
        }
    } catch (e) {
        results['StudentPrayerAttendance'] = `Error: ${e.message}`;
    }

    // 3. Reset Presensi Mata Pelajaran / KBM Kelas (Room Handover)
    try {
        if (prisma.studentSubjectAttendance) {
            const deletedSubjectAtt = await prisma.studentSubjectAttendance.deleteMany({});
            results['StudentSubjectAttendance (Mapel KBM)'] = deletedSubjectAtt.count;
        }
    } catch (e) {
        results['StudentSubjectAttendance'] = `Error: ${e.message}`;
    }

    // 4. Reset Sesi Mengajar Guru (TeachingSession)
    try {
        if (prisma.teachingSession) {
            const deletedSessions = await prisma.teachingSession.deleteMany({});
            results['TeachingSession (Sesi Mengajar)'] = deletedSessions.count;
        }
    } catch (e) {
        results['TeachingSession'] = `Error: ${e.message}`;
    }

    // 5. Reset ClassPeriodAttendance
    try {
        if (prisma.classPeriodAttendance) {
            const deletedPeriod = await prisma.classPeriodAttendance.deleteMany({});
            results['ClassPeriodAttendance'] = deletedPeriod.count;
        }
    } catch (e) {
        results['ClassPeriodAttendance'] = `Error: ${e.message}`;
    }

    // 6. Reset Geofence Attendance (Guru & Siswa)
    try {
        if (prisma.teacherGeofenceAttendance) {
            const deletedTGeofence = await prisma.teacherGeofenceAttendance.deleteMany({});
            results['TeacherGeofenceAttendance'] = deletedTGeofence.count;
        }
        if (prisma.studentGeofenceAttendance) {
            const deletedSGeofence = await prisma.studentGeofenceAttendance.deleteMany({});
            results['StudentGeofenceAttendance'] = deletedSGeofence.count;
        }
    } catch (e) {
        results['GeofenceAttendance'] = `Error: ${e.message}`;
    }

    // 7. Reset Attendance Anomaly Log
    try {
        if (prisma.attendanceAnomalyLog) {
            const deletedAnomaly = await prisma.attendanceAnomalyLog.deleteMany({});
            results['AttendanceAnomalyLog'] = deletedAnomaly.count;
        }
    } catch (e) {
        results['AttendanceAnomalyLog'] = `Error: ${e.message}`;
    }

    // 8. Reset Notifikasi Presensi (Parent & Counselor)
    try {
        const deletedNotifs = await prisma.notificationMessage.deleteMany({
            where: {
                OR: [
                    { category: 'ATTENDANCE' },
                    { category: 'BK_LATE' },
                    { category: 'BK_EARLY_LEAVE' },
                    { title: { contains: 'Presensi' } },
                    { title: { contains: 'Sholat' } },
                    { title: { contains: 'Terlambat' } },
                    { title: { contains: 'Gate' } }
                ]
            }
        });
        results['NotificationMessage (Notif Presensi & Sholat)'] = deletedNotifs.count;
    } catch (e) {
        results['NotificationMessage'] = `Error: ${e.message}`;
    }

    // 9. Reset Catatan Pelanggaran Keterlambatan BK & Poin Siswa
    try {
        const deletedDiscipline = await prisma.disciplineRecord.deleteMany({
            where: {
                OR: [
                    { description: { contains: 'presensi' } },
                    { description: { contains: 'terlambat' } },
                    { description: { contains: 'Keterlambatan' } },
                    { description: { contains: 'Ruang BK' } }
                ]
            }
        });
        results['DisciplineRecord (Pelanggaran Terlambat)'] = deletedDiscipline.count;

        // Reset poin siswa kembali ke 0 untuk memastikan bersih
        const resetPoints = await prisma.user.updateMany({
            where: { role: 'STUDENT', points: { gt: 0 } },
            data: { points: 0 }
        });
        results['User (Reset Poin Pelanggaran Siswa)'] = resetPoints.count;
    } catch (e) {
        results['DisciplineRecord / Points'] = `Error: ${e.message}`;
    }

    // 10. Reset Izin Keluar BK / StudentLeave jika ada
    try {
        if (prisma.studentLeave) {
            const deletedLeaves = await prisma.studentLeave.deleteMany({});
            results['StudentLeave (Exit Pass BK)'] = deletedLeaves.count;
        }
        if (prisma.studentLeaveRequest) {
            const deletedReqs = await prisma.studentLeaveRequest.deleteMany({});
            results['StudentLeaveRequest'] = deletedReqs.count;
        }
    } catch (e) {
        results['StudentLeave'] = `Error: ${e.message}`;
    }

    console.log('=== HASIL PEMBERSIHAN DATA PRESENSI ===');
    console.table(results);
    console.log('\n✅ Seluruh tabel dan rekaman presensi telah BERSIH TOTAL!');
}

resetAllAttendance()
    .catch(console.error)
    .finally(async () => {
        await prisma.$disconnect();
    });
