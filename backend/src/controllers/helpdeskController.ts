import { Request, Response } from 'express';
import prisma from '../utils/db';
import { logAudit } from '../services/auditLogger';

export const submitHelpdeskReport = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { category, title, description, deviceInfo } = req.body;

        if (!description || !category) {
            return res.status(400).json({ success: false, message: 'Kategori dan Deskripsi kendala wajib diisi' });
        }

        const senderRole = user?.role || 'UNKNOWN';
        const senderName = user?.name || 'Pengguna';
        const senderUsername = user?.username || '-';

        // Catat ke NotificationMessage agar muncul di panel pengumuman/notifikasi Super Admin
        const notifTitle = `🚨 [LAPOR KENDALA] ${category} - ${senderName} (${senderRole})`;
        const notifMessage = `Pengguna: ${senderName} (${senderUsername})\nRole: ${senderRole}\nKendala: ${description}\nDevice: ${deviceInfo || 'Android App'}`;

        await prisma.notificationMessage.create({
            data: {
                recipientRole: 'ADMIN',
                title: notifTitle,
                message: notifMessage,
                category: 'HELPDESK'
            }
        });

        // Catat di Audit Log untuk jejak sistem
        await logAudit(req, 'HELPDESK_REPORT', `Category: ${category}, User: ${senderUsername}`, {
            senderRole,
            senderName,
            title: title || 'Laporan Kendala Aplikasi',
            description,
            deviceInfo
        });

        res.json({
            success: true,
            message: 'Laporan kendala berhasil dikirim ke Administrator. Tim teknis akan segera menindaklanjuti.'
        });
    } catch (error: any) {
        console.error('Error submitting helpdesk report:', error);
        res.status(500).json({ success: false, message: 'Gagal mengirim laporan kendala: ' + error.message });
    }
};

export const getHelpdeskReports = async (req: Request, res: Response) => {
    try {
        const reports = await prisma.notificationMessage.findMany({
            where: { category: 'HELPDESK' },
            orderBy: { sentAt: 'desc' },
            take: 50
        });
        res.json({ success: true, reports });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat laporan kendala' });
    }
};
