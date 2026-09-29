import { Request, Response } from 'express';
import prisma from '../utils/db';

// ==================== 1. GRUP MAPEL (ADMIN & KURIKULUM) ====================

// Mengambil daftar grup mapel
export const getGrupMapelList = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const groups = await (prisma as any).grupMapelSoal.findMany({
            include: {
                _count: { select: { bankSoalList: true } }
            },
            orderBy: { name: 'asc' }
        });

        // Filter grup yang diikuti guru jika role TEACHER
        let myGroups = groups;
        if (user && user.role === 'TEACHER') {
            myGroups = groups.filter((g: any) => {
                if (!g.membersJson) return false;
                try {
                    const members = JSON.parse(g.membersJson);
                    return members.some((m: any) => m.teacherId === user.id || m.teacherName === user.name);
                } catch (e) {
                    return false;
                }
            });
        }

        res.json({ success: true, allGroups: groups, myGroups });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat grup mapel: ' + error.message });
    }
};

// Admin / Kurikulum membuat grup mapel baru
export const createGrupMapel = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { name, subjectName, level, description, members } = req.body;

        if (!name || !subjectName || !level) {
            return res.status(400).json({ success: false, message: 'Nama grup, mata pelajaran, dan tingkat wajib diisi.' });
        }

        const group = await (prisma as any).grupMapelSoal.create({
            data: {
                name,
                subjectName,
                level,
                description: description || '',
                membersJson: members ? (typeof members === 'string' ? members : JSON.stringify(members)) : '[]',
                createdBy: user ? user.name : 'Admin'
            }
        });

        res.json({ success: true, message: 'Grup mapel berhasil dibuat!', group });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal membuat grup mapel: ' + error.message });
    }
};

// Admin / Kurikulum menambahkan atau memperbarui anggota guru grup mapel
export const updateGrupMembers = async (req: Request, res: Response) => {
    try {
        const { groupId } = req.params;
        const { members } = req.body; // array of { teacherId, teacherName, role }

        const group = await (prisma as any).grupMapelSoal.update({
            where: { id: groupId },
            data: {
                membersJson: typeof members === 'string' ? members : JSON.stringify(members)
            }
        });

        res.json({ success: true, message: 'Daftar anggota grup berhasil diperbarui!', group });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memperbarui anggota grup: ' + error.message });
    }
};

// ==================== 2. ADVANCED BANK SOAL DENGAN VISIBILITAS & POOL BERSAMA ====================

// Mengambil Bank Soal berdasarkan scope: MY (Milik Saya), GROUP (Grup Mapel), SCHOOL (Sekolah), VERIFIED (Resmi)
export const getAdvancedBankSoal = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { scope, subjectName, level, verificationStatus, groupId, search } = req.query;

        const whereClause: any = {};

        // Filter dasar
        if (subjectName) whereClause.subjectName = String(subjectName);
        if (level && level !== 'ALL') whereClause.level = String(level);
        if (verificationStatus && verificationStatus !== 'ALL') whereClause.verificationStatus = String(verificationStatus);

        if (search) {
            whereClause.OR = [
                { content: { contains: String(search) } },
                { subjectName: { contains: String(search) } }
            ];
        }

        const selectedScope = String(scope || 'MY').toUpperCase();

        if (selectedScope === 'MY') {
            // Hanya soal milik guru login
            if (user && user.id) {
                whereClause.teacherId = user.id;
            }
        } else if (selectedScope === 'GROUP') {
            // Soal yang dibagikan ke grup mapel
            whereClause.visibility = 'GROUP';
            if (groupId) {
                whereClause.groupId = String(groupId);
            }
        } else if (selectedScope === 'SCHOOL') {
            // Soal yang dibagikan ke seluruh sekolah
            whereClause.visibility = 'SCHOOL';
        } else if (selectedScope === 'VERIFIED') {
            // Hanya soal yang sudah terverifikasi (bebas dipakai resmi)
            whereClause.verificationStatus = 'VERIFIED';
        }

        const questions = await (prisma as any).bankSoal.findMany({
            where: whereClause,
            include: {
                group: { select: { id: true, name: true, subjectName: true, level: true } },
                _count: { select: { revisionProposals: true, revisionHistories: true } }
            },
            orderBy: [
                { likesCount: 'desc' },
                { createdAt: 'desc' }
            ]
        });

        res.json({ success: true, count: questions.length, questions });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal mengambil bank soal: ' + error.message });
    }
};

// Mengubah visibilitas soal (Privat -> Dibagikan ke Grup / Sekolah)
export const updateSoalVisibility = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { id } = req.params;
        const { visibility, groupId } = req.body; // 'PRIVATE' | 'GROUP' | 'SCHOOL'

        const soal = await (prisma as any).bankSoal.findUnique({ where: { id } });
        if (!soal) {
            return res.status(404).json({ success: false, message: 'Soal tidak ditemukan.' });
        }

        // Hanya pemilik atau admin yang bisa mengubah visibilitas
        if (user && user.role !== 'ADMIN' && soal.teacherId && soal.teacherId !== user.id) {
            return res.status(403).json({ success: false, message: 'Hanya pemilik asli yang berhak mengubah visibilitas soal.' });
        }

        if (visibility === 'GROUP' && !groupId) {
            return res.status(400).json({ success: false, message: 'Pilih grup mapel tujuan untuk membagikan soal.' });
        }

        const updated = await (prisma as any).bankSoal.update({
            where: { id },
            data: {
                visibility: visibility || 'PRIVATE',
                groupId: visibility === 'GROUP' ? groupId : null
            },
            include: { group: true }
        });

        res.json({
            success: true,
            message: visibility === 'GROUP' ? `Soal berhasil dibagikan ke grup ${updated.group?.name}!` : 'Visibilitas soal berhasil diperbarui.',
            soal: updated
        });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal mengubah visibilitas soal: ' + error.message });
    }
};

// Memberikan rating / like pada soal bersama
export const likeSoal = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const updated = await (prisma as any).bankSoal.update({
            where: { id },
            data: { likesCount: { increment: 1 } }
        });
        res.json({ success: true, likesCount: updated.likesCount });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal menyukai soal: ' + error.message });
    }
};

// Admin mentransfer kepemilikan soal jika guru pindah tugas / tidak aktif
export const transferSoalOwnership = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        if (user && user.role !== 'ADMIN') {
            return res.status(403).json({ success: false, message: 'Hanya Administrator yang dapat mentransfer kepemilikan soal.' });
        }

        const { id } = req.params;
        const { newTeacherId, newTeacherName } = req.body;

        const soal = await (prisma as any).bankSoal.findUnique({ where: { id } });
        if (!soal) return res.status(404).json({ success: false, message: 'Soal tidak ditemukan.' });

        const updated = await (prisma as any).bankSoal.update({
            where: { id },
            data: {
                originalTeacherId: soal.originalTeacherId || soal.teacherId,
                originalTeacherName: soal.originalTeacherName || 'Guru Sebelumnya',
                teacherId: newTeacherId
            }
        });

        res.json({ success: true, message: `Kepemilikan soal berhasil dialihkan ke ${newTeacherName || newTeacherId}!`, soal: updated });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal mentransfer kepemilikan: ' + error.message });
    }
};

// ==================== 3. QUALITY GATE: BANK SOAL TERVERIFIKASI VS DRAFT ====================

// Guru mengajukan soal untuk verifikasi resmi
export const submitSoalForVerification = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const soal = await (prisma as any).bankSoal.findUnique({ where: { id } });
        if (!soal) return res.status(404).json({ success: false, message: 'Soal tidak ditemukan.' });

        const updated = await (prisma as any).bankSoal.update({
            where: { id },
            data: {
                verificationStatus: 'SUBMITTED',
                rejectionNotes: null
            }
        });

        res.json({
            success: true,
            message: 'Soal berhasil diajukan untuk verifikasi! Masuk ke antrian review koordinator mapel & kurikulum.',
            soal: updated
        });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal mengajukan verifikasi: ' + error.message });
    }
};

// Reviewer / Koordinator Mapel / Kurikulum memverifikasi atau menolak soal
export const reviewSoalVerification = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { id } = req.params;
        const { action, notes, content, options, correctOption, explanation } = req.body; // action: 'APPROVE' | 'REJECT' | 'EDIT_AND_APPROVE'

        const soal = await (prisma as any).bankSoal.findUnique({ where: { id } });
        if (!soal) return res.status(404).json({ success: false, message: 'Soal tidak ditemukan.' });

        let newStatus = 'DRAFT';
        const updateData: any = {
            reviewedById: user?.id || 'Reviewer',
            reviewedByName: user?.name || 'Tim Reviewer Kurikulum'
        };

        if (action === 'APPROVE') {
            newStatus = 'VERIFIED';
            updateData.verificationStatus = newStatus;
            updateData.verifiedAt = new Date();
            updateData.rejectionNotes = null;
        } else if (action === 'REJECT') {
            newStatus = 'REJECTED';
            updateData.verificationStatus = newStatus;
            updateData.rejectionNotes = notes || 'Perlu perbaikan redaksi atau kunci jawaban.';
        } else if (action === 'EDIT_AND_APPROVE') {
            newStatus = 'VERIFIED';
            updateData.verificationStatus = newStatus;
            updateData.verifiedAt = new Date();
            updateData.rejectionNotes = null;
            if (content) updateData.content = content;
            if (options) updateData.options = typeof options === 'string' ? options : JSON.stringify(options);
            if (correctOption) updateData.correctOption = correctOption;
            if (explanation) updateData.explanation = explanation;

            // Catat ke riwayat revisi
            await (prisma as any).riwayatRevisiSoal.create({
                data: {
                    soalId: id,
                    changedById: user?.id || 'Reviewer',
                    changedByName: user?.name || 'Reviewer Kurikulum',
                    summary: 'Koreksi langsung dan verifikasi oleh reviewer: ' + (notes || 'Perbaikan redaksi/kunci'),
                    previousData: JSON.stringify({ content: soal.content, options: soal.options, correctOption: soal.correctOption })
                }
            });
        }

        const updated = await (prisma as any).bankSoal.update({
            where: { id },
            data: updateData
        });

        res.json({
            success: true,
            message: newStatus === 'VERIFIED' ? '✅ Soal berhasil disetujui & berstatus TERVERIFIKASI!' : '❌ Soal ditolak dengan catatan evaluasi.',
            soal: updated
        });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal mereview soal: ' + error.message });
    }
};

// Mengambil antrian soal yang menunggu verifikasi (khusus Reviewer & Kurikulum)
export const getVerificationQueue = async (req: Request, res: Response) => {
    try {
        const { subjectName } = req.query;
        const whereClause: any = {
            verificationStatus: 'SUBMITTED'
        };
        if (subjectName) whereClause.subjectName = String(subjectName);

        const queue = await (prisma as any).bankSoal.findMany({
            where: whereClause,
            include: {
                group: true
            },
            orderBy: { createdAt: 'asc' }
        });

        res.json({ success: true, count: queue.length, queue });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat antrian verifikasi: ' + error.message });
    }
};

// Statistik persentase bank soal terverifikasi per mapel untuk kurikulum
export const getVerificationStats = async (req: Request, res: Response) => {
    try {
        const allSoal = await (prisma as any).bankSoal.findMany({
            select: { subjectName: true, verificationStatus: true }
        });

        const statsMap = new Map<string, { total: number; verified: number; draft: number; submitted: number; rejected: number }>();

        allSoal.forEach((s: any) => {
            const sub = s.subjectName || 'Umum';
            if (!statsMap.has(sub)) {
                statsMap.set(sub, { total: 0, verified: 0, draft: 0, submitted: 0, rejected: 0 });
            }
            const cur = statsMap.get(sub)!;
            cur.total++;
            if (s.verificationStatus === 'VERIFIED') cur.verified++;
            else if (s.verificationStatus === 'SUBMITTED') cur.submitted++;
            else if (s.verificationStatus === 'REJECTED') cur.rejected++;
            else cur.draft++;
        });

        const stats = Array.from(statsMap.entries()).map(([subject, data]) => ({
            subject,
            ...data,
            verifiedPercentage: data.total > 0 ? Math.round((data.verified / data.total) * 100) : 0
        }));

        res.json({ success: true, stats });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat statistik verifikasi: ' + error.message });
    }
};

// ==================== 4. PEER REVIEW: USULKAN REVISI SOAL ====================

// Rekan guru mengusulkan revisi pada soal bersama (Komentar / Edit Konkret)
export const proposeSoalRevision = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { id } = req.params;
        const { type, content, suggestedOptions, suggestedKey } = req.body; // type: 'COMMENT' | 'EDIT'

        if (!content) {
            return res.status(400).json({ success: false, message: 'Isi usulan revisi atau komentar wajib diisi.' });
        }

        const soal = await (prisma as any).bankSoal.findUnique({ where: { id } });
        if (!soal) return res.status(404).json({ success: false, message: 'Soal tidak ditemukan.' });

        const proposal = await (prisma as any).usulanRevisiSoal.create({
            data: {
                soalId: id,
                proposedById: user?.id || 'Guru',
                proposedByName: user?.name || 'Rekan Guru',
                type: type || 'COMMENT',
                content,
                suggestedOptions: suggestedOptions ? (typeof suggestedOptions === 'string' ? suggestedOptions : JSON.stringify(suggestedOptions)) : null,
                suggestedKey: suggestedKey || null,
                status: 'PENDING'
            }
        });

        res.json({
            success: true,
            message: 'Usulan revisi berhasil dikirimkan ke guru pemilik soal!',
            proposal
        });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal mengirim usulan revisi: ' + error.message });
    }
};

// Pemilik soal merespon usulan revisi (Approve / Reject)
export const respondToRevisionProposal = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { proposalId } = req.params;
        const { action, responseNotes } = req.body; // action: 'ACCEPT' | 'REJECT'

        const proposal = await (prisma as any).usulanRevisiSoal.findUnique({
            where: { id: proposalId },
            include: { soal: true }
        });

        if (!proposal) return res.status(404).json({ success: false, message: 'Usulan tidak ditemukan.' });

        // Verifikasi pemilik asli soal
        if (user && user.role !== 'ADMIN' && proposal.soal.teacherId && proposal.soal.teacherId !== user.id) {
            return res.status(403).json({ success: false, message: 'Hanya pemilik asli soal yang dapat menyetujui usulan revisi.' });
        }

        if (action === 'ACCEPT') {
            // Update proposal status
            await (prisma as any).usulanRevisiSoal.update({
                where: { id: proposalId },
                data: {
                    status: 'ACCEPTED',
                    responseNotes: responseNotes || 'Diterima oleh pemilik soal.',
                    respondedAt: new Date()
                }
            });

            // Jika ada edit konkret, update soal secara otomatis
            if (proposal.type === 'EDIT') {
                const updateSoalData: any = {};
                if (proposal.content) updateSoalData.content = proposal.content;
                if (proposal.suggestedOptions) updateSoalData.options = proposal.suggestedOptions;
                if (proposal.suggestedKey) updateSoalData.correctOption = proposal.suggestedKey;

                await (prisma as any).bankSoal.update({
                    where: { id: proposal.soalId },
                    data: updateSoalData
                });

                // Catat ke riwayat revisi
                await (prisma as any).riwayatRevisiSoal.create({
                    data: {
                        soalId: proposal.soalId,
                        changedById: proposal.proposedById,
                        changedByName: proposal.proposedByName,
                        summary: `Menerapkan usulan revisi dari ${proposal.proposedByName}: ${proposal.content.substring(0, 60)}...`,
                        previousData: JSON.stringify({
                            content: proposal.soal.content,
                            options: proposal.soal.options,
                            correctOption: proposal.soal.correctOption
                        })
                    }
                });
            }

            res.json({ success: true, message: 'Usulan revisi telah disetujui dan diterapkan pada soal!' });
        } else {
            await (prisma as any).usulanRevisiSoal.update({
                where: { id: proposalId },
                data: {
                    status: 'REJECTED',
                    responseNotes: responseNotes || 'Ditolak oleh pemilik soal.',
                    respondedAt: new Date()
                }
            });

            res.json({ success: true, message: 'Usulan revisi telah ditolak.' });
        }
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal merespon usulan: ' + error.message });
    }
};

// Mengambil riwayat revisi dan daftar usulan untuk satu butir soal
export const getSoalRevisionHistory = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const proposals = await (prisma as any).usulanRevisiSoal.findMany({
            where: { soalId: id },
            orderBy: { createdAt: 'desc' }
        });

        const histories = await (prisma as any).riwayatRevisiSoal.findMany({
            where: { soalId: id },
            orderBy: { createdAt: 'desc' }
        });

        res.json({ success: true, proposals, histories });
    } catch (error: any) {
        res.status(500).json({ success: false, message: 'Gagal memuat riwayat revisi: ' + error.message });
    }
};
