import { Request, Response } from 'express';
import prisma from '../utils/db';
import { getSchoolSignatures } from '../utils/schoolSignatures';

// 1. Ambil Jadwal Rombel Sholat Hari Ini
export const getTodayPrayerSchedule = async (req: Request, res: Response) => {
    try {
        const todayDay = new Date().getDay(); // 0 = Minggu, 1 = Senin, dst.
        const dayNames = ['MINGGU', 'SENIN', 'SELASA', 'RABU', 'KAMIS', 'JUMAT', 'SABTU'];
        const currentDayName = dayNames[todayDay];

        let schedule = await (prisma as any).prayerClassSchedule.findFirst({
            where: {
                dayOfWeek: todayDay,
                isActive: true
            }
        });

        // Default schedules jika belum diatur di database
        if (!schedule) {
            let defaultClasses = "VII-A,VII-B,VII-C,VII-D,VII-E";
            if (todayDay === 2) defaultClasses = "VIII-A,VIII-B,VIII-C,VIII-D,VIII-E";
            else if (todayDay === 3) defaultClasses = "IX-A,IX-B,IX-C,IX-D,IX-E";
            else if (todayDay === 5) defaultClasses = "ALL_MALE"; // Sholat Jumat
            
            schedule = {
                id: `default-${todayDay}`,
                dayOfWeek: todayDay,
                dayName: currentDayName,
                prayerType: todayDay === 5 ? 'JUMAT' : 'DHUHUR',
                classNames: defaultClasses,
                barcodeKey: todayDay === 5 ? 'BARCODE_SHOLAT_JUMAT_MASJID' : 'BARCODE_SHOLAT_DHUHUR_MASJID',
                wudhuTime: '11:45',
                scanStartTime: '12:00',
                scanEndTime: '12:30',
                notes: `Jadwal Sholat ${todayDay === 5 ? 'Jumat' : 'Dhuhur'} Berjamaah di Masjid Sekolah`
            };
        } else {
            schedule = {
                ...schedule,
                wudhuTime: schedule.wudhuTime || '11:45',
                scanStartTime: schedule.scanStartTime || '12:00',
                scanEndTime: schedule.scanEndTime || '12:30'
            };
        }

        res.json({
            success: true,
            dayName: currentDayName,
            schedule
        });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat jadwal sholat: ' + error.message });
    }
};

// 1b. Ambil Seluruh Jadwal Sholat Sepanjang Pekan (Senin - Jumat)
export const getAllPrayerSchedules = async (req: Request, res: Response) => {
    try {
        const schedules = await (prisma as any).prayerClassSchedule.findMany({
            where: { isActive: true },
            orderBy: { dayOfWeek: 'asc' }
        });

        const dayNames = ['MINGGU', 'SENIN', 'SELASA', 'RABU', 'KAMIS', 'JUMAT', 'SABTU'];
        const fullWeekSchedules = [];

        for (let d = 1; d <= 5; d++) {
            const found = schedules.find((s: any) => s.dayOfWeek === d);
            if (found) {
                fullWeekSchedules.push({
                    ...found,
                    wudhuTime: found.wudhuTime || '11:45',
                    scanStartTime: found.scanStartTime || '12:00',
                    scanEndTime: found.scanEndTime || '12:30'
                });
            } else {
                let defaultClasses = "VII-A,VII-B,VII-C,VII-D,VII-E";
                if (d === 2) defaultClasses = "VIII-A,VIII-B,VIII-C,VIII-D,VIII-E";
                else if (d === 3) defaultClasses = "IX-A,IX-B,IX-C,IX-D,IX-E";
                else if (d === 4) defaultClasses = "VII-F,VII-G,VIII-F,VIII-G";
                else if (d === 5) defaultClasses = "ALL_MALE";

                fullWeekSchedules.push({
                    id: `default-${d}`,
                    dayOfWeek: d,
                    dayName: dayNames[d],
                    prayerType: d === 5 ? 'JUMAT' : 'DHUHUR',
                    classNames: defaultClasses,
                    barcodeKey: d === 5 ? 'BARCODE_SHOLAT_JUMAT_MASJID' : 'BARCODE_SHOLAT_DHUHUR_MASJID',
                    wudhuTime: '11:45',
                    scanStartTime: '12:00',
                    scanEndTime: '12:30',
                    notes: `Jadwal Sholat ${d === 5 ? 'Jumat' : 'Dhuhur'} Berjamaah di Masjid Sekolah`,
                    isActive: true
                });
            }
        }

        res.json({
            success: true,
            schedules: fullWeekSchedules
        });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat daftar jadwal sholat: ' + error.message });
    }
};

// 2. Simpan / Perbarui Jadwal Rombel Sholat (Operator & Guru PAI)
export const setPrayerClassSchedule = async (req: Request, res: Response) => {
    try {
        const { dayOfWeek, dayName, prayerType, classNames, barcodeKey, notes, wudhuTime, scanStartTime, scanEndTime } = req.body;
        if (dayOfWeek === undefined || !classNames) {
            return res.status(400).json({ success: false, message: 'Hari dan daftar kelas wajib diisi.' });
        }

        const cleanDay = Number(dayOfWeek);
        const dayNames = ['MINGGU', 'SENIN', 'SELASA', 'RABU', 'KAMIS', 'JUMAT', 'SABTU'];
        const resolvedDayName = dayName || dayNames[cleanDay] || 'HARI';
        const pType = prayerType || (cleanDay === 5 ? 'JUMAT' : 'DHUHUR');

        const existing = await (prisma as any).prayerClassSchedule.findFirst({
            where: { dayOfWeek: cleanDay, prayerType: pType }
        });

        const dataPayload = {
            dayName: resolvedDayName,
            prayerType: pType,
            classNames: typeof classNames === 'object' ? classNames.join(',') : String(classNames).trim(),
            barcodeKey: barcodeKey || (pType === 'JUMAT' ? 'BARCODE_SHOLAT_JUMAT_MASJID' : 'BARCODE_SHOLAT_DHUHUR_MASJID'),
            wudhuTime: wudhuTime || '11:45',
            scanStartTime: scanStartTime || '12:00',
            scanEndTime: scanEndTime || '12:30',
            notes: notes ? String(notes).trim() : null,
            isActive: true
        };

        let saved;
        if (existing) {
            saved = await (prisma as any).prayerClassSchedule.update({
                where: { id: existing.id },
                data: dataPayload
            });
        } else {
            saved = await (prisma as any).prayerClassSchedule.create({
                data: {
                    dayOfWeek: cleanDay,
                    ...dataPayload
                }
            });
        }

        res.json({ success: true, message: 'Jadwal sholat berhasil disimpan dan disinkronkan ke seluruh sistem', schedule: saved });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal menyimpan jadwal sholat: ' + error.message });
    }
};

// 3. Siswa Memindai Barcode Statik Masjid Sekolah (Self-Scan Barcode Statik)
export const studentScanStaticBarcode = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const rawBar = req.body.barcodeValue || req.body.barcodeData || req.body.barcodeKey || req.body.barcode;
        const { prayerType, lat, lng, isFakeGps } = req.body;
        const cleanBar = String(rawBar || '').trim().toUpperCase();

        // Validasi Barcode Statik Masjid
        const validStaticBarcodes = [
            'BARCODE_SHOLAT_DHUHUR_MASJID',
            'BARCODE_SHOLAT_JUMAT_MASJID',
            'MASJID_DHUHUR_S1BOY',
            'MASJID_JUMAT_S1BOY',
            'SHOLAT_MASJID_SMPN1',
            'SHOLAT_DHUHUR_MASJID'
        ];

        const isMatch = validStaticBarcodes.some(b => cleanBar.includes(b)) || cleanBar.includes('MASJID') || cleanBar.includes('SHOLAT');
        if (!isMatch) {
            return res.status(400).json({
                success: false,
                message: '❌ Barcode tidak valid! Pastikan Anda memindai Barcode Statik resmi yang tertempel di dinding Masjid SMPN 1 Boyolangu.'
            });
        }

        const todayDay = new Date().getDay();
        const pType = prayerType || (todayDay === 5 ? 'JUMAT' : 'DHUHUR');

        // Cek Jadwal & Status Keaktifan Barcode Mushola Hari Ini (Otomatis Non-aktif saat Scan Selesai)
        const sched = await (prisma as any).prayerClassSchedule.findFirst({
            where: { dayOfWeek: todayDay, prayerType: pType, isActive: true }
        });

        const now = new Date();
        const currentHourMinute = now.toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit', hour12: false }).replace('.', ':');

        // Pengaturan jam scan sholat resmi: ditentukan oleh Admin atau Guru PAI (Bukan 24 jam)
        const startTime = sched?.scanStartTime || '12:00';
        const endTime = sched?.scanEndTime || '12:30';
        const wudhuTime = sched?.wudhuTime || '11:45';

        if (sched && sched.isScanActive === false) {
            return res.status(400).json({
                success: false,
                message: '❌ Barcode mushola non-aktif! Sesi scan sholat telah dinonaktifkan oleh Guru PAI / Pengurus Mushola.'
            });
        }

        if (currentHourMinute < startTime) {
            return res.status(400).json({
                success: false,
                message: `❌ Presensi sholat belum dibuka! Sesi scan QR mushola dibuka pukul ${startTime} s/d ${endTime} WIB (Wudhu: ${wudhuTime} WIB) sesuai jadwal resmi Admin / Guru PAI. Presensi sholat tidak berlaku 24 jam.`
            });
        }
        if (currentHourMinute > endTime) {
            return res.status(400).json({
                success: false,
                message: `❌ Waktu scan sholat telah ditutup (batas pukul ${endTime} WIB). Pengaturan jam presensi ditentukan oleh Admin / Guru PAI demi ketertiban ibadah.`
            });
        }

        const todayStr = new Date().toISOString().substring(0, 10);

        const student = await prisma.user.findUnique({ where: { id: user.id } });
        if (!student) {
            return res.status(404).json({ success: false, message: 'Data siswa tidak ditemukan.' });
        }

        // Cek apakah sudah pernah presensi sholat hari ini
        const existing = await (prisma as any).studentPrayerAttendance.findFirst({
            where: {
                studentId: student.id,
                prayerType: pType,
                date: todayStr
            }
        });

        if (existing) {
            return res.json({
                success: true,
                alreadyRecorded: true,
                message: `✅ Anda sudah tercatat melaksanakan Sholat ${pType} Berjamaah hari ini pukul ${new Date(existing.createdAt).toTimeString().substring(0, 5)} WIB.`,
                record: existing
            });
        }

        const coordText = (lat && lng) ? ` • GPS: ${lat}, ${lng}` : '';
        const newRecord = await (prisma as any).studentPrayerAttendance.create({
            data: {
                studentId: student.id,
                studentName: student.name,
                className: student.className || '-',
                gender: (student.gender || 'L').toUpperCase(),
                prayerType: pType,
                date: todayStr,
                status: 'SHOLAT_BERJAMAAH',
                method: 'STATIC_BARCODE',
                recordedById: student.id,
                recordedByName: student.name,
                notes: `Presensi mandiri scan barcode statik mushola${coordText}`
            }
        });

        // Simpan koordinat GPS ke rekaman Attendance utama jika tersedia
        if (lat && lng && Number(lat) !== 0 && Number(lng) !== 0) {
            try {
                const startOfDay = new Date();
                startOfDay.setHours(0, 0, 0, 0);
                const endOfDay = new Date();
                endOfDay.setHours(23, 59, 59, 999);

                const existingAtt = await prisma.attendance.findFirst({
                    where: {
                        userId: student.id,
                        scanTime: { gte: startOfDay, lte: endOfDay }
                    },
                    orderBy: { scanTime: 'desc' }
                });

                if (existingAtt) {
                    await prisma.attendance.update({
                        where: { id: existingAtt.id },
                        data: {
                            lat: Number(lat),
                            lng: Number(lng),
                            isFakeGps: Boolean(isFakeGps),
                            note: (existingAtt.note || '') + ` • Mushola GPS: ${lat}, ${lng}`
                        }
                    });
                } else {
                    await prisma.attendance.create({
                        data: {
                            userId: student.id,
                            type: 'PRAYER',
                            method: 'BARCODE',
                            status: 'PRESENT',
                            lat: Number(lat),
                            lng: Number(lng),
                            isFakeGps: Boolean(isFakeGps),
                            note: `Presensi Sholat Mushola • GPS: ${lat}, ${lng}`
                        }
                    });
                }
            } catch (attErr) {}
        }

        // Kirim notifikasi ke Orang Tua
        try {
            await (prisma as any).notificationMessage.create({
                data: {
                    recipientRole: 'PARENT',
                    studentId: student.id,
                    studentName: student.name,
                    className: student.className,
                    title: `🕌 Laporan Ibadah: Sholat ${pType} Berjamaah`,
                    message: `Alhamdulillah, ananda ${student.name} telah melaksanakan Sholat ${pType} Berjamaah di Masjid SMPN 1 Boyolangu hari ini.`,
                    category: 'ATTENDANCE'
                }
            });
        } catch (nErr) {}

        res.json({
            success: true,
            message: `Alhamdulillah! Presensi Sholat ${pType} Berjamaah berhasil dicatat.`,
            record: newRecord
        });
    } catch (error: any) {
        console.error('Error studentScanStaticBarcode:', error);
        res.status(500).json({ success: false, message: 'Gagal mencatat presensi sholat: ' + error.message });
    }
};

// 4. Guru PAI Menandai Presensi Sholat Siswa (Termasuk Siswi Berhalangan Haid & Siswa Tanpa HP)
export const paiMarkStudentPrayer = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { studentId, prayerType, status, notes } = req.body;
        // status: 'SHOLAT_BERJAMAAH', 'BERHALANGAN_HAID', 'UZUR_SAKIT', 'NON_MUSLIM', 'ALPHA'

        if (!studentId || !status) {
            return res.status(400).json({ success: false, message: 'Siswa dan status sholat wajib ditentukan.' });
        }

        const student = await prisma.user.findUnique({ where: { id: studentId } });
        if (!student) {
            return res.status(404).json({ success: false, message: 'Siswa tidak ditemukan.' });
        }

        const gender = (student.gender || 'L').toUpperCase();

        // ATURAN KRUSIAL: Status 'BERHALANGAN_HAID' HANYA BOLEH UNTUK SISWI PEREMPUAN
        if (status.toUpperCase() === 'BERHALANGAN_HAID' && gender !== 'P' && gender !== 'PEREMPUAN') {
            return res.status(400).json({
                success: false,
                message: '⛔ Opsi Berhalangan (Haid / Keputrian) HANYA berlaku untuk siswi perempuan.'
            });
        }

        const todayStr = new Date().toISOString().substring(0, 10);
        const pType = prayerType || (new Date().getDay() === 5 ? 'JUMAT' : 'DHUHUR');

        const existing = await (prisma as any).studentPrayerAttendance.findFirst({
            where: {
                studentId: student.id,
                prayerType: pType,
                date: todayStr
            }
        });

        let resultRecord;
        if (existing) {
            resultRecord = await (prisma as any).studentPrayerAttendance.update({
                where: { id: existing.id },
                data: {
                    status: status.toUpperCase(),
                    method: 'MANUAL_PAI',
                    recordedById: user.id,
                    recordedByName: user.name || 'Guru PAI',
                    notes: notes || (status === 'BERHALANGAN_HAID' ? 'Izin keputrian / berhalangan syar’i' : 'Diverifikasi Guru PAI')
                }
            });
        } else {
            resultRecord = await (prisma as any).studentPrayerAttendance.create({
                data: {
                    studentId: student.id,
                    studentName: student.name,
                    className: student.className || '-',
                    gender,
                    prayerType: pType,
                    date: todayStr,
                    status: status.toUpperCase(),
                    method: 'MANUAL_PAI',
                    recordedById: user.id,
                    recordedByName: user.name || 'Guru PAI',
                    notes: notes || (status === 'BERHALANGAN_HAID' ? 'Izin keputrian / berhalangan syar’i' : 'Diverifikasi Guru PAI')
                }
            });
        }

        // Notifikasi ke Orang Tua
        try {
            const isHaid = status.toUpperCase() === 'BERHALANGAN_HAID';
            await (prisma as any).notificationMessage.create({
                data: {
                    recipientRole: 'PARENT',
                    studentId: student.id,
                    studentName: student.name,
                    className: student.className,
                    title: isHaid ? `🌸 Informasi Keputrian: ${student.name}` : `🕌 Laporan Ibadah Sholat`,
                    message: isHaid 
                        ? `Ananda ${student.name} hari ini tercatat izin berhalangan syar'i (haid/keputrian) dan mengikuti pembinaan di ruang keputrian.`
                        : `Ananda ${student.name} tercatat melaksanakan Sholat ${pType} Berjamaah (Diverifikasi oleh Guru PAI Bpk/Ibu ${user.name}).`,
                    category: 'ATTENDANCE'
                }
            });
        } catch (nErr) {}

        res.json({
            success: true,
            message: `Status presensi sholat siswa berhasil diperbarui: ${status}`,
            record: resultRecord
        });
    } catch (error: any) {
        console.error('Error paiMarkStudentPrayer:', error);
        res.status(500).json({ success: false, message: 'Gagal mencatat status sholat: ' + error.message });
    }
};

// 4b. Guru PAI Menandai Presensi Sholat Secara Massal (Satu Kelas Sekaligus)
export const batchPaiMarkStudentPrayer = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { className, prayerType, status, studentIds, notes } = req.body;
        const targetStatus = (status || 'SHOLAT_BERJAMAAH').toUpperCase();

        if (!className) {
            return res.status(400).json({ success: false, message: 'Kelas wajib ditentukan.' });
        }

        const todayStr = new Date().toISOString().substring(0, 10);
        const pType = prayerType || (new Date().getDay() === 5 ? 'JUMAT' : 'DHUHUR');

        let students: any[] = [];
        if (Array.isArray(studentIds) && studentIds.length > 0) {
            students = await prisma.user.findMany({
                where: { id: { in: studentIds }, role: 'STUDENT' }
            });
        } else {
            students = await prisma.user.findMany({
                where: { className: String(className).trim(), role: 'STUDENT' }
            });
        }

        if (students.length === 0) {
            return res.status(404).json({ success: false, message: 'Tidak ada siswa pada kelas ini.' });
        }

        let updatedCount = 0;
        for (const student of students) {
            const gender = (student.gender || 'L').toUpperCase();
            const isFemale = gender === 'P' || gender === 'PEREMPUAN';

            const existing = await (prisma as any).studentPrayerAttendance.findFirst({
                where: {
                    studentId: student.id,
                    prayerType: pType,
                    date: todayStr
                }
            });

            // Jangan timpa jika siswi sudah berhalangan haid saat aksi 'Tandai Semua Hadir'
            if (existing && existing.status === 'BERHALANGAN_HAID' && targetStatus === 'SHOLAT_BERJAMAAH') {
                continue;
            }

            if (targetStatus === 'BERHALANGAN_HAID' && !isFemale) {
                continue;
            }

            if (existing) {
                await (prisma as any).studentPrayerAttendance.update({
                    where: { id: existing.id },
                    data: {
                        status: targetStatus,
                        method: 'MANUAL_PAI',
                        recordedById: user.id,
                        recordedByName: user.name || 'Guru PAI',
                        notes: notes || 'Presensi massal diverifikasi Guru PAI'
                    }
                });
            } else {
                await (prisma as any).studentPrayerAttendance.create({
                    data: {
                        studentId: student.id,
                        studentName: student.name,
                        className: student.className || className,
                        gender,
                        prayerType: pType,
                        date: todayStr,
                        status: targetStatus,
                        method: 'MANUAL_PAI',
                        recordedById: user.id,
                        recordedByName: user.name || 'Guru PAI',
                        notes: notes || 'Presensi massal diverifikasi Guru PAI'
                    }
                });
            }
            updatedCount++;
        }

        res.json({
            success: true,
            updatedCount,
            message: `Berhasil mencatat ${updatedCount} siswa sebagai ${targetStatus}.`
        });
    } catch (error: any) {
        console.error('Error batchPaiMarkStudentPrayer:', error);
        res.status(500).json({ success: false, message: 'Gagal melakukan presensi massal: ' + error.message });
    }
};

// 5. Guru PAI Memantau Daftar Siswa Rombel yang Terjadwal Hari Ini (Live Monitor)
export const getPaiMonitoringByClass = async (req: Request, res: Response) => {
    try {
        const { className, date, prayerType } = req.query;
        if (!className) {
            return res.status(400).json({ success: false, message: 'Kelas wajib dipilih.' });
        }

        const targetDate = String(date || new Date().toISOString().substring(0, 10));
        const targetDateObj = new Date(targetDate + 'T12:00:00+07:00');
        const dayOfWeek = targetDateObj.getDay();
        const pType = String(prayerType || (dayOfWeek === 5 ? 'JUMAT' : 'DHUHUR')).toUpperCase();

        // Ambil info jadwal kelas hari ini jika ada
        const schedule = await (prisma as any).prayerClassSchedule.findFirst({
            where: { dayOfWeek, isActive: true }
        });

        // Ambil seluruh siswa di kelas tersebut
        const students = await prisma.user.findMany({
            where: { role: 'STUDENT', className: String(className).trim() },
            select: {
                id: true,
                name: true,
                nisn: true,
                gender: true,
                className: true,
                religion: true,
                profilePicUrl: true
            },
            orderBy: { name: 'asc' }
        });

        // Ambil catatan presensi sholat hari tersebut
        const attendances = await (prisma as any).studentPrayerAttendance.findMany({
            where: {
                className: String(className).trim(),
                date: targetDate,
                prayerType: pType
            }
        });

        const attMap = new Map();
        attendances.forEach((a: any) => attMap.set(a.studentId, a));

        const enriched = students.map(s => {
            const att = attMap.get(s.id);
            const isFemale = (s.gender || '').toUpperCase().startsWith('P');
            let gpsStr: string | null = null;
            let latVal: number | null = null;
            let lngVal: number | null = null;
            if (att?.notes && att.notes.includes('GPS:')) {
                const match = att.notes.match(/GPS:\s*([-0-9.]+),\s*([-0-9.]+)/);
                if (match) {
                    gpsStr = `${match[1]}, ${match[2]}`;
                    latVal = parseFloat(match[1]);
                    lngVal = parseFloat(match[2]);
                }
            }
            return {
                ...s,
                isFemale,
                prayerStatus: att ? att.status : 'BELUM_PRESENSI',
                method: att?.method || null,
                recordedAt: att?.createdAt || null,
                notes: att?.notes || null,
                gps: gpsStr,
                lat: latVal,
                lng: lngVal
            };
        });

        const totalStudents = enriched.length;
        const sholatCount = enriched.filter(e => e.prayerStatus === 'SHOLAT_BERJAMAAH').length;
        const haidCount = enriched.filter(e => e.prayerStatus === 'BERHALANGAN_HAID').length;
        const belumCount = enriched.filter(e => e.prayerStatus === 'BELUM_PRESENSI' || e.prayerStatus === 'ALPHA').length;

        res.json({
            success: true,
            className,
            date: targetDate,
            prayerType: pType,
            schedule: schedule ? {
                ...schedule,
                wudhuTime: schedule.wudhuTime || '11:45',
                scanStartTime: schedule.scanStartTime || '12:00',
                scanEndTime: schedule.scanEndTime || '12:30'
            } : null,
            summary: {
                totalStudents,
                sholatCount,
                haidCount,
                belumCount
            },
            students: enriched
        });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat monitoring sholat: ' + error.message });
    }
};

// 6. Riwayat Ibadah untuk Siswa & Orang Tua
export const getStudentPrayerHistory = async (req: Request, res: Response) => {
    try {
        const { studentId } = req.params;
        const history = await (prisma as any).studentPrayerAttendance.findMany({
            where: { studentId },
            orderBy: { createdAt: 'desc' },
            take: 30
        });

        res.json({ success: true, history });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat riwayat ibadah: ' + error.message });
    }
};

// 7. Cetak / Unduh Rekap Presensi Sholat Format PDF Resmi A4
export const exportPrayerAttendancePdf = async (req: Request, res: Response) => {
    try {
        const { className, date, prayerType, autoprint, download, period, range } = req.query;
        const cleanClass = className ? String(className).trim() : 'ALL';
        const isAllClasses = !className || cleanClass.toUpperCase() === 'ALL' || cleanClass.toUpperCase() === 'SEMUA' || cleanClass.toUpperCase() === 'SEMUA KELAS';
        const isWeekly = String(period || range || '').toUpperCase() === 'WEEKLY' || String(period || range || '').toUpperCase() === 'MINGGUAN' || String(period || range || '').toUpperCase() === '1_MINGGU';

        const targetDate = String(date || new Date().toISOString().substring(0, 10));
        const targetDateObj = new Date(targetDate + 'T12:00:00+07:00');
        const dayOfWeek = targetDateObj.getDay();
        const dayNames = ['Minggu', 'Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat', 'Sabtu'];
        const dayName = dayNames[dayOfWeek];

        const pType = String(prayerType || (dayOfWeek === 5 ? 'JUMAT' : 'DHUHUR')).toUpperCase();

        const sigs = await getSchoolSignatures();

        // Ambil jadwal sholat hari itu
        const schedule = await (prisma as any).prayerClassSchedule.findFirst({
            where: { dayOfWeek, isActive: true }
        });

        const wudhuTime = schedule?.wudhuTime || '11:45';
        const scanStartTime = schedule?.scanStartTime || '12:00';
        const scanEndTime = schedule?.scanEndTime || '12:30';

        const formattedDate = targetDateObj.toLocaleDateString('id-ID', { day: 'numeric', month: 'long', year: 'numeric' });

        const teacherName = (req as any).user?.name || 'Guru Pembina PAI';
        const teacherNip = (req as any).user?.nip || '-';

        let html = '';

        if (isAllClasses) {
            // Ambil seluruh rombel kelas
            const allClasses = await prisma.class.findMany({
                orderBy: { name: 'asc' }
            });

            // Ambil semua siswa aktif
            const allStudents = await prisma.user.findMany({
                where: { role: 'STUDENT', isActive: true },
                select: { id: true, name: true, nisn: true, gender: true, className: true },
                orderBy: [{ className: 'asc' }, { name: 'asc' }]
            });

            // Ambil seluruh rekaman presensi sholat hari tersebut
            const attendances = await (prisma as any).studentPrayerAttendance.findMany({
                where: {
                    date: targetDate,
                    prayerType: pType
                }
            });

            const attMap = new Map<string, any>();
            attendances.forEach((a: any) => attMap.set(a.studentId, a));

            // Map siswa berdasarkan rombel kelas
            const studentsByClass = new Map<string, any[]>();
            allStudents.forEach(s => {
                const cName = s.className || 'Tanpa Kelas';
                if (!studentsByClass.has(cName)) studentsByClass.set(cName, []);
                studentsByClass.get(cName)!.push(s);
            });

            let grandTotalStudents = 0;
            let grandTotalSholat = 0;
            let grandTotalHaid = 0;
            let grandTotalAlpha = 0;

            const classList = allClasses.length > 0
                ? allClasses.map(c => ({ name: c.name, homeroomTeacher: (c as any).homeroomTeacher || (c as any).homeroom || '-' }))
                : Array.from(studentsByClass.keys()).sort().map(k => ({ name: k, homeroomTeacher: '-' }));

            const classSummaries: any[] = [];

            classList.forEach((clsObj, idx) => {
                const cStudents = studentsByClass.get(clsObj.name) || [];
                let cSholat = 0;
                let cHaid = 0;
                let cAlpha = 0;

                cStudents.forEach(s => {
                    const att = attMap.get(s.id);
                    const status = att ? att.status : 'ALPHA';
                    if (status === 'SHOLAT_BERJAMAAH') cSholat++;
                    else if (status === 'BERHALANGAN_HAID') cHaid++;
                    else cAlpha++;
                });

                const totalInClass = cStudents.length;
                grandTotalStudents += totalInClass;
                grandTotalSholat += cSholat;
                grandTotalHaid += cHaid;
                grandTotalAlpha += cAlpha;

                const percent = totalInClass > 0 ? ((cSholat / totalInClass) * 100).toFixed(1) : '0';

                classSummaries.push({
                    no: idx + 1,
                    className: clsObj.name,
                    homeroom: clsObj.homeroomTeacher || '-',
                    total: totalInClass,
                    sholat: cSholat,
                    haid: cHaid,
                    alpha: cAlpha,
                    percent: percent + '%'
                });
            });

            const grandPercent = grandTotalStudents > 0 ? ((grandTotalSholat / grandTotalStudents) * 100).toFixed(1) + '%' : '0%';

            const summaryRowsHtml = classSummaries.map(cs => `
                <tr style="border-bottom: 1px solid #e2e8f0; font-size: 11px;">
                    <td style="padding: 5px 8px; text-align: center; color: #64748b;">${cs.no}</td>
                    <td style="padding: 5px 8px; font-weight: bold; text-align: center; color: #1e293b;">${cs.className}</td>
                    <td style="padding: 5px 8px; color: #334155;">${cs.homeroom}</td>
                    <td style="padding: 5px 8px; text-align: center; font-weight: bold;">${cs.total}</td>
                    <td style="padding: 5px 8px; text-align: center; font-weight: bold; color: #15803d; background: #f0fdf4;">${cs.sholat}</td>
                    <td style="padding: 5px 8px; text-align: center; font-weight: bold; color: #be185d; background: #fdf2f8;">${cs.haid}</td>
                    <td style="padding: 5px 8px; text-align: center; font-weight: bold; color: #b91c1c; background: #fef2f2;">${cs.alpha}</td>
                    <td style="padding: 5px 8px; text-align: center; font-weight: bold; color: #0369a1;">${cs.percent}</td>
                </tr>
            `).join('');

            let detailTablesHtml = '';
            classList.forEach(clsObj => {
                const cStudents = studentsByClass.get(clsObj.name) || [];
                if (cStudents.length === 0) return;

                const studentRows = cStudents.map((s, sIdx) => {
                    const att = attMap.get(s.id);
                    const genderStr = (s.gender || 'L').toUpperCase().startsWith('P') ? 'P' : 'L';
                    const status = att ? att.status : 'ALPHA';
                    const recTime = att?.createdAt ? new Date(att.createdAt).toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' }) + ' WIB' : '-';
                    const method = att?.method === 'STATIC_BARCODE' ? 'Scan Barcode' : (att?.method === 'MANUAL_PAI' ? 'Guru PAI' : '-');

                    let badgeHtml = '';
                    if (status === 'SHOLAT_BERJAMAAH') {
                        badgeHtml = '<span style="display:inline-block; padding:2px 8px; border-radius:9999px; font-weight:700; font-size:9.5px; background:#dcfce7; color:#15803d; border:1px solid #86efac;">🕌 SHOLAT</span>';
                    } else if (status === 'BERHALANGAN_HAID') {
                        badgeHtml = '<span style="display:inline-block; padding:2px 8px; border-radius:9999px; font-weight:700; font-size:9.5px; background:#fce7f3; color:#be185d; border:1px solid #fbcfe8;">🌸 HAID</span>';
                    } else {
                        badgeHtml = '<span style="display:inline-block; padding:2px 8px; border-radius:9999px; font-weight:700; font-size:9.5px; background:#fee2e2; color:#b91c1c; border:1px solid #fca5a5;">❌ ALPHA</span>';
                    }

                    return `
                        <tr style="border-bottom: 1px solid #e2e8f0; font-size: 10px;">
                            <td style="padding: 4px 6px; text-align: center; color: #64748b;">${sIdx + 1}</td>
                            <td style="padding: 4px 6px; font-family: monospace; text-align: center;">${s.nisn || '-'}</td>
                            <td style="padding: 4px 6px; font-weight: 600; color: #1e293b;">${s.name}</td>
                            <td style="padding: 4px 6px; text-align: center; font-weight: bold; color: ${genderStr === 'P' ? '#db2777' : '#0284c7'};">${genderStr}</td>
                            <td style="padding: 4px 6px; text-align: center;">${badgeHtml}</td>
                            <td style="padding: 4px 6px; text-align: center; font-family: monospace;">${recTime}</td>
                            <td style="padding: 4px 6px; font-size: 9.5px; color: #475569;">${method}</td>
                            <td style="padding: 4px 6px; font-size: 9.5px; color: #64748b;">${att?.notes || '-'}</td>
                        </tr>
                    `;
                }).join('');

                detailTablesHtml += `
                    <div style="margin-top: 18px; page-break-inside: avoid;">
                        <div style="background: #f1f5f9; padding: 6px 10px; border-left: 4px solid #4f46e5; font-weight: bold; font-size: 11px; margin-bottom: 4px; display: flex; justify-content: space-between;">
                            <span>🏢 Rombel: <b>${clsObj.name}</b> (Wali: ${clsObj.homeroomTeacher})</span>
                            <span style="color: #475569; font-weight: 600;">Total: ${cStudents.length} Siswa</span>
                        </div>
                        <table class="data-table" style="margin-top: 0; margin-bottom: 8px;">
                            <thead>
                                <tr>
                                    <th style="width: 25px;">No</th>
                                    <th style="width: 85px;">NISN</th>
                                    <th>Nama Siswa</th>
                                    <th style="width: 30px;">L/P</th>
                                    <th style="width: 100px;">Status</th>
                                    <th style="width: 65px;">Waktu</th>
                                    <th style="width: 90px;">Metode</th>
                                    <th style="width: 100px;">Catatan</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${studentRows}
                            </tbody>
                        </table>
                    </div>
                `;
            });

            html = `
<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <title>Rekap Presensi Sholat ${pType} - Seluruh Rombel Kelas - ${targetDate}</title>
    <style>
        @page { size: A4 portrait; margin: 12mm 12mm 12mm 12mm; }
        body { font-family: 'Times New Roman', Times, serif; color: #000; margin: 0; padding: 0; line-height: 1.25; font-size: 11px; }
        .kop-table { width: 100%; border-bottom: 3px double #000; padding-bottom: 6px; margin-bottom: 10px; }
        .kop-logo { width: 70px; height: 70px; object-fit: contain; }
        .kop-text { text-align: center; }
        .kop-instansi { font-size: 12px; font-weight: bold; letter-spacing: 0.5px; text-transform: uppercase; margin: 0; }
        .kop-school { font-size: 16px; font-weight: 900; letter-spacing: 1px; text-transform: uppercase; margin: 2px 0; }
        .kop-address { font-size: 9.5px; font-style: italic; margin: 0; color: #111; }
        .doc-title { text-align: center; font-size: 13px; font-weight: bold; text-decoration: underline; margin-top: 8px; text-transform: uppercase; }
        .doc-subtitle { text-align: center; font-size: 10.5px; margin-top: 2px; margin-bottom: 12px; font-weight: bold; }
        .meta-table { width: 100%; margin-bottom: 10px; font-size: 11px; }
        .meta-table td { padding: 2px 4px; vertical-align: top; }
        .stat-grid { display: flex; gap: 8px; margin-bottom: 12px; }
        .stat-box { flex: 1; border: 1px solid #94a3b8; border-radius: 6px; padding: 6px 8px; text-align: center; background: #f8fafc; font-size: 10.5px; }
        .stat-box b { font-size: 13.5px; display: block; margin-top: 2px; }
        table.data-table { width: 100%; border-collapse: collapse; margin-top: 4px; font-family: Arial, Helvetica, sans-serif; }
        table.data-table th { background: #0f172a; color: #fff; border: 1px solid #334155; padding: 5px 6px; font-size: 10px; text-align: center; }
        table.data-table td { border: 1px solid #cbd5e1; vertical-align: middle; }
        .sig-table { width: 100%; margin-top: 24px; page-break-inside: avoid; }
        .sig-table td { width: 50%; text-align: center; vertical-align: top; font-size: 11px; }
        .no-print { margin-bottom: 14px; padding: 8px 14px; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; text-align: center; font-family: Arial, sans-serif; }
        @media print {
            .no-print { display: none !important; }
            body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
        }
    </style>
</head>
<body>
    <div class="no-print">
        <button onclick="window.print()" style="background:#0284c7; color:#fff; font-weight:bold; padding:8px 20px; border:none; border-radius:6px; cursor:pointer; font-size:13px;">🖨️ Cetak / Simpan PDF</button>
        <span style="margin-left: 12px; font-size: 12px; color: #475569;">Rekap Seluruh Rombel Kelas (${classList.length} Kelas • ${grandTotalStudents} Siswa)</span>
    </div>

    <!-- Kop Surat Resmi -->
    <table class="kop-table">
        <tr>
            <td style="width: 75px; text-align: center;">
                <img src="/images/logo-smpn1.png" onerror="this.src='/images/kemdikbud.png'" class="kop-logo" alt="Logo">
            </td>
            <td class="kop-text">
                <p class="kop-instansi">PEMERINTAH KABUPATEN TULUNGAGUNG<br>DINAS PENDIDIKAN</p>
                <h1 class="kop-school">${sigs.schoolName.toUpperCase()}</h1>
                <p class="kop-address">Jalan Raya Boyolangu, Kec. Boyolangu, Kab. Tulungagung 66271 | Telp. 0355324146<br>Website: www.smpn1boyolangu.sch.id • Surel: info@smpn1boyolangu.sch.id</p>
            </td>
            <td style="width: 75px; text-align: center;">
                <img src="/images/kemdikbud.png" class="kop-logo" alt="Logo Kemdikbud">
            </td>
        </tr>
    </table>

    <div class="doc-title">REKAPITULASI PRESENSI SHOLAT ${pType} BERJAMAAH &amp; KEPUTRIAN</div>
    <div class="doc-subtitle">SELURUH ROMBEL KELAS • TAHUN AJARAN 2025/2026 • MASJID ${sigs.schoolName.toUpperCase()}</div>

    <table class="meta-table">
        <tr>
            <td style="width: 15%; font-weight: bold;">Cakupan Rombel</td>
            <td style="width: 35%;">: <b>Seluruh Rombel (${classList.length} Rombel Kelas)</b></td>
            <td style="width: 18%; font-weight: bold;">Hari / Tanggal</td>
            <td style="width: 32%;">: ${dayName}, ${formattedDate}</td>
        </tr>
        <tr>
            <td style="font-weight: bold;">Jenis Ibadah</td>
            <td>: Sholat ${pType === 'JUMAT' ? 'Jumat Berjamaah (Putra) / Keputrian (Putri)' : 'Dhuhur Berjamaah'}</td>
            <td style="font-weight: bold;">Jadwal Ibadah</td>
            <td>: Wudhu: <b>${wudhuTime} WIB</b> • Scan: <b>${scanStartTime} - ${scanEndTime} WIB</b></td>
        </tr>
    </table>

    <div class="stat-grid">
        <div class="stat-box">
            Total Seluruh Siswa
            <b style="color: #0f172a;">${grandTotalStudents}</b>
        </div>
        <div class="stat-box" style="background:#f0fdf4; border-color:#86efac;">
            Hadir Berjamaah
            <b style="color: #15803d;">${grandTotalSholat} (${grandPercent})</b>
        </div>
        <div class="stat-box" style="background:#fdf2f8; border-color:#fbcfe8;">
            Berhalangan (Haid)
            <b style="color: #be185d;">${grandTotalHaid}</b>
        </div>
        <div class="stat-box" style="background:#fef2f2; border-color:#fca5a5;">
            Alpha / Belum
            <b style="color: #b91c1c;">${grandTotalAlpha}</b>
        </div>
    </div>

    <!-- Bagian 1: Ringkasan Rekapitulasi Per Rombel -->
    <h4 style="margin: 10px 0 4px 0; font-size: 11.5px; font-weight: bold; text-transform: uppercase;">I. Rekapitulasi Kehadiran Sholat Per Rombel Kelas</h4>
    <table class="data-table">
        <thead>
            <tr>
                <th style="width: 30px;">No</th>
                <th style="width: 90px;">Rombel</th>
                <th>Wali Kelas</th>
                <th style="width: 65px;">Total Siswa</th>
                <th style="width: 75px;">Hadir Sholat</th>
                <th style="width: 75px;">Haid</th>
                <th style="width: 75px;">Alpha</th>
                <th style="width: 70px;">% Hadir</th>
            </tr>
        </thead>
        <tbody>
            ${summaryRowsHtml}
        </tbody>
        <tfoot>
            <tr style="background: #0f172a; color: #fff; font-weight: bold; font-size: 11px; text-align: center;">
                <td colspan="3" style="padding: 6px 8px; text-align: right;">TOTAL KESELURUHAN:</td>
                <td style="padding: 6px 8px;">${grandTotalStudents}</td>
                <td style="padding: 6px 8px; color: #86efac;">${grandTotalSholat}</td>
                <td style="padding: 6px 8px; color: #fbcfe8;">${grandTotalHaid}</td>
                <td style="padding: 6px 8px; color: #fca5a5;">${grandTotalAlpha}</td>
                <td style="padding: 6px 8px; color: #7dd3fc;">${grandPercent}</td>
            </tr>
        </tfoot>
    </table>

    <!-- Bagian 2: Rincian Presensi Seluruh Siswa Per Kelas -->
    <h4 style="margin: 18px 0 4px 0; font-size: 11.5px; font-weight: bold; text-transform: uppercase;">II. Rincian Presensi Siswa Masing-Masing Kelas</h4>
    ${detailTablesHtml}

    <!-- Tanda Tangan Resmi Dinamis Sesuai Pengaturan Sistem -->
    <table class="sig-table">
        <tr>
            <td>
                Mengetahui,<br>
                ${sigs.headmasterTitle}
                <div style="height: 65px;"></div>
                <b><u>${sigs.headmasterName}</u></b><br>
                NIP. ${sigs.headmasterNip}
            </td>
            <td>
                Tulungagung, ${formattedDate}<br>
                Guru Pembina PAI &amp; Budi Pekerti
                <div style="height: 65px;"></div>
                <b><u>${teacherName}</u></b><br>
                NIP. ${teacherNip}
            </td>
        </tr>
    </table>

    ${autoprint === '1' ? '<script>window.addEventListener("load", () => { setTimeout(() => window.print(), 500); });</script>' : ''}
</body>
</html>
            `;
        } else {
            // SINGLE CLASS RECAP
            const students = await prisma.user.findMany({
                where: { role: 'STUDENT', className: cleanClass },
                select: { id: true, name: true, nisn: true, gender: true, className: true },
                orderBy: { name: 'asc' }
            });

            let thColumnsHtml = '';
            let rowsHtml = '';
            let periodSubtitle = '';
            let sholatCount = 0;
            let haidCount = 0;
            let alphaCount = 0;

            if (isWeekly) {
                // Tentukan tanggal Senin sampai Jumat pekan ini
                const refDate = new Date(targetDate + 'T12:00:00+07:00');
                const day = refDate.getDay();
                const diffToMonday = day === 0 ? -6 : 1 - day;
                const mondayDate = new Date(refDate);
                mondayDate.setDate(refDate.getDate() + diffToMonday);

                const weekDays: { dateStr: string; dayName: string }[] = [];
                const dayLabels = ['Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat'];
                for (let i = 0; i < 5; i++) {
                    const d = new Date(mondayDate);
                    d.setDate(mondayDate.getDate() + i);
                    weekDays.push({
                        dateStr: d.toISOString().substring(0, 10),
                        dayName: dayLabels[i]
                    });
                }

                const weekDates = weekDays.map(w => w.dateStr);
                const attendances = await (prisma as any).studentPrayerAttendance.findMany({
                    where: {
                        className: cleanClass,
                        date: { in: weekDates },
                        prayerType: pType
                    }
                });

                const attWeekMap = new Map<string, Map<string, string>>();
                attendances.forEach((a: any) => {
                    if (!attWeekMap.has(a.studentId)) attWeekMap.set(a.studentId, new Map());
                    attWeekMap.get(a.studentId)!.set(a.date, a.status);
                });

                thColumnsHtml = `
                    <th style="width: 25px;">No</th>
                    <th style="width: 85px;">NISN</th>
                    <th>Nama Siswa</th>
                    <th style="width: 30px;">L/P</th>
                    ${weekDays.map(w => `<th style="width: 55px;">${w.dayName}<br><span style="font-size:8px; font-weight:normal;">${w.dateStr.substring(5)}</span></th>`).join('')}
                    <th style="width: 45px; background: #065f46;">Sholat</th>
                    <th style="width: 45px; background: #9d174d;">Haid</th>
                    <th style="width: 45px; background: #991b1b;">Alpha</th>
                    <th style="width: 45px; background: #1e40af;">%</th>
                `;

                rowsHtml = students.map((s, idx) => {
                    const sAtts = attWeekMap.get(s.id) || new Map();
                    let sSholat = 0;
                    let sHaid = 0;
                    let sAlpha = 0;

                    const dayCells = weekDays.map(w => {
                        const st = sAtts.get(w.dateStr);
                        if (st === 'SHOLAT_BERJAMAAH') {
                            sSholat++;
                            return '<td style="padding: 5px; text-align: center; color: #15803d; font-weight: bold; background: #f0fdf4;">🕌 H</td>';
                        } else if (st === 'BERHALANGAN_HAID') {
                            sHaid++;
                            return '<td style="padding: 5px; text-align: center; color: #be185d; font-weight: bold; background: #fdf2f8;">🌸 Haid</td>';
                        } else {
                            sAlpha++;
                            return '<td style="padding: 5px; text-align: center; color: #b91c1c; font-weight: bold; background: #fef2f2;">-</td>';
                        }
                    }).join('');

                    sholatCount += sSholat;
                    haidCount += sHaid;
                    alphaCount += sAlpha;

                    const percent = ((sSholat / 5) * 100).toFixed(0);
                    const genderStr = (s.gender || 'L').toUpperCase().startsWith('P') ? 'P' : 'L';

                    return `
                        <tr style="border-bottom: 1px solid #e2e8f0; font-size: 11px;">
                            <td style="padding: 5px 6px; text-align: center; color: #64748b;">${idx + 1}</td>
                            <td style="padding: 5px 6px; font-family: monospace; font-size: 10.5px; text-align: center;">${s.nisn || '-'}</td>
                            <td style="padding: 5px 6px; font-weight: 600; color: #1e293b;">${s.name}</td>
                            <td style="padding: 5px 6px; text-align: center; font-weight: bold; color: ${genderStr === 'P' ? '#db2777' : '#0284c7'};">${genderStr}</td>
                            ${dayCells}
                            <td style="padding: 5px 6px; text-align: center; font-weight: bold; color: #15803d;">${sSholat}</td>
                            <td style="padding: 5px 6px; text-align: center; font-weight: bold; color: #be185d;">${sHaid}</td>
                            <td style="padding: 5px 6px; text-align: center; font-weight: bold; color: #b91c1c;">${sAlpha}</td>
                            <td style="padding: 5px 6px; text-align: center; font-weight: bold; color: #0284c7;">${percent}%</td>
                        </tr>
                    `;
                }).join('');

                periodSubtitle = `PERIODE 1 MINGGU PENUH (${weekDays[0].dateStr} s.d. ${weekDays[4].dateStr})`;
            } else {
                // DAILY RECAP
                const attendances = await (prisma as any).studentPrayerAttendance.findMany({
                    where: {
                        className: cleanClass,
                        date: targetDate,
                        prayerType: pType
                    }
                });

                const attMap = new Map();
                attendances.forEach((a: any) => attMap.set(a.studentId, a));

                thColumnsHtml = `
                    <th style="width: 25px;">No</th>
                    <th style="width: 85px;">NISN</th>
                    <th>Nama Siswa</th>
                    <th style="width: 30px;">L/P</th>
                    <th style="width: 140px;">Status</th>
                    <th style="width: 75px;">Waktu</th>
                    <th style="width: 110px;">Metode</th>
                    <th>Catatan</th>
                `;

                rowsHtml = students.map((s, idx) => {
                    const att = attMap.get(s.id);
                    const genderStr = (s.gender || 'L').toUpperCase().startsWith('P') ? 'P' : 'L';
                    const status = att ? att.status : 'ALPHA';
                    const recTime = att?.createdAt ? new Date(att.createdAt).toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' }) + ' WIB' : '-';
                    const method = att?.method === 'STATIC_BARCODE' ? 'Scan Barcode Masjid' : (att?.method === 'MANUAL_PAI' ? 'Verifikasi Guru PAI' : '-');

                    let badgeHtml = '';
                    if (status === 'SHOLAT_BERJAMAAH') {
                        sholatCount++;
                        badgeHtml = '<span style="display:inline-block; padding:3px 10px; border-radius:9999px; font-weight:700; font-size:10px; background:#dcfce7; color:#15803d; border:1px solid #86efac;">🕌 HADIR BERJAMAAH</span>';
                    } else if (status === 'BERHALANGAN_HAID') {
                        haidCount++;
                        badgeHtml = '<span style="display:inline-block; padding:3px 10px; border-radius:9999px; font-weight:700; font-size:10px; background:#fce7f3; color:#be185d; border:1px solid #fbcfe8;">🌸 BERHALANGAN (HAID)</span>';
                    } else {
                        alphaCount++;
                        badgeHtml = '<span style="display:inline-block; padding:3px 10px; border-radius:9999px; font-weight:700; font-size:10px; background:#fee2e2; color:#b91c1c; border:1px solid #fca5a5;">❌ BELUM PRESENSI / ALPHA</span>';
                    }

                    return `
                        <tr style="border-bottom: 1px solid #e2e8f0; font-size: 11px;">
                            <td style="padding: 6px 8px; text-align: center; color: #64748b;">${idx + 1}</td>
                            <td style="padding: 6px 8px; font-family: monospace; font-size: 11px; text-align: center;">${s.nisn || '-'}</td>
                            <td style="padding: 6px 8px; font-weight: 600; color: #1e293b;">${s.name}</td>
                            <td style="padding: 6px 8px; text-align: center; font-weight: 700; color: ${genderStr === 'P' ? '#db2777' : '#0284c7'};">${genderStr}</td>
                            <td style="padding: 6px 8px; text-align: center;">${badgeHtml}</td>
                            <td style="padding: 6px 8px; text-align: center; font-family: monospace; font-size: 10px;">${recTime}</td>
                            <td style="padding: 6px 8px; font-size: 10px; color: #475569;">${method}</td>
                            <td style="padding: 6px 8px; font-size: 10px; color: #64748b;">${att?.notes || '-'}</td>
                        </tr>
                    `;
                }).join('');

                periodSubtitle = `HARIAN • ${dayName}, ${formattedDate}`;
            }

            html = `
<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <title>Rekap Presensi Sholat ${pType} - Kelas ${cleanClass} - ${targetDate}</title>
    <style>
        @page { size: A4 portrait; margin: 15mm 15mm 15mm 15mm; }
        body { font-family: 'Times New Roman', Times, serif; color: #000; margin: 0; padding: 0; line-height: 1.3; font-size: 12px; }
        .kop-table { width: 100%; border-bottom: 3px double #000; padding-bottom: 8px; margin-bottom: 14px; }
        .kop-logo { width: 75px; height: 75px; object-fit: contain; }
        .kop-text { text-align: center; }
        .kop-instansi { font-size: 13px; font-weight: bold; letter-spacing: 0.5px; text-transform: uppercase; margin: 0; }
        .kop-school { font-size: 17px; font-weight: 900; letter-spacing: 1px; text-transform: uppercase; margin: 2px 0; }
        .kop-address { font-size: 10px; font-style: italic; margin: 0; color: #111; }
        .doc-title { text-align: center; font-size: 13.5px; font-weight: bold; text-decoration: underline; margin-top: 10px; text-transform: uppercase; }
        .doc-subtitle { text-align: center; font-size: 11px; margin-top: 2px; margin-bottom: 14px; font-weight: bold; }
        .meta-table { width: 100%; margin-bottom: 12px; font-size: 11.5px; }
        .meta-table td { padding: 2px 4px; vertical-align: top; }
        .stat-grid { display: flex; gap: 10px; margin-bottom: 12px; }
        .stat-box { flex: 1; border: 1px solid #94a3b8; border-radius: 6px; padding: 6px 8px; text-align: center; background: #f8fafc; font-size: 11px; }
        .stat-box b { font-size: 14px; display: block; margin-top: 2px; }
        table.data-table { width: 100%; border-collapse: collapse; margin-top: 6px; font-family: Arial, Helvetica, sans-serif; }
        table.data-table th { background: #0f172a; color: #fff; border: 1px solid #334155; padding: 6px 6px; font-size: 10.5px; text-align: center; }
        table.data-table td { border: 1px solid #cbd5e1; vertical-align: middle; }
        .sig-table { width: 100%; margin-top: 28px; page-break-inside: avoid; }
        .sig-table td { width: 50%; text-align: center; vertical-align: top; font-size: 11.5px; }
        .no-print { margin-bottom: 16px; padding: 10px 16px; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; text-align: center; font-family: Arial, sans-serif; }
        @media print {
            .no-print { display: none !important; }
            body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
        }
    </style>
</head>
<body>
    <div class="no-print">
        <button onclick="window.print()" style="background:#0284c7; color:#fff; font-weight:bold; padding:8px 20px; border:none; border-radius:6px; cursor:pointer; font-size:13px;">🖨️ Cetak / Simpan PDF</button>
        <span style="margin-left: 12px; font-size: 12px; color: #475569;">Gunakan menu "Save as PDF" di dialog cetak browser Anda.</span>
    </div>

    <!-- Kop Surat Resmi -->
    <table class="kop-table">
        <tr>
            <td style="width: 80px; text-align: center;">
                <img src="/images/logo-smpn1.png" onerror="this.src='/images/kemdikbud.png'" class="kop-logo" alt="Logo">
            </td>
            <td class="kop-text">
                <p class="kop-instansi">PEMERINTAH KABUPATEN TULUNGAGUNG<br>DINAS PENDIDIKAN</p>
                <h1 class="kop-school">${sigs.schoolName.toUpperCase()}</h1>
                <p class="kop-address">Jalan Raya Boyolangu, Kec. Boyolangu, Kab. Tulungagung 66271 | Telp. 0355324146<br>Website: www.smpn1boyolangu.sch.id • Surel: info@smpn1boyolangu.sch.id</p>
            </td>
            <td style="width: 80px; text-align: center;">
                <img src="/images/kemdikbud.png" class="kop-logo" alt="Logo Kemdikbud">
            </td>
        </tr>
    </table>

    <div class="doc-title">REKAPITULASI PRESENSI SHOLAT ${pType} BERJAMAAH &amp; KEPUTRIAN</div>
    <div class="doc-subtitle">${periodSubtitle} • TAHUN AJARAN 2025/2026 • MASJID ${sigs.schoolName.toUpperCase()}</div>

    <table class="meta-table">
        <tr>
            <td style="width: 15%; font-weight: bold;">Rombel / Kelas</td>
            <td style="width: 35%;">: <b>${cleanClass}</b></td>
            <td style="width: 18%; font-weight: bold;">Periode Rekap</td>
            <td style="width: 32%;">: <b>${isWeekly ? '1 Minggu Penuh' : `${dayName}, ${formattedDate}`}</b></td>
        </tr>
        <tr>
            <td style="font-weight: bold;">Jenis Ibadah</td>
            <td>: Sholat ${pType === 'JUMAT' ? 'Jumat Berjamaah (Putra) / Keputrian (Putri)' : 'Dhuhur Berjamaah'}</td>
            <td style="font-weight: bold;">Jadwal Ibadah</td>
            <td>: Wudhu: <b>${wudhuTime} WIB</b> • Scan: <b>${scanStartTime} - ${scanEndTime} WIB</b></td>
        </tr>
    </table>

    <div class="stat-grid">
        <div class="stat-box">
            Total Siswa
            <b style="color: #0f172a;">${students.length}</b>
        </div>
        <div class="stat-box" style="background:#f0fdf4; border-color:#86efac;">
            ${isWeekly ? 'Akumulasi Hadir' : 'Hadir Berjamaah'}
            <b style="color: #15803d;">${sholatCount}</b>
        </div>
        <div class="stat-box" style="background:#fdf2f8; border-color:#fbcfe8;">
            ${isWeekly ? 'Akumulasi Haid' : 'Berhalangan (Haid)'}
            <b style="color: #be185d;">${haidCount}</b>
        </div>
        <div class="stat-box" style="background:#fef2f2; border-color:#fca5a5;">
            ${isWeekly ? 'Akumulasi Alpha' : 'Alpha / Belum'}
            <b style="color: #b91c1c;">${alphaCount}</b>
        </div>
    </div>

    <table class="data-table">
        <thead>
            <tr>
                ${thColumnsHtml}
            </tr>
        </thead>
        <tbody>
            ${rowsHtml || `<tr><td colspan="${isWeekly ? 9 : 8}" style="text-align:center; padding:12px; color:#64748b;">Tidak ada data siswa pada rombel ini</td></tr>`}
        </tbody>
    </table>

    <!-- Tanda Tangan Resmi Dinamis Sesuai Pengaturan Sistem -->
    <table class="sig-table">
        <tr>
            <td>
                Mengetahui,<br>
                ${sigs.headmasterTitle}
                <div style="height: 65px;"></div>
                <b><u>${sigs.headmasterName}</u></b><br>
                NIP. ${sigs.headmasterNip}
            </td>
            <td>
                Tulungagung, ${formattedDate}<br>
                Guru Pembina PAI &amp; Budi Pekerti
                <div style="height: 65px;"></div>
                <b><u>${teacherName}</u></b><br>
                NIP. ${teacherNip}
            </td>
        </tr>
    </table>

    ${autoprint === '1' ? '<script>window.addEventListener("load", () => { setTimeout(() => window.print(), 500); });</script>' : ''}
</body>
</html>
            `;
        }

        if (download === '1') {
            res.setHeader('Content-Disposition', `attachment; filename="Rekap_Sholat_${cleanClass}_${targetDate}.html"`);
        }
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.send(html);
    } catch (error: any) {
        console.error('Error in exportPrayerAttendancePdf:', error);
        res.status(500).send('Gagal mengekspor laporan presensi sholat: ' + error.message);
    }
};
