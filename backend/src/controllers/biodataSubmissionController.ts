import { Request, Response, NextFunction } from 'express';
import { PrismaClient } from '@prisma/client';
import fs from 'fs';
import path from 'path';

const prisma = new PrismaClient();

// Ensure upload folders exist
const biodataUploadDir = path.join(__dirname, '../../uploads/biodata');
const kkDir = path.join(biodataUploadDir, 'kk');
const aktaDir = path.join(biodataUploadDir, 'akta');
const bantuanDir = path.join(biodataUploadDir, 'bantuan');

if (!fs.existsSync(biodataUploadDir)) fs.mkdirSync(biodataUploadDir, { recursive: true });
if (!fs.existsSync(kkDir)) fs.mkdirSync(kkDir, { recursive: true });
if (!fs.existsSync(aktaDir)) fs.mkdirSync(aktaDir, { recursive: true });
if (!fs.existsSync(bantuanDir)) fs.mkdirSync(bantuanDir, { recursive: true });

// ========================================================
// 1. SYSTEM SETTINGS & FEATURE TOGGLE HELPERS
// ========================================================

export const getBiodataToggleConfig = async () => {
    try {
        const activeSetting = await (prisma as any).systemSetting.findUnique({
            where: { key: 'student_biodata_submission_active' }
        });
        const deadlineSetting = await (prisma as any).systemSetting.findUnique({
            where: { key: 'biodata_submission_deadline' }
        });

        const isActive = activeSetting ? (activeSetting.value === 'true' || activeSetting.value === true) : true;
        const deadline = deadlineSetting?.value || null;

        const now = new Date();
        let isOpenNow = isActive;
        if (isActive && deadline) {
            const dDate = new Date(deadline);
            if (!isNaN(dDate.getTime()) && now > dDate) {
                isOpenNow = false;
            }
        }

        return {
            isActive,
            deadline,
            isOpenNow
        };
    } catch (e: any) {
        return { isActive: true, deadline: null, isOpenNow: true };
    }
};

/**
 * Middleware Backend: Memblokir request pengiriman form (HTTP 403 Forbidden) jika toggle nonaktif
 */
export const ensureBiodataSubmissionActive = async (req: Request, res: Response, next: NextFunction) => {
    try {
        const config = await getBiodataToggleConfig();
        if (!config.isOpenNow) {
            return res.status(403).json({
                success: false,
                isLocked: true,
                message: 'Periode pengisian biodata sedang ditutup oleh pihak sekolah.'
            });
        }
        next();
    } catch (e: any) {
        next();
    }
};

/**
 * PATCH /api/v1/admin/modules/biodata/toggle
 * Admin/Operator mengubah status toggle dan batas waktu pengisian biodata
 */
export const toggleBiodataModule = async (req: Request, res: Response) => {
    try {
        const { isActive, deadline } = req.body;

        await (prisma as any).systemSetting.upsert({
            where: { key: 'student_biodata_submission_active' },
            update: { value: String(isActive === true || isActive === 'true'), type: 'boolean' },
            create: { key: 'student_biodata_submission_active', value: String(isActive === true || isActive === 'true'), type: 'boolean' }
        });

        if (deadline !== undefined) {
            await (prisma as any).systemSetting.upsert({
                where: { key: 'biodata_submission_deadline' },
                update: { value: deadline ? String(deadline) : '', type: 'timestamp' },
                create: { key: 'biodata_submission_deadline', value: deadline ? String(deadline) : '', type: 'timestamp' }
            });
        }

        const config = await getBiodataToggleConfig();
        return res.json({
            success: true,
            message: `Modul pengisian biodata berhasil diubah: ${config.isOpenNow ? 'AKTIF / DIBUKA' : 'NONAKTIF / DITUTUP'}.`,
            data: config
        });
    } catch (error: any) {
        console.error('Error toggleBiodataModule:', error);
        return res.status(500).json({ success: false, message: error.message || 'Gagal mengubah pengaturan toggle biodata' });
    }
};

// ========================================================
// 2. SUBMIT PENGISIAN BIODATA OLEH ORANG TUA / SISWA (STAGING)
// ========================================================

/**
 * POST /api/v1/mobile/biodata/submit
 * Orang tua mengisi 5-Step Wizard & mengunggah berkas KK & Akta
 * Staging Table: student_profile_submissions (Status: 'PENDING')
 */
export const submitStudentBiodata = async (req: Request, res: Response) => {
    try {
        const currentUser = (req as any).user;
        if (!currentUser) {
            return res.status(401).json({ success: false, message: 'Autentikasi diperlukan.' });
        }

        if (currentUser.role === 'STUDENT') {
            return res.status(403).json({
                success: false,
                message: 'Pengisian formulir biodata buku induk hanya dapat diajukan melalui Akun Orang Tua.'
            });
        }

        // Tentukan studentId target
        let studentId = '';
        let parentId: string | null = null;

        if (currentUser.role === 'PARENT') {
            parentId = currentUser.id;
            // Ambil NISN dari parent username (P<NISN>) atau nisn field
            const cleanNisn = (currentUser.nisn || currentUser.username.replace(/^[Pp]/, '')).trim();
            const student = await prisma.user.findFirst({
                where: {
                    role: 'STUDENT',
                    OR: [
                        { nisn: cleanNisn },
                        { username: cleanNisn }
                    ]
                }
            });
            if (student) {
                studentId = student.id;
            } else if (req.body.studentId) {
                studentId = req.body.studentId;
            }
        } else if (currentUser.role === 'ADMIN' || currentUser.role === 'OPERATOR') {
            studentId = req.body.studentId || '';
        }

        if (!studentId) {
            return res.status(400).json({ success: false, message: 'Data siswa tujuan tidak ditemukan.' });
        }

        // Cek submission yang sudah ada
        const existing = await (prisma as any).studentProfileSubmission.findFirst({
            where: { studentId },
            orderBy: { submittedAt: 'desc' }
        });

        if (existing && existing.status === 'APPROVED') {
            return res.status(400).json({
                success: false,
                isLocked: true,
                message: 'Biodata telah disetujui oleh Petugas TU dan berstatus Terkunci (Read-Only). Hubungi pihak sekolah jika ingin perubahan.'
            });
        }

        // Ambil file upload multer (KK, Akta Kelahiran, dan Kartu Bantuan Sosial)
        const files = req.files as { [fieldname: string]: Express.Multer.File[] } | undefined;
        let kkFileUrl = existing?.kkFileUrl || null;
        let aktaFileUrl = existing?.aktaFileUrl || null;
        let bantuanFileUrl: string | null = null;
        try {
            if (existing?.payloadJson) {
                const prev = JSON.parse(existing.payloadJson);
                bantuanFileUrl = prev.bantuanFileUrl || null;
            }
        } catch (e) {}

        if (files?.kk && files.kk.length > 0) {
            kkFileUrl = `/uploads/biodata/kk/${files.kk[0].filename}`;
        }
        if (files?.akta && files.akta.length > 0) {
            aktaFileUrl = `/uploads/biodata/akta/${files.akta[0].filename}`;
        }
        if (files?.bantuan && files.bantuan.length > 0) {
            bantuanFileUrl = `/uploads/biodata/bantuan/${files.bantuan[0].filename}`;
        }

        const {
            step1Data,
            step2Data,
            step3Data,
            step4Data,
            step5Data
        } = req.body;

        const s4Obj = typeof step4Data === 'string' ? JSON.parse(step4Data || '{}') : (step4Data || {});
        if (bantuanFileUrl) {
            s4Obj.bantuanFileUrl = bantuanFileUrl;
        }

        const combinedPayload = {
            step1: typeof step1Data === 'string' ? JSON.parse(step1Data || '{}') : (step1Data || {}),
            step2: typeof step2Data === 'string' ? JSON.parse(step2Data || '{}') : (step2Data || {}),
            step3: typeof step3Data === 'string' ? JSON.parse(step3Data || '{}') : (step3Data || {}),
            step4: s4Obj,
            step5: typeof step5Data === 'string' ? JSON.parse(step5Data || '{}') : (step5Data || {}),
            kkFileUrl,
            aktaFileUrl,
            bantuanFileUrl
        };

        let resultSubmission;
        if (existing) {
            // Update data antrean staging yang sedang pending / perbaikan setelah ditolak
            resultSubmission = await (prisma as any).studentProfileSubmission.update({
                where: { id: existing.id },
                data: {
                    parentId,
                    step1Data: typeof step1Data === 'string' ? step1Data : JSON.stringify(step1Data || {}),
                    step2Data: typeof step2Data === 'string' ? step2Data : JSON.stringify(step2Data || {}),
                    step3Data: typeof step3Data === 'string' ? step3Data : JSON.stringify(step3Data || {}),
                    step4Data: JSON.stringify(s4Obj),
                    step5Data: typeof step5Data === 'string' ? step5Data : JSON.stringify(step5Data || {}),
                    payloadJson: JSON.stringify(combinedPayload),
                    kkFileUrl,
                    aktaFileUrl,
                    status: 'PENDING',
                    rejectionReason: null, // Reset alasan penolakan
                    submittedAt: new Date()
                }
            });
        } else {
            // Buat record staging baru
            resultSubmission = await (prisma as any).studentProfileSubmission.create({
                data: {
                    studentId,
                    parentId,
                    step1Data: typeof step1Data === 'string' ? step1Data : JSON.stringify(step1Data || {}),
                    step2Data: typeof step2Data === 'string' ? step2Data : JSON.stringify(step2Data || {}),
                    step3Data: typeof step3Data === 'string' ? step3Data : JSON.stringify(step3Data || {}),
                    step4Data: JSON.stringify(s4Obj),
                    step5Data: typeof step5Data === 'string' ? step5Data : JSON.stringify(step5Data || {}),
                    payloadJson: JSON.stringify(combinedPayload),
                    kkFileUrl,
                    aktaFileUrl,
                    status: 'PENDING',
                    submittedAt: new Date()
                }
            });
        }

        return res.status(200).json({
            success: true,
            message: '🎉 Formulir biodata siswa berhasil dikirim ke antrean verifikasi Staf TU.',
            submission: resultSubmission
        });
    } catch (error: any) {
        console.error('Error submitStudentBiodata:', error);
        return res.status(500).json({ success: false, message: error.message || 'Gagal mengirimkan formulir biodata' });
    }
};

/**
 * GET /api/v1/mobile/biodata/my-submission
 * Ambil data submission aktif orang tua / siswa untuk mengisi atau melihat status
 */
export const getMyBiodataSubmission = async (req: Request, res: Response) => {
    try {
        const currentUser = (req as any).user;
        if (!currentUser) {
            return res.status(401).json({ success: false, message: 'Autentikasi diperlukan.' });
        }

        let studentId = '';
        if (currentUser.role === 'PARENT') {
            const cleanNisn = (currentUser.nisn || currentUser.username.replace(/^[Pp]/, '')).trim();
            const student = await prisma.user.findFirst({
                where: {
                    role: 'STUDENT',
                    OR: [
                        { nisn: cleanNisn },
                        { username: cleanNisn }
                    ]
                }
            });
            if (student) studentId = student.id;
        } else if (currentUser.role === 'STUDENT') {
            studentId = currentUser.id;
        }

        const config = await getBiodataToggleConfig();

        if (!studentId) {
            return res.json({
                success: true,
                isToggleOpen: config.isOpenNow,
                deadline: config.deadline,
                submission: null,
                student: null
            });
        }

        const student = await prisma.user.findUnique({
            where: { id: studentId },
            select: {
                id: true,
                name: true,
                nisn: true,
                nik: true,
                className: true,
                gender: true,
                religion: true,
                pob: true,
                dob: true,
                address: true,
                fatherName: true,
                motherName: true,
                parentPhone: true,
                profilePicUrl: true
            }
        });

        const submission = await (prisma as any).studentProfileSubmission.findFirst({
            where: { studentId },
            orderBy: { submittedAt: 'desc' }
        });

        const isLocked = submission?.status === 'APPROVED';

        return res.json({
            success: true,
            isToggleOpen: config.isOpenNow,
            deadline: config.deadline,
            isLocked,
            status: submission?.status || 'NOT_SUBMITTED',
            rejectionReason: submission?.rejectionReason || null,
            submission,
            student
        });
    } catch (error: any) {
        console.error('Error getMyBiodataSubmission:', error);
        return res.status(500).json({ success: false, message: error.message || 'Gagal memuat submission biodata' });
    }
};

// ========================================================
// 3. VERIFIKASI & APPROVAL OLEH STAF TU (PORTAL WEB)
// ========================================================

/**
 * GET /api/v1/admin/biodata/submissions
 * Daftar antrean validasi biodata untuk Portal Web Staf TU (Filter PENDING, APPROVED, REJECTED)
 */
export const getBiodataSubmissionsList = async (req: Request, res: Response) => {
    try {
        const { status, className, search } = req.query;
        const whereClause: any = {};

        if (status && status !== 'ALL') {
            whereClause.status = String(status);
        }

        const submissions = await (prisma as any).studentProfileSubmission.findMany({
            where: whereClause,
            include: {
                student: {
                    select: {
                        id: true,
                        name: true,
                        className: true,
                        nisn: true,
                        nik: true,
                        profilePicUrl: true
                    }
                }
            },
            orderBy: { submittedAt: 'desc' },
            take: 150
        });

        let filtered = submissions;
        if (className && className !== 'ALL') {
            filtered = filtered.filter((s: any) => s.student?.className === className);
        }
        if (search && typeof search === 'string' && search.trim()) {
            const q = search.trim().toLowerCase();
            filtered = filtered.filter((s: any) =>
                s.student?.name?.toLowerCase().includes(q) ||
                s.student?.nisn?.includes(q)
            );
        }

        return res.json({
            success: true,
            count: filtered.length,
            data: filtered
        });
    } catch (error: any) {
        console.error('Error getBiodataSubmissionsList:', error);
        return res.status(500).json({ success: false, message: error.message || 'Gagal mengambil daftar antrean biodata' });
    }
};

/**
 * POST /api/v1/admin/biodata/:id/action
 * Action Staf TU: 'APPROVE' atau 'REJECT'
 * Jika Approved: Salin otomatis ke tabel master `User` (students) & kunci form di APK menjadi Read-Only
 * Jika Rejected: Set status REJECTED, simpan `rejection_reason`, buka kembali form di APK
 */
export const actionBiodataSubmission = async (req: Request, res: Response) => {
    try {
        const reviewerName = (req as any).user?.name || 'Petugas TU';
        const { id } = req.params;
        const { action, rejectionReason } = req.body;

        if (!['APPROVE', 'REJECT'].includes(action)) {
            return res.status(400).json({ success: false, message: 'Aksi tidak valid. Pilih APPROVE atau REJECT.' });
        }

        const submission = await (prisma as any).studentProfileSubmission.findUnique({
            where: { id },
            include: { student: true }
        });

        if (!submission) {
            return res.status(404).json({ success: false, message: 'Data pengajuan biodata tidak ditemukan.' });
        }

        if (action === 'APPROVE') {
            // Parsing Step Data
            let s1: any = {};
            let s2: any = {};
            let s3: any = {};
            try {
                if (submission.step1Data) s1 = JSON.parse(submission.step1Data);
                if (submission.step2Data) s2 = JSON.parse(submission.step2Data);
                if (submission.step3Data) s3 = JSON.parse(submission.step3Data);
            } catch (e) {}

            const fullAddress = s2.alamat
                ? `${s2.alamat}${s2.rt ? ` RT ${s2.rt}` : ''}${s2.rw ? ` RW ${s2.rw}` : ''}${s2.desa ? `, Desa ${s2.desa}` : ''}${s2.kecamatan ? `, Kec. ${s2.kecamatan}` : ''}${s2.kabupaten ? `, ${s2.kabupaten}` : ''}`
                : submission.student.address;

            // Salin otomatis ke tabel master `User` (Student)
            await prisma.user.update({
                where: { id: submission.studentId },
                data: {
                    name: s1.nama || submission.student.name,
                    nik: s1.nik || (submission.student as any).nik || null,
                    pob: s1.tempatLahir || submission.student.pob,
                    dob: s1.tanggalLahir || submission.student.dob,
                    gender: s1.jenisKelamin || submission.student.gender,
                    religion: s1.agama || submission.student.religion,
                    address: fullAddress,
                    fatherName: s3.namaAyah || submission.student.fatherName,
                    motherName: s3.namaIbu || submission.student.motherName,
                    parentPhone: s3.noHpOrtu || submission.student.parentPhone
                }
            });

            // Update submission status menjadi APPROVED
            const updated = await (prisma as any).studentProfileSubmission.update({
                where: { id },
                data: {
                    status: 'APPROVED',
                    reviewedBy: reviewerName,
                    reviewedAt: new Date(),
                    rejectionReason: null
                }
            });

            // Notifikasi ke Orang Tua
            try {
                await (prisma as any).notificationMessage.create({
                    data: {
                        recipientRole: 'PARENT',
                        studentId: submission.studentId,
                        studentName: submission.student.name,
                        className: submission.student.className || '-',
                        title: '✅ Verifikasi Biodata Siswa Selesai!',
                        message: `Selamat, berkas dan data formulir biodata ananda ${submission.student.name} telah diverifikasi dan disetujui oleh Petugas TU (${reviewerName}). Data resmi telah diperbarui di SIAKAD.`,
                        category: 'BIODATA_VERIFIED'
                    }
                });
            } catch (e) {}

            return res.json({
                success: true,
                message: `✅ Biodata ananda ${submission.student.name} berhasil disetujui & disinkronkan ke tabel utama siswa. Form di APK terkunci (Read-Only).`,
                data: updated
            });

        } else {
            // REJECT
            if (!rejectionReason || rejectionReason.trim().length < 5) {
                return res.status(400).json({
                    success: false,
                    message: 'Alasan penolakan / catatan revisi wajib diisi minimal 5 karakter.'
                });
            }

            const updated = await (prisma as any).studentProfileSubmission.update({
                where: { id },
                data: {
                    status: 'REJECTED',
                    rejectionReason: rejectionReason.trim(),
                    reviewedBy: reviewerName,
                    reviewedAt: new Date()
                }
            });

            // Notifikasi revisi ke Orang Tua
            try {
                await (prisma as any).notificationMessage.create({
                    data: {
                        recipientRole: 'PARENT',
                        studentId: submission.studentId,
                        studentName: submission.student.name,
                        className: submission.student.className || '-',
                        title: '⚠️ Revisi Pengajuan Biodata Siswa Diperlukan',
                        message: `Pengajuan biodata ananda ${submission.student.name} memerlukan perbaikan dari Petugas TU (${reviewerName}). Catatan: "${rejectionReason.trim()}". Silakan buka kembali form di aplikasi untuk perbaikan.`,
                        category: 'BIODATA_REVISED'
                    }
                });
            } catch (e) {}

            return res.json({
                success: true,
                message: `Pengajuan biodata ditolak. Catatan revisi telah dikirimkan ke akun orang tua siswa. Form di APK terbuka kembali untuk perbaikan.`,
                data: updated
            });
        }
    } catch (error: any) {
        console.error('Error actionBiodataSubmission:', error);
        return res.status(500).json({ success: false, message: error.message || 'Gagal memproses aksi biodata' });
    }
};
