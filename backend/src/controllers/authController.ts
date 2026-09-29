import { Request, Response } from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { PrismaClient } from '@prisma/client';
import { logAudit } from '../services/auditLogger';
import { revokedUserIds } from './adminUserController';
import { AuthRequest } from '../middlewares/authMiddleware';

const prisma = new PrismaClient();
const SECRET_KEY = process.env.JWT_SECRET || 'cbt_prod_secret_f9a83b27c64e10d592a8174fec01849a62bc38102d7e481b9201948271039841';
const REFRESH_SECRET_KEY = process.env.JWT_REFRESH_SECRET || 'cbt_refresh_prod_secret_81726354a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2';

export const login = async (req: Request, res: Response) => {
    const { username, password, deviceId, role } = req.body;

    try {
        const cleanUsername = String(username || '').trim();
        const uLower = cleanUsername.toLowerCase();
        const targetRole = String(role || '').toUpperCase().trim();

        let user = null;

        // 1. Jika mode PARENT dipilih atau username diawali huruf P
        if (targetRole === 'PARENT' || targetRole === 'ORANGTUA' || targetRole === 'ORANG_TUA' || uLower.startsWith('p')) {
            const rawNisn = cleanUsername.replace(/^[Pp]/, '').trim();
            user = await prisma.user.findFirst({
                where: {
                    role: 'PARENT',
                    OR: [
                        { username: cleanUsername },
                        { username: 'P' + rawNisn },
                        { nisn: rawNisn },
                        { nisn: cleanUsername }
                    ]
                }
            });
            // Jika akun orang tua belum ada di DB tapi siswa ada, sinkronkan otomatis
            if (!user && rawNisn) {
                const student = await prisma.user.findFirst({
                    where: {
                        role: 'STUDENT',
                        OR: [
                            { nisn: rawNisn },
                            { username: rawNisn }
                        ]
                    }
                });
                if (student) {
                    const { syncParentAccountForStudent } = require('../utils/parentAccountHelper');
                    user = await syncParentAccountForStudent(student);
                }
            }
        }

        // 2. Jika bukan PARENT atau belum ditemukan, cari berdasarkan username atau nisn
        if (!user) {
            user = await prisma.user.findUnique({ where: { username: cleanUsername } });
        }
        if (!user) {
            user = await prisma.user.findFirst({
                where: {
                    OR: [
                        { username: { equals: cleanUsername } },
                        { nisn: cleanUsername }
                    ]
                }
            });
        }
        if (!user && (uLower === 'admin' || uLower === 'administrator')) {
            user = await prisma.user.findFirst({
                where: {
                    OR: [
                        { role: 'ADMIN' },
                        { role: 'OPERATOR' },
                        { username: 'admin' }
                    ]
                }
            });
        }

        if (!user) {
            return res.status(404).json({ message: 'Pengguna tidak ditemukan.' });
        }

        const userRole = String(user.role || '').toUpperCase().trim();
        const isAdminUser = userRole === 'ADMIN' || userRole === 'ADMINISTRATOR' || userRole === 'SUPERADMIN' || uLower === 'admin' || uLower === 'administrator';

        // 1. Cek kecocokan password dengan hash bcrypt terlebih dahulu (prioritas utama jika password baru saja diubah)
        let isValidPassword = false;
        try {
            isValidPassword = await bcrypt.compare(password, user.password);
        } catch (e) {
            isValidPassword = false;
        }

        // Cek juga plain text match (jika database menyimpan password tanpa hash)
        if (!isValidPassword && user.password && user.password === password) {
            isValidPassword = true;
        }

        // 2. Fallback default password untuk Siswa & Orang Tua (Orang Tua login username P<NISN> password <NISN>)
        if (!isValidPassword && (userRole === 'STUDENT' || userRole === 'PARENT')) {
            const studentNisn = (user.nisn || user.username.replace(/^[Pp]/, '')).trim();
            if (userRole === 'PARENT' && studentNisn && (password === studentNisn || password === 'P' + studentNisn || password === user.username)) {
                isValidPassword = true;
            } else {
                const isDbPasswordDefault = !user.password ||
                    user.password === 'password123' ||
                    user.password === studentNisn ||
                    user.password === user.username ||
                    (await bcrypt.compare('password123', user.password).catch(() => false)) ||
                    (studentNisn ? await bcrypt.compare(studentNisn, user.password).catch(() => false) : false) ||
                    (user.username ? await bcrypt.compare(user.username, user.password).catch(() => false) : false);

                if (isDbPasswordDefault) {
                    if (studentNisn && (password === studentNisn || password === user.username)) {
                        isValidPassword = true;
                    } else if (password === 'password123') {
                        isValidPassword = true;
                    }
                }
            }
        }

        // 3. Admin fallback password: hanya aktif jika password admin di DB masih default
        if (!isValidPassword && isAdminUser && (password === 'admin' || password === 'admin123' || password === 'password123' || password === 'Admin123' || password === 'admincbt' || password === 'admincbt123')) {
            const isDbAdminDefault = !user.password ||
                user.password === 'password123' ||
                user.password === 'admin123' ||
                (await bcrypt.compare('password123', user.password).catch(() => false)) ||
                (await bcrypt.compare('admin123', user.password).catch(() => false));
            if (isDbAdminDefault) {
                isValidPassword = true;
            }
        }

        // 4. BK fallback password
        if (!isValidPassword && (uLower === 'bk' || userRole === 'COUNSELOR') && (password === 'bk' || password === 'bk123' || password === 'password123')) {
            isValidPassword = true;
        }

        if (!isValidPassword) {
            return res.status(401).json({ message: 'Password salah.' });
        }

        if (!user.isActive) {
            return res.status(403).json({ message: 'Akun Anda dinonaktifkan.' });
        }

        // Deteksi apakah guru bertugas sebagai Guru BK / Konselor (via flag isBk, role COUNSELOR, atau tugasTambahan BK)
        let isCounselor = Boolean((user as any).isBk) || userRole === 'COUNSELOR' || userRole === 'BK' || userRole === 'GURU_BK' || uLower === 'bk';
        if (!isCounselor && userRole === 'TEACHER') {
            if ((user.teachingSubject && user.teachingSubject.toUpperCase().includes('BK')) ||
                ((user as any).tugasTambahan && String((user as any).tugasTambahan).toUpperCase().includes('BK'))) {
                isCounselor = true;
            } else {
                const assignedClass = await prisma.class.findFirst({
                    where: { OR: [{ counselorId: user.id }, { counselorName: user.name }] }
                });
                if (assignedClass) isCounselor = true;
            }
        }

        // Fitur Anti-Joki & Anti-Titip Absen: Device Binding Check KHUSUS Role SISWA (Dikecualikan untuk user dummy testing)
        const incomingDeviceId = (deviceId || req.headers['x-device-id']) as string;
        const isDummyTester = user.username === 'siswa1' || user.username === 'parent';
        if (user.role === 'STUDENT' && !isDummyTester) {
            if (incomingDeviceId) {
                if (user.deviceBindingId && user.deviceBindingId !== incomingDeviceId) {
                    return res.status(403).json({ 
                        success: false,
                        message: '⛔ Akses Ditolak: Username sudah terdaftar di perangkat lain. Silakan minta reset perangkat ke Admin atau Operator sekolah.' 
                    });
                } else if (!user.deviceBindingId) {
                    // Binding pertama kali ke perangkat HP siswa
                    await prisma.user.update({
                        where: { id: user.id },
                        data: { deviceBindingId: incomingDeviceId }
                    });
                }
            } else if (user.deviceBindingId) {
                return res.status(403).json({
                    success: false,
                    message: '⛔ Akses Ditolak: Username sudah terdaftar di perangkat lain. Silakan minta reset perangkat ke Admin atau Operator sekolah.'
                });
            }
        }

        // Catat Last Login & update deviceSession userId jika incomingDeviceId tersedia
        if (incomingDeviceId) {
            await prisma.deviceSession.updateMany({
                where: { deviceAndroidId: incomingDeviceId },
                data: { userId: user.id }
            }).catch(() => {});
        }

        await prisma.user.update({
            where: { id: user.id },
            data: { lastLogin: new Date() }
        });

        // Hapus status revoked jika user login kembali secara sah
        revokedUserIds.delete(user.id);
        if (user.username) revokedUserIds.delete(user.username);
        if (user.nisn) revokedUserIds.delete(user.nisn);
        if (incomingDeviceId) revokedUserIds.delete(incomingDeviceId);

        // 1. Generate Access Token (7 Hari) untuk stabilitas sesi portal & APK
        const effectiveRole = isAdminUser ? 'ADMIN' : (isCounselor && userRole === 'TEACHER' ? 'COUNSELOR' : userRole);
        const accessToken = jwt.sign(
            { 
                id: user.id, 
                username: user.username, 
                role: effectiveRole, 
                isCounselor,
                name: user.name,
                teachingSubject: user.teachingSubject,
                teachingClasses: user.teachingClasses,
                className: user.className
            },
            SECRET_KEY,
            { expiresIn: '7d' }
        );

        // 2. Generate Refresh Token (7 Hari)
        const refreshTokenStr = jwt.sign(
            { id: user.id, username: user.username, nonce: Math.random().toString(36).substring(2) + Date.now() },
            REFRESH_SECRET_KEY,
            { expiresIn: '7d' }
        );

        // Simpan Refresh Token ke Database
        const expiresAt = new Date();
        expiresAt.setDate(expiresAt.getDate() + 7);
        try {
            await (prisma as any).refreshToken.create({
                data: {
                    token: refreshTokenStr,
                    userId: user.id,
                    expiresAt
                }
            });
        } catch (tokErr) {
            console.warn('Refresh token create warning, using fallback');
        }

        await logAudit(req, 'USER_LOGIN', `User: ${user.username}`, { role: effectiveRole, name: user.name });

        res.cookie('token', accessToken, { path: '/', maxAge: 7 * 24 * 60 * 60 * 1000, httpOnly: false });
        res.cookie('admin_token', accessToken, { path: '/', maxAge: 7 * 24 * 60 * 60 * 1000, httpOnly: false });

        // Hitung peran tambahan (tugas tambahan) jika user adalah guru atau staf
        const availableRoles: string[] = [];
        const uRoleUpper = (user.role || '').toUpperCase();
        const tTambahan = ((user as any).tugasTambahan || '').toUpperCase();
        
        if (uRoleUpper === 'TEACHER') {
            availableRoles.push('GURU_MAPEL');
            
            // Wali Kelas jika punya rombel atau ditugaskan di tugasTambahan
            if (user.className || tTambahan.includes('WALI') || tTambahan.includes('WALI_KELAS')) {
                availableRoles.push('WALI_KELAS');
            }
            // Operator jika diberikan tugas tambahan operator oleh admin
            if (tTambahan.includes('OPERATOR') || isAdminUser) {
                availableRoles.push('OPERATOR');
            }
            // Piket jika ada jadwal piket atau tugasTambahan piket
            const todayDay = new Date().getDay();
            const hasPiketToday = await prisma.piketSchedule.findFirst({
                where: {
                    dayOfWeek: todayDay,
                    isActive: true,
                    OR: [
                        { teacherId: user.id },
                        { teacher: { name: { contains: user.name } } }
                    ]
                }
            });
            if (hasPiketToday || tTambahan.includes('PIKET')) {
                availableRoles.push('PIKET');
            }
            // PAI jika mapel agama Islam atau tugasTambahan PAI
            const subj = (user.teachingSubject || '').toUpperCase();
            if (subj.includes('PAI') || subj.includes('AGAMA') || subj.includes('ISLAM') || tTambahan.includes('PAI')) {
                availableRoles.push('PAI');
            }
            // Guru BK jika tugas BK
            if (isCounselor || tTambahan.includes('BK')) {
                availableRoles.push('COUNSELOR');
                availableRoles.push('BK');
            }
        } else if (uRoleUpper === 'OPERATOR' || isAdminUser) {
            availableRoles.push('OPERATOR');
            availableRoles.push('GURU_MAPEL');
            availableRoles.push('PIKET');
            availableRoles.push('COUNSELOR');
        }

        res.json({
            message: 'Login Berhasil',
            token: accessToken,
            refreshToken: refreshTokenStr,
            user: {
                id: user.id,
                name: user.name,
                username: user.username,
                role: user.role,
                isBk: isCounselor,
                isSecurity: Boolean((user as any).isSecurity || user.role === 'SECURITY'),
                classId: user.classId,
                className: user.className,
                nisn: user.nisn,
                teachingSubject: user.teachingSubject,
                teachingClasses: user.teachingClasses,
                tugasTambahan: (user as any).tugasTambahan || (isCounselor ? 'BK' : null),
                availableRoles: availableRoles.length > 0 ? availableRoles : [user.role],
                motherName: user.motherName || null,
                fatherName: user.fatherName || null,
                profilePicUrl: user.profilePicUrl || null,
                isClassOfficer: Boolean((user as any).classRole && ['Ketua Kelas', 'Wakil Ketua Kelas', 'Sekretaris', 'Sekretaris 1', 'Sekretaris 2', 'Bendahara', 'Bendahara 1', 'Bendahara 2'].includes((user as any).classRole)),
                classRole: (user as any).classRole || null
            }
        });
    } catch (error) {
        console.error(error);
        res.status(500).json({ message: 'Terjadi kesalahan pada server.' });
    }
};

// 3. Endpoint Refresh Token Otomatis (Saat Access Token 15m Habis)
export const refreshTokenHandler = async (req: Request, res: Response) => {
    const { refreshToken } = req.body;
    if (!refreshToken) {
        return res.status(400).json({ success: false, message: 'Refresh token wajib disertakan' });
    }

    try {
        const decoded: any = jwt.verify(refreshToken, REFRESH_SECRET_KEY);
        const storedToken = await prisma.refreshToken.findUnique({
            where: { token: refreshToken }
        });

        if (!storedToken || storedToken.expiresAt < new Date()) {
            return res.status(401).json({ success: false, message: 'Refresh token tidak valid atau telah kedaluwarsa' });
        }

        const user = await prisma.user.findUnique({ where: { id: decoded.id } });
        if (!user || !user.isActive) {
            return res.status(403).json({ success: false, message: 'Pengguna tidak aktif' });
        }

        // Buat access token baru 15 menit
        const newAccessToken = jwt.sign(
            { id: user.id, username: user.username, role: user.role, name: user.name },
            SECRET_KEY,
            { expiresIn: '15m' }
        );

        res.json({
            success: true,
            token: newAccessToken,
            message: 'Access Token berhasil diperbarui'
        });
    } catch (err) {
        res.status(401).json({ success: false, message: 'Sesi kedaluwarsa, silakan login kembali' });
    }
};

// 4. Audit Log Viewer untuk Super Admin
export const getAuditLogs = async (req: Request, res: Response) => {
    try {
        const logs = await prisma.auditLog.findMany({
            take: 50,
            orderBy: { createdAt: 'desc' }
        });
        res.json(logs);
    } catch (error) {
        res.status(500).json({ message: 'Gagal memuat rekam jejak audit' });
    }
};

export const resetDeviceBinding = async (req: Request, res: Response) => {
    const { studentId, nisn, username, query } = req.body;
    try {
        const searchTerm = studentId || nisn || username || query;
        if (!searchTerm) {
            return res.status(400).json({ success: false, message: 'Harap berikan ID, NISN, atau Username siswa.' });
        }

        const user = await prisma.user.findFirst({
            where: {
                OR: [
                    { id: searchTerm },
                    { nisn: searchTerm },
                    { username: searchTerm }
                ]
            }
        });

        if (!user) {
            return res.status(404).json({ success: false, message: `Siswa dengan NISN / Username '${searchTerm}' tidak ditemukan.` });
        }

        await prisma.user.update({
            where: { id: user.id },
            data: { deviceBindingId: null }
        });
        await logAudit(req, 'RESET_DEVICE_BINDING', `User: ${user.name} (${user.id})`);
        res.json({ success: true, message: `Device binding untuk ${user.name} (${user.className || 'Siswa'}) berhasil di-reset.` });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal mereset perangkat: ' + error.message });
    }
};

/**
 * Endpoint untuk mendapatkan access token baru berdasarkan sesi login aktif (cookie / header)
 */
export const getSessionToken = async (req: AuthRequest, res: Response) => {
    try {
        if (!req.user) {
            return res.status(401).json({ success: false, message: 'Belum login' });
        }
        const freshToken = jwt.sign(
            {
                id: req.user.id,
                username: req.user.username,
                role: req.user.role,
                name: req.user.name,
                isCounselor: req.user.isCounselor
            },
            SECRET_KEY,
            { expiresIn: '7d' }
        );
        res.cookie('token', freshToken, { path: '/', maxAge: 7 * 24 * 60 * 60 * 1000, httpOnly: false });
        res.cookie('admin_token', freshToken, { path: '/', maxAge: 7 * 24 * 60 * 60 * 1000, httpOnly: false });
        res.json({ success: true, token: freshToken });
    } catch (e: any) {
        res.status(500).json({ success: false, message: 'Gagal mendapatkan token sesi: ' + e.message });
    }
};