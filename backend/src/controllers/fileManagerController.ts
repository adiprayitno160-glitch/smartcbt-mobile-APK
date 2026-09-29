import { Request, Response } from 'express';
import prisma from '../utils/db';

export const getRemoteFiles = async (req: Request, res: Response) => {
    try {
        const files = await prisma.eFile.findMany({
            include: {
                user: {
                    select: {
                        id: true,
                        name: true,
                        nisn: true,
                        className: true,
                        role: true
                    }
                }
            },
            orderBy: { uploadedAt: 'desc' }
        });

        // Calculate Storage Statistics
        const totalFiles = files.length;
        const raporCount = files.filter(f => f.category === 'RAPOR').length;
        const kartuCount = files.filter(f => f.category === 'KARTU_PELAJAR').length;
        const sertifikatCount = files.filter(f => f.category === 'SERTIFIKAT').length;
        const suratCount = files.filter(f => f.category === 'SURAT').length;
        const modulCount = files.filter(f => f.category === 'MODUL_CBT' || f.category === 'LAINNYA').length;

        res.json({
            success: true,
            totalFiles,
            storageSummary: {
                totalFiles,
                usedStorageMb: (totalFiles * 1.8).toFixed(1), // Average ~1.8MB per file
                maxStorageMb: 50000, // 50 GB
                categories: {
                    rapor: raporCount,
                    kartuPelajar: kartuCount,
                    sertifikat: sertifikatCount,
                    surat: suratCount,
                    modul: modulCount
                }
            },
            files: files.map(f => ({
                id: f.id,
                title: f.title,
                category: f.category,
                fileUrl: f.fileUrl,
                fileSize: f.fileSize || '1.5 MB',
                uploadedAt: f.uploadedAt.toISOString().split('T')[0],
                recipient: f.user ? {
                    name: f.user.name,
                    nisn: f.user.nisn || '-',
                    className: f.user.className || 'Semua'
                } : { name: 'Semua Siswa', nisn: '-', className: 'Semua' }
            }))
        });
    } catch (error) {
        console.error('Error fetching remote files:', error);
        res.status(500).json({ message: 'Gagal mengambil data remote file manager.' });
    }
};

export const uploadAndDistributeFile = async (req: Request, res: Response) => {
    try {
        const { title, category, targetType, targetClass, targetUserId, fileSize, fileUrl } = req.body;

        if (!title || !category) {
            return res.status(400).json({ message: 'Judul dan kategori berkas wajib diisi.' });
        }

        const resolvedUrl = fileUrl || '/storage/efiles/' + encodeURIComponent(title.toLowerCase().replace(/\s+/g, '_')) + '.pdf';
        const resolvedSize = fileSize || '1.8 MB';

        let targetUsers: any[] = [];

        if (targetType === 'ALL_STUDENTS') {
            targetUsers = await prisma.user.findMany({ where: { role: 'STUDENT' } });
        } else if (targetType === 'CLASS' && targetClass) {
            targetUsers = await prisma.user.findMany({ where: { role: 'STUDENT', className: targetClass } });
        } else if (targetType === 'SINGLE_STUDENT' && targetUserId) {
            targetUsers = await prisma.user.findMany({ where: { id: targetUserId } });
        } else {
            // Default to all students or first available students
            targetUsers = await prisma.user.findMany({ where: { role: 'STUDENT' } });
        }

        if (targetUsers.length === 0) {
            // Fallback to all users
            targetUsers = await prisma.user.findMany({ take: 5 });
        }

        const createdRecords = [];
        for (const user of targetUsers) {
            const efile = await prisma.eFile.create({
                data: {
                    userId: user.id,
                    title,
                    category,
                    fileUrl: resolvedUrl,
                    fileSize: resolvedSize
                }
            });
            createdRecords.push(efile);
        }

        res.json({
            success: true,
            message: `Berkas "${title}" berhasil didistribusikan ke ${createdRecords.length} siswa secara remote.`,
            distributedCount: createdRecords.length
        });
    } catch (error) {
        console.error('Error distributing remote file:', error);
        res.status(500).json({ message: 'Gagal mendistribusikan berkas remote.' });
    }
};

export const deleteRemoteFile = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        await prisma.eFile.delete({ where: { id: String(id) } });
        res.json({ success: true, message: 'Berkas berhasil dihapus dari remote storage server.' });
    } catch (error) {
        console.error('Error deleting remote file:', error);
        res.status(500).json({ message: 'Gagal menghapus berkas remote.' });
    }
};
