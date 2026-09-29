import { Request, Response } from 'express';
import prisma from '../utils/db';

export const getAppVersion = async (req: Request, res: Response) => {
    try {
        let config = await prisma.appVersionConfig.findUnique({ where: { id: 'latest' } });
        
        if (!config || config.versionCode < 100) {
            config = await prisma.appVersionConfig.upsert({
                where: { id: 'latest' },
                update: {
                    versionCode: 100,
                    versionName: '2.8.90',
                    downloadUrl: '/uploads/smartcbt-latest.apk',
                    fileSizeMb: 18.83,
                    releaseNotes: 'Pembaruan Resmi Smart CBT v2.8.90 (Build 100): Grafik Tren Kehadiran Siswa & Orang Tua, Validasi Satelit GPS Presisi Tinggi, Mode Offline Antrian Presensi Room DB, Scan QR Kelas Fallback Tanpa GPS, Panel Kontrol Fitur Terpadu.',
                    isForceUpdate: true
                },
                create: {
                    id: 'latest',
                    versionCode: 100,
                    versionName: '2.8.90',
                    downloadUrl: '/uploads/smartcbt-latest.apk',
                    fileSizeMb: 18.83,
                    releaseNotes: 'Pembaruan Resmi Smart CBT v2.8.90 (Build 100): Grafik Tren Kehadiran Siswa & Orang Tua, Validasi Satelit GPS Presisi Tinggi, Mode Offline Antrian Presensi Room DB, Scan QR Kelas Fallback Tanpa GPS, Panel Kontrol Fitur Terpadu.',
                    isForceUpdate: true
                }
            });
        }

        res.json({
            success: true,
            update: {
                versionCode: config.versionCode,
                versionName: config.versionName,
                downloadUrl: config.downloadUrl,
                fileSizeMb: config.fileSizeMb,
                releaseNotes: config.releaseNotes,
                isForceUpdate: config.isForceUpdate
            }
        });
    } catch (error) {
        console.error('Error fetching app version:', error);
        res.status(500).json({ message: 'Gagal mengecek pembaruan aplikasi' });
    }
};

export const updateAppVersionConfig = async (req: Request, res: Response) => {
    try {
        const { versionCode, versionName, downloadUrl, fileSizeMb, releaseNotes, isForceUpdate } = req.body;
        
        const config = await prisma.appVersionConfig.upsert({
            where: { id: 'latest' },
            update: { versionCode, versionName, downloadUrl, fileSizeMb, releaseNotes, isForceUpdate },
            create: { id: 'latest', versionCode, versionName, downloadUrl, fileSizeMb, releaseNotes, isForceUpdate }
        });

        res.json({
            success: true,
            message: 'Konfigurasi versi aplikasi berhasil diperbarui',
            config
        });
    } catch (error) {
        res.status(500).json({ message: 'Gagal memperbarui konfigurasi aplikasi' });
    }
};

export const checkVersionV1 = async (req: Request, res: Response) => {
    try {
        const platform = (req.query.platform as string) || 'android';
        const currentVersion = (req.query.current_version as string) || '1.0.0';

        let config = await prisma.appVersionConfig.findUnique({ where: { id: 'latest' } });
        const latestVersion = config?.versionName || '2.8.89';
        const minSupportedVersion = '2.8.80';

        const protocol = req.headers['x-forwarded-proto'] || req.protocol || 'https';
        const host = req.get('host') || 'cbt.smpn1boyolangu.my.id';
        const rawUrl = config?.downloadUrl || '/uploads/smartcbt-latest.apk';
        const fullDownloadUrl = rawUrl.startsWith('http')
            ? rawUrl
            : `${protocol}://${host}${rawUrl.startsWith('/') ? '' : '/'}${rawUrl}`;

        res.json({
            status: "success",
            data: {
                latest_version: latestVersion,
                min_supported_version: minSupportedVersion,
                download_url: fullDownloadUrl,
                changelog: config?.releaseNotes || "Pembaruan stabilitas sistem presensi, verifikasi Satpam Gatepass, dan perbaikan koneksi.",
                is_force_update: config?.isForceUpdate ?? true
            }
        });
    } catch (error) {
        console.error('Error fetching version v1:', error);
        res.status(500).json({ status: "error", message: 'Gagal mengecek pembaruan aplikasi' });
    }
};