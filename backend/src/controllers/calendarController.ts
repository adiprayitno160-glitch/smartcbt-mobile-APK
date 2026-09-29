import { Request, Response } from 'express';
import prisma from '../utils/db';

interface HolidayItem {
    date: string;       // YYYY-MM-DD
    name: string;
    isNationalHoliday: boolean;
    description?: string;
}

// Kalender Resmi Hari Libur Nasional Indonesia (2025, 2026, 2027)
const BUILTIN_HOLIDAYS: Record<string, HolidayItem[]> = {
    '2025': [
        { date: '2025-01-01', name: 'Tahun Baru 2025 Masehi', isNationalHoliday: true },
        { date: '2025-01-27', name: 'Isra Mikraj Nabi Muhammad SAW', isNationalHoliday: true },
        { date: '2025-01-29', name: 'Tahun Baru Imlek 2576 Kongzili', isNationalHoliday: true },
        { date: '2025-03-29', name: 'Hari Suci Nyepi (Tahun Baru Saka 1947)', isNationalHoliday: true },
        { date: '2025-03-31', name: 'Hari Raya Idul Fitri 1446 H', isNationalHoliday: true },
        { date: '2025-04-01', name: 'Hari Raya Idul Fitri 1446 H (Hari Kedua)', isNationalHoliday: true },
        { date: '2025-04-18', name: 'Wafat Yesus Kristus', isNationalHoliday: true },
        { date: '2025-04-20', name: 'Kebangkitan Yesus Kristus (Paskah)', isNationalHoliday: true },
        { date: '2025-05-01', name: 'Hari Buruh Internasional', isNationalHoliday: true },
        { date: '2025-05-12', name: 'Hari Raya Waisak 2569 BE', isNationalHoliday: true },
        { date: '2025-05-29', name: 'Kenaikan Yesus Kristus', isNationalHoliday: true },
        { date: '2025-06-01', name: 'Hari Lahir Pancasila', isNationalHoliday: true },
        { date: '2025-06-06', name: 'Hari Raya Idul Adha 1446 H', isNationalHoliday: true },
        { date: '2025-06-27', name: '1 Muharram Tahun Baru Islam 1447 H', isNationalHoliday: true },
        { date: '2025-08-17', name: 'Proklamasi Kemerdekaan RI Ke-80', isNationalHoliday: true },
        { date: '2025-09-05', name: 'Maulid Nabi Muhammad SAW', isNationalHoliday: true },
        { date: '2025-12-25', name: 'Kelahiran Yesus Kristus (Hari Raya Natal)', isNationalHoliday: true }
    ],
    '2026': [
        { date: '2026-01-01', name: 'Tahun Baru 2026 Masehi', isNationalHoliday: true },
        { date: '2026-01-16', name: 'Isra Mikraj Nabi Muhammad SAW', isNationalHoliday: true },
        { date: '2026-02-17', name: 'Tahun Baru Imlek 2577 Kongzili', isNationalHoliday: true },
        { date: '2026-03-19', name: 'Hari Suci Nyepi (Tahun Baru Saka 1948)', isNationalHoliday: true },
        { date: '2026-03-20', name: 'Hari Raya Idul Fitri 1447 H', isNationalHoliday: true },
        { date: '2026-03-21', name: 'Hari Raya Idul Fitri 1447 H (Hari Kedua)', isNationalHoliday: true },
        { date: '2026-04-03', name: 'Wafat Yesus Kristus (Jumat Agung)', isNationalHoliday: true },
        { date: '2026-04-05', name: 'Hari Paskah', isNationalHoliday: true },
        { date: '2026-05-01', name: 'Hari Buruh Internasional', isNationalHoliday: true },
        { date: '2026-05-14', name: 'Kenaikan Yesus Kristus', isNationalHoliday: true },
        { date: '2026-05-31', name: 'Hari Raya Waisak 2570 BE', isNationalHoliday: true },
        { date: '2026-06-01', name: 'Hari Lahir Pancasila', isNationalHoliday: true },
        { date: '2026-05-27', name: 'Hari Raya Idul Adha 1447 H', isNationalHoliday: true },
        { date: '2026-06-16', name: '1 Muharram Tahun Baru Islam 1448 H', isNationalHoliday: true },
        { date: '2026-08-17', name: 'Proklamasi Kemerdekaan Republik Indonesia Ke-81', isNationalHoliday: true },
        { date: '2026-08-25', name: 'Maulid Nabi Muhammad SAW', isNationalHoliday: true },
        { date: '2026-12-25', name: 'Hari Raya Natal', isNationalHoliday: true }
    ],
    '2027': [
        { date: '2027-01-01', name: 'Tahun Baru 2027 Masehi', isNationalHoliday: true },
        { date: '2027-01-06', name: 'Isra Mikraj Nabi Muhammad SAW', isNationalHoliday: true },
        { date: '2027-02-06', name: 'Tahun Baru Imlek 2578 Kongzili', isNationalHoliday: true },
        { date: '2027-03-09', name: 'Hari Raya Idul Fitri 1448 H', isNationalHoliday: true },
        { date: '2027-03-10', name: 'Hari Raya Idul Fitri 1448 H (Hari Kedua)', isNationalHoliday: true },
        { date: '2027-03-26', name: 'Wafat Yesus Kristus', isNationalHoliday: true },
        { date: '2027-04-08', name: 'Hari Suci Nyepi', isNationalHoliday: true },
        { date: '2027-05-01', name: 'Hari Buruh Internasional', isNationalHoliday: true },
        { date: '2027-05-06', name: 'Kenaikan Yesus Kristus', isNationalHoliday: true },
        { date: '2027-05-20', name: 'Hari Raya Waisak 2571 BE', isNationalHoliday: true },
        { date: '2027-05-16', name: 'Hari Raya Idul Adha 1448 H', isNationalHoliday: true },
        { date: '2027-06-01', name: 'Hari Lahir Pancasila', isNationalHoliday: true },
        { date: '2027-06-06', name: 'Tahun Baru Islam 1449 H', isNationalHoliday: true },
        { date: '2027-08-15', name: 'Maulid Nabi Muhammad SAW', isNationalHoliday: true },
        { date: '2027-08-17', name: 'HUT Proklamasi Kemerdekaan RI Ke-82', isNationalHoliday: true },
        { date: '2027-12-25', name: 'Hari Raya Natal', isNationalHoliday: true }
    ]
};

export const getHolidays = async (req: Request, res: Response) => {
    try {
        const now = new Date();
        const year = String(req.query.year || now.getFullYear());
        const month = req.query.month ? String(req.query.month).padStart(2, '0') : null;

        let holidays: HolidayItem[] = BUILTIN_HOLIDAYS[year] || BUILTIN_HOLIDAYS['2026'];

        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 2000);
            const onlineRes = await fetch(`https://dayoffapi.vercel.app/api?year=${year}`, { signal: controller.signal });
            clearTimeout(timeoutId);
            if (onlineRes.ok) {
                const onlineData = await onlineRes.json();
                if (Array.isArray(onlineData) && onlineData.length > 0) {
                    holidays = onlineData.map((item: any) => ({
                        date: item.holiday_date,
                        name: item.holiday_name,
                        isNationalHoliday: item.is_national_holiday ?? true
                    }));
                }
            }
        } catch (onlineErr) {
            // Fallback to built-in verified holidays
        }

        // Ambil hari libur sekolah kustom dari database
        const dbHolidays = await prisma.schoolHoliday.findMany({
            orderBy: { date: 'asc' }
        });

        const holidayMap = new Map<string, HolidayItem>();
        holidays.forEach(h => holidayMap.set(h.date, h));
        dbHolidays.forEach(h => {
            holidayMap.set(h.date, {
                date: h.date,
                name: h.name,
                isNationalHoliday: h.isNationalHoliday,
                description: h.description || undefined
            });
        });

        let merged = Array.from(holidayMap.values()).sort((a, b) => a.date.localeCompare(b.date));

        if (year) {
            merged = merged.filter(h => h.date.startsWith(year));
        }

        if (month) {
            const prefix = `${year}-${month}`;
            merged = merged.filter(h => h.date.startsWith(prefix));
        }

        res.json(merged);
    } catch (error) {
        console.error('Error fetching holidays:', error);
        res.status(500).json({ message: 'Gagal mengambil kalender hari libur.' });
    }
};

export const getSchoolHolidays = async (req: Request, res: Response) => {
    try {
        const list = await prisma.schoolHoliday.findMany({
            orderBy: { date: 'desc' }
        });
        res.json(list);
    } catch (error) {
        console.error('Error fetching school holidays:', error);
        res.status(500).json({ message: 'Gagal memuat daftar libur sekolah' });
    }
};

export const createOrUpdateHoliday = async (req: Request, res: Response) => {
    try {
        const { date, name, description, isNationalHoliday } = req.body;
        if (!date || !name) {
            return res.status(400).json({ message: 'Tanggal dan Nama / Alasan Libur wajib diisi' });
        }
        const user = (req as any).user;
        const holiday = await prisma.schoolHoliday.upsert({
            where: { date: String(date).trim() },
            update: {
                name: String(name).trim(),
                description: description ? String(description).trim() : null,
                isNationalHoliday: Boolean(isNationalHoliday),
                createdBy: user?.username || 'Admin'
            },
            create: {
                date: String(date).trim(),
                name: String(name).trim(),
                description: description ? String(description).trim() : null,
                isNationalHoliday: Boolean(isNationalHoliday),
                createdBy: user?.username || 'Admin'
            }
        });
        res.json({
            success: true,
            message: `✅ Hari Libur "${holiday.name}" (${holiday.date}) berhasil disimpan!`,
            holiday
        });
    } catch (error) {
        console.error('Error saving school holiday:', error);
        res.status(500).json({ message: 'Gagal menyimpan hari libur sekolah' });
    }
};

export const deleteHoliday = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        await prisma.schoolHoliday.delete({
            where: { id: String(id) }
        });
        res.json({ success: true, message: 'Hari libur berhasil dihapus' });
    } catch (error) {
        console.error('Error deleting school holiday:', error);
        res.status(500).json({ message: 'Gagal menghapus hari libur' });
    }
};
