import { Request, Response } from 'express';
import prisma from '../utils/db';
import { getClassExamToken, getClassProctors } from './cbtController';

export const getTeacherExams = async (req: Request, res: Response) => {
    try {
        const [exams, classes] = await Promise.all([
            prisma.exam.findMany({
                include: {
                    subject: true,
                    _count: { select: { questions: true, studentExams: true } }
                },
                orderBy: { createdAt: 'desc' }
            }),
            prisma.class.findMany({ orderBy: { name: 'asc' } })
        ]);

        const enrichedExams = exams.map(exam => {
            const classTokensMap: Record<string, string> = {};
            const assigned = (exam.assignedClasses || 'ALL').trim();
            
            let targetClasses: string[] = [];
            if (assigned === 'ALL' || assigned === 'Semua Kelas' || !assigned) {
                targetClasses = classes.map(c => c.name);
            } else {
                targetClasses = assigned.split(',').map(c => c.trim()).filter(Boolean);
            }

            for (const cls of targetClasses) {
                classTokensMap[cls] = getClassExamToken(exam.id, cls, exam.token);
            }

            const classProctorsMap = getClassProctors(exam.id);

            return {
                ...exam,
                classTokens: classTokensMap,
                classProctors: classProctorsMap,
                targetClassesList: targetClasses
            };
        });

        res.json(enrichedExams);
    } catch (error) {
        console.error('Error fetching exams:', error);
        res.status(500).json({ message: 'Error fetching exams' });
    }
};

export const createExam = async (req: Request, res: Response) => {
    const { title, subjectName, durationMinutes, minDurationMinutes, assignedClasses, sessionName, executionDate, startTimeStr, endTimeStr, randomizeQuestions, randomizeOptions, showScoreToStudent, maxStrikes, examType, roomName, proctorName, passingGrade, level } = req.body;
    const user = (req as any).user;

    try {
        const subName = (subjectName || 'Umum').trim();
        let subject = await prisma.subject.findUnique({ where: { name: subName } });
        if (!subject) {
            subject = await prisma.subject.create({ data: { name: subName } });
        }

        const tokenStr = Math.random().toString(36).substring(2, 8).toUpperCase();

        const exam = await prisma.exam.create({
            data: {
                title: title || 'Paket Ujian Baru',
                subjectId: subject.id,
                durationMinutes: Number(durationMinutes) || 90,
                minDurationMinutes: Number(minDurationMinutes) || 30,
                token: tokenStr,
                isTokenActive: false,
                assignedClasses: assignedClasses || 'ALL',
                sessionName: sessionName || 'Sesi 1 (Pagi)',
                roomName: roomName || 'Lab Komputer 1',
                proctorName: proctorName || 'Proktor Utama',
                passingGrade: Number(passingGrade) || 75,
                level: level || 'ALL',
                examType: examType || 'SAS',
                executionDate: executionDate || new Date().toISOString().split('T')[0],
                startTimeStr: startTimeStr || '07:30',
                endTimeStr: endTimeStr || '09:30',
                randomizeQuestions: randomizeQuestions !== undefined ? Boolean(randomizeQuestions) : true,
                randomizeOptions: randomizeOptions !== undefined ? Boolean(randomizeOptions) : true,
                showScoreToStudent: Boolean(showScoreToStudent),
                maxStrikes: Number(maxStrikes) || 3,
                teacherId: user ? user.id : null
            },
            include: { subject: true }
        });
        res.json(exam);
    } catch (error) {
        console.error('Error creating exam:', error);
        res.status(500).json({ message: 'Error creating exam' });
    }
};

export const scheduleNationalExam = async (req: Request, res: Response) => {
    const id = req.params.id as string;
    const { title, subjectName, durationMinutes, minDurationMinutes, assignedClasses, sessionName, executionDate, startTimeStr, endTimeStr, isTokenActive, randomizeQuestions, randomizeOptions, showScoreToStudent, maxStrikes, resetToken, examType, roomName, proctorName, passingGrade, level } = req.body;
    try {
        const updateData: any = {};
        if (title) updateData.title = title;
        if (durationMinutes) updateData.durationMinutes = Number(durationMinutes);
        if (minDurationMinutes !== undefined) updateData.minDurationMinutes = Number(minDurationMinutes);
        if (assignedClasses !== undefined) updateData.assignedClasses = assignedClasses;
        if (sessionName !== undefined) updateData.sessionName = sessionName;
        if (roomName !== undefined) updateData.roomName = roomName;
        if (proctorName !== undefined) updateData.proctorName = proctorName;
        if (passingGrade !== undefined) updateData.passingGrade = Number(passingGrade);
        if (level !== undefined) updateData.level = level;
        if (examType !== undefined) updateData.examType = examType;
        if (executionDate !== undefined) updateData.executionDate = executionDate;
        if (startTimeStr !== undefined) updateData.startTimeStr = startTimeStr;
        if (endTimeStr !== undefined) updateData.endTimeStr = endTimeStr;
        if (isTokenActive !== undefined) updateData.isTokenActive = Boolean(isTokenActive);
        if (randomizeQuestions !== undefined) updateData.randomizeQuestions = Boolean(randomizeQuestions);
        if (randomizeOptions !== undefined) updateData.randomizeOptions = Boolean(randomizeOptions);
        if (showScoreToStudent !== undefined) updateData.showScoreToStudent = Boolean(showScoreToStudent);
        if (maxStrikes !== undefined) updateData.maxStrikes = Number(maxStrikes);
        if (resetToken) updateData.token = Math.random().toString(36).substring(2, 8).toUpperCase();

        if (subjectName) {
            let subject = await prisma.subject.findUnique({ where: { name: subjectName.trim() } });
            if (!subject) {
                subject = await prisma.subject.create({ data: { name: subjectName.trim() } });
            }
            updateData.subjectId = subject.id;
        }

        const updated = await prisma.exam.update({
            where: { id },
            data: updateData,
            include: { subject: true }
        });

        res.json({ message: 'Penjadwalan dan konfigurasi Ujian berhasil disimpan', exam: updated });
    } catch (error) {
        console.error('Error scheduling exam:', error);
        res.status(500).json({ message: 'Gagal memperbarui jadwal ujian' });
    }
};

export const getExamResults = async (req: Request, res: Response) => {
    try {
        const { examId, className } = req.query;

        const whereClause: any = {};
        if (examId && examId !== 'ALL') {
            whereClause.examId = String(examId);
        }

        const studentExams = await prisma.studentExam.findMany({
            where: whereClause,
            include: {
                user: true,
                exam: { include: { subject: true, _count: { select: { questions: true } } } }
            },
            orderBy: [
                { score: 'desc' },
                { startTime: 'asc' }
            ]
        });

        let results = studentExams;
        if (className && className !== 'ALL') {
            results = results.filter(se => se.user.className === className);
        }

        res.json(results);
    } catch (error) {
        console.error('Error getting exam results:', error);
        res.status(500).json({ message: 'Gagal mengambil rekap hasil ujian' });
    }
};

export const deleteExam = async (req: Request, res: Response) => {
    try {
        const id = req.params.id as string;
        await prisma.question.deleteMany({ where: { examId: id } });
        await prisma.studentExam.deleteMany({ where: { examId: id } });
        await prisma.exam.delete({ where: { id } });
        res.json({ message: 'Paket ujian berhasil dihapus' });
    } catch (error) {
        res.status(500).json({ message: 'Gagal menghapus ujian' });
    }
};

import fs from 'fs';
import path from 'path';

export const deleteQuestion = async (req: Request, res: Response) => {
    try {
        const id = req.params.id as string;
        await prisma.question.delete({ where: { id } });
        res.json({ message: 'Butir soal berhasil dihapus' });
    } catch (error) {
        console.error('Error deleting question:', error);
        res.status(500).json({ message: 'Gagal menghapus butir soal' });
    }
};

export const uploadQuestionImage = async (req: Request, res: Response) => {
    try {
        const { imageBase64, fileName } = req.body;
        if (!imageBase64) {
            return res.status(400).json({ message: 'Data gambar (base64) wajib dikirim' });
        }

        const uploadDir = path.join(process.cwd(), 'uploads', 'questions');
        if (!fs.existsSync(uploadDir)) {
            fs.mkdirSync(uploadDir, { recursive: true });
        }

        let ext = 'png';
        let base64Data = imageBase64;
        const matches = imageBase64.match(/^data:image\/([a-zA-Z0-9+]+);base64,(.+)$/);
        if (matches) {
            ext = matches[1] === 'jpeg' ? 'jpg' : matches[1];
            base64Data = matches[2];
        }

        const safeFileName = `q_${Date.now()}_${Math.random().toString(36).substring(2, 7)}.${ext}`;
        const targetPath = path.join(uploadDir, safeFileName);

        fs.writeFileSync(targetPath, Buffer.from(base64Data, 'base64'));

        const publicUrl = `/uploads/questions/${safeFileName}`;
        res.json({
            message: 'Gambar soal berhasil diunggah',
            imageUrl: publicUrl,
            location: publicUrl // TinyMCE image upload compatibility
        });
    } catch (error) {
        console.error('Error uploading question image:', error);
        res.status(500).json({ message: 'Gagal mengunggah gambar soal' });
    }
};

export const getQuestions = async (req: Request, res: Response) => {
    try {
        const examId = req.params.examId as string;
        const questions = await prisma.question.findMany({
            where: { examId },
            orderBy: { orderNum: 'asc' }
        });
        res.json(questions);
    } catch (error) {
        res.status(500).json({ message: 'Error fetching questions' });
    }
};

export const createQuestion = async (req: Request, res: Response) => {
    const examId = req.params.examId as string;
    const { content, type, correctOption, options, imageUrl, audioUrl, cognitiveLevel, kdTag } = req.body;
    try {
        const count = await prisma.question.count({ where: { examId } });
        const mediaType = imageUrl && audioUrl ? 'BOTH' : (imageUrl ? 'IMAGE' : (audioUrl ? 'AUDIO' : 'NONE'));

        const question = await prisma.question.create({
            data: {
                examId,
                content,
                type: type || 'MULTIPLE_CHOICE',
                imageUrl: imageUrl || null,
                audioUrl: audioUrl || null,
                mediaType,
                correctOption: correctOption || 'A',
                options: typeof options === 'object' ? JSON.stringify(options) : options,
                cognitiveLevel: cognitiveLevel || 'C3',
                kdTag: kdTag || null,
                orderNum: count + 1
            }
        });
        res.json(question);
    } catch (error) {
        console.error('Error creating question:', error);
        res.status(500).json({ message: 'Error creating question' });
    }
};

export const updateQuestion = async (req: Request, res: Response) => {
    const id = req.params.id as string;
    const { content, type, correctOption, options, imageUrl, audioUrl, cognitiveLevel, kdTag, orderNum } = req.body;
    try {
        const mediaType = imageUrl && audioUrl ? 'BOTH' : (imageUrl ? 'IMAGE' : (audioUrl ? 'AUDIO' : 'NONE'));
        const updateData: any = {
            content,
            correctOption,
            options: typeof options === 'object' ? JSON.stringify(options) : options,
            imageUrl: imageUrl || null,
            audioUrl: audioUrl || null,
            mediaType
        };
        if (type !== undefined) updateData.type = type;
        if (cognitiveLevel !== undefined) updateData.cognitiveLevel = cognitiveLevel;
        if (kdTag !== undefined) updateData.kdTag = kdTag;
        if (orderNum !== undefined) updateData.orderNum = Number(orderNum);

        const question = await prisma.question.update({
            where: { id },
            data: updateData
        });
        res.json(question);
    } catch (error) {
        console.error('Error updating question:', error);
        res.status(500).json({ message: 'Error updating question' });
    }
};

// ==========================================
// FITUR BEESMART CBT: LEMBAR JAWABAN SISWA (LJS DIGITAL)
// ==========================================
export const getStudentExamLjs = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        const studentExam = await prisma.studentExam.findUnique({
            where: { id: String(id) },
            include: {
                user: {
                    select: {
                        id: true,
                        name: true,
                        username: true,
                        className: true,
                        nisn: true
                    }
                },
                exam: {
                    include: {
                        subject: true,
                        questions: {
                            orderBy: { orderNum: 'asc' }
                        }
                    }
                }
            }
        });

        if (!studentExam) {
            return res.status(404).json({ message: 'Lembar Ujian Siswa tidak ditemukan' });
        }

        // Parse student answers
        let answersObj: Record<string, string> = {};
        if (studentExam.answers) {
            try {
                const parsed = JSON.parse(studentExam.answers);
                if (Array.isArray(parsed)) {
                    parsed.forEach((item: any) => {
                        if (item.questionId) {
                            answersObj[item.questionId] = String(item.answer || '').toUpperCase().trim();
                        }
                    });
                } else if (typeof parsed === 'object') {
                    Object.keys(parsed).forEach(k => {
                        answersObj[k] = String(parsed[k] || '').toUpperCase().trim();
                    });
                }
            } catch (e) {
                console.warn('Error parsing student answers for LJS:', e);
            }
        }

        let totalCorrect = 0;
        let totalWrong = 0;
        let totalUnattempted = 0;
        const questions = studentExam.exam?.questions || [];

        const items = questions.map(q => {
            const studentAnswer = answersObj[q.id] || answersObj[String(q.orderNum)] || '';
            const correctOption = (q.correctOption || '').toUpperCase().trim();
            const hasAnswer = studentAnswer.length > 0;
            const isCorrect = hasAnswer && (correctOption === studentAnswer);

            if (!hasAnswer) {
                totalUnattempted++;
            } else if (isCorrect) {
                totalCorrect++;
            } else {
                totalWrong++;
            }

            return {
                id: q.id,
                orderNum: q.orderNum,
                content: q.content,
                type: q.type,
                options: q.options,
                imageUrl: q.imageUrl,
                correctOption,
                studentAnswer,
                isCorrect: q.type === 'ESSAY' ? null : isCorrect,
                status: !hasAnswer ? 'KOSONG' : (isCorrect ? 'BENAR' : 'SALAH')
            };
        });

        const totalQuestions = questions.length;
        const passingGrade = studentExam.exam?.passingGrade || 75;
        const isPassed = (studentExam.score ?? 0) >= passingGrade;

        res.json({
            studentExam: {
                id: studentExam.id,
                status: studentExam.status,
                score: studentExam.score,
                strikeCount: studentExam.strikeCount || 0,
                startTime: studentExam.startTime,
                endTime: studentExam.endTime,
                user: studentExam.user,
                exam: {
                    id: studentExam.exam.id,
                    title: studentExam.exam.title,
                    examType: studentExam.exam.examType || 'SAS',
                    subjectName: studentExam.exam.subject?.name || '-',
                    durationMinutes: studentExam.exam.durationMinutes,
                    passingGrade,
                    roomName: studentExam.exam.roomName || 'Lab Komputer 1',
                    proctorName: studentExam.exam.proctorName || 'Proktor Utama',
                    isPassed
                }
            },
            summary: {
                totalQuestions,
                totalAttempted: totalCorrect + totalWrong,
                totalCorrect,
                totalWrong,
                totalUnattempted,
                passingGrade,
                isPassed,
                finalScore: studentExam.score ?? 0
            },
            items
        });
    } catch (error) {
        console.error('Error fetching student exam LJS:', error);
        res.status(500).json({ message: 'Gagal memuat Lembar Jawaban Siswa (LJS)' });
    }
};

// ==========================================
// FITUR BEESMART CBT: EKSPOR REKAP NILAI KE CSV / EXCEL
// ==========================================
export const exportExamResultsCsv = async (req: Request, res: Response) => {
    try {
        const { examId, className } = req.query;

        const whereClause: any = {};
        if (examId && examId !== 'ALL') {
            whereClause.examId = String(examId);
        }

        const studentExams = await prisma.studentExam.findMany({
            where: whereClause,
            include: {
                user: true,
                exam: {
                    include: {
                        subject: true,
                        questions: { select: { id: true, correctOption: true, orderNum: true } }
                    }
                }
            },
            orderBy: [
                { score: 'desc' },
                { startTime: 'asc' }
            ]
        });

        let results = studentExams;
        if (className && className !== 'ALL') {
            results = results.filter(se => se.user.className === className);
        }

        // Generate CSV rows with BOM for Excel compatibility in Indonesia
        const escapeCsv = (str: any) => {
            if (str === null || str === undefined) return '""';
            const s = String(str).replace(/"/g, '""');
            return `"${s}"`;
        };

        const headers = [
            'No',
            'NISN',
            'Nama Siswa',
            'Kelas',
            'Mata Pelajaran',
            'Paket Ujian',
            'Jenis Asesmen',
            'Status',
            'Benar',
            'Salah',
            'Nilai Akhir',
            'KKM',
            'Ketuntasan',
            'Pelanggaran (Strike)',
            'Waktu Mulai',
            'Waktu Selesai'
        ];

        const csvRows: string[] = [headers.join(',')];

        results.forEach((se, idx) => {
            // Count correct / wrong answers
            let correctCount = 0;
            let wrongCount = 0;
            if (se.answers && se.exam?.questions) {
                try {
                    let answersObj: Record<string, string> = {};
                    const parsed = JSON.parse(se.answers);
                    if (Array.isArray(parsed)) {
                        parsed.forEach((item: any) => {
                            if (item.questionId) answersObj[item.questionId] = String(item.answer || '').toUpperCase().trim();
                        });
                    } else if (typeof parsed === 'object') {
                        Object.keys(parsed).forEach(k => {
                            answersObj[k] = String(parsed[k] || '').toUpperCase().trim();
                        });
                    }
                    se.exam.questions.forEach(q => {
                        const ans = answersObj[q.id] || answersObj[String(q.orderNum)] || '';
                        if (ans) {
                            if (q.correctOption && ans === q.correctOption.toUpperCase().trim()) {
                                correctCount++;
                            } else {
                                wrongCount++;
                            }
                        }
                    });
                } catch (e) {}
            }

            const kkm = se.exam?.passingGrade || 75;
            const score = se.score ?? 0;
            const ketuntasan = score >= kkm ? 'TUNTAS' : 'BELUM TUNTAS';
            const startTimeStr = se.startTime ? new Date(se.startTime).toLocaleString('id-ID') : '-';
            const endTimeStr = se.endTime ? new Date(se.endTime).toLocaleString('id-ID') : '-';

            const row = [
                idx + 1,
                escapeCsv(se.user.nisn || '-'),
                escapeCsv(se.user.name),
                escapeCsv(se.user.className || '-'),
                escapeCsv(se.exam?.subject?.name || '-'),
                escapeCsv(se.exam?.title || '-'),
                escapeCsv(se.exam?.examType || 'SAS'),
                escapeCsv(se.status),
                correctCount,
                wrongCount,
                score,
                kkm,
                escapeCsv(ketuntasan),
                se.strikeCount || 0,
                escapeCsv(startTimeStr),
                escapeCsv(endTimeStr)
            ];

            csvRows.push(row.join(','));
        });

        // Add UTF-8 BOM (\uFEFF) so Excel opens Indonesian characters perfectly
        const csvContent = '\uFEFF' + csvRows.join('\r\n');
        const filename = `rekap_nilai_cbt_${new Date().toISOString().split('T')[0]}.csv`;

        res.setHeader('Content-Type', 'text/csv; charset=utf-8');
        res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);
        return res.status(200).send(csvContent);
    } catch (error) {
        console.error('Error exporting exam results CSV:', error);
        res.status(500).json({ message: 'Gagal mengekspor rekap nilai ujian' });
    }
};


