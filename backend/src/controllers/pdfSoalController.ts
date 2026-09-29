import { Request, Response } from 'express';
import prisma from '../utils/db';
import fs from 'fs';
import path from 'path';

const pdfParse = require('pdf-parse');

export interface ParsedQuestionItem {
    id: number | string;
    number: number;
    question: string;
    options: string[];
    correctOption: string;
    type: 'PG' | 'ESSAY' | 'ISIAN' | 'BENAR_SALAH';
    explanation?: string;
}

/**
 * Ekstraksi butir soal dari teks PDF secara cerdas
 */
export function extractQuestionsFromText(rawText: string): { questions: ParsedQuestionItem[]; rawSnippet: string } {
    if (!rawText || !rawText.trim()) {
        return { questions: [], rawSnippet: '' };
    }

    const cleanText = rawText.replace(/\r\n/g, '\n').replace(/\r/g, '\n');

    // 1. Cek apakah ada bagian Kunci Jawaban di akhir dokumen
    const answerKeyMap = new Map<number, string>();
    const answerKeySectionMatch = cleanText.match(/(?:KUNCI\s+JAWABAN|KUNCI\s+SOAL|KUNCI|ANSWER\s+KEY)[\s\S]*$/i);
    if (answerKeySectionMatch) {
        const keySectionText = answerKeySectionMatch[0];
        const keyRegex = /(?:^|\s|[,\n])(\d+)[\.\s:=\-]+([A-Ea-e])/g;
        let km: RegExpExecArray | null;
        while ((km = keyRegex.exec(keySectionText)) !== null) {
            const num = parseInt(km[1], 10);
            const opt = km[2].toUpperCase();
            if (num > 0 && ['A', 'B', 'C', 'D', 'E'].includes(opt)) {
                answerKeyMap.set(num, opt);
            }
        }
    }

    // 2. Pisahkan teks dokumen menjadi butir-butir soal berdasarkan nomor soal (1., 2., dst.)
    const questionRegex = /(?:^|\n)\s*(?:Soal\s*)?(\d+)[\.\)\:\-]\s*([\s\S]*?)(?=(?:\n\s*(?:Soal\s*)?\d+[\.\)\:\-]|\n\s*(?:KUNCI\s+JAWABAN|KUNCI\s+SOAL|ANSWER\s+KEY)|$))/gi;

    const parsedList: ParsedQuestionItem[] = [];
    let match: RegExpExecArray | null;

    while ((match = questionRegex.exec(cleanText)) !== null) {
        const qNum = parseInt(match[1], 10);
        const qBody = match[2].trim();

        if (!qBody || qBody.length < 3) continue;

        // Pisahkan teks pertanyaan dan pilihan jawaban (A, B, C, D, E)
        const optionRegex = /(?:^|\n)\s*([A-Ea-e])[\.\)\:\-]\s*([\s\S]*?)(?=(?:\n\s*[A-Ea-e][\.\)\:\-]|\n\s*(?:Kunci|Jawaban|Pembahasan)|$))/gi;
        const options: string[] = [];
        let optMatch: RegExpExecArray | null;
        let firstOptIndex = -1;

        // Cari indeks pertama munculnya opsi A
        const firstAIndex = qBody.search(/(?:^|\n)\s*[A-Aa-a][\.\)\:\-]/);
        let questionText = qBody;
        if (firstAIndex !== -1) {
            questionText = qBody.substring(0, firstAIndex).trim();
            const optionsPart = qBody.substring(firstAIndex);

            while ((optMatch = optionRegex.exec(optionsPart)) !== null) {
                const optLetter = optMatch[1].toUpperCase();
                const optContent = optMatch[2].trim().replace(/\n+/g, ' ');
                options.push(`${optLetter}. ${optContent}`);
            }
        }

        // Cek kunci jawaban inline di dalam soal jika ada (misal: "Kunci: A" atau "Jawaban: B")
        let correctOption = 'A';
        const inlineKeyMatch = qBody.match(/(?:Kunci|Jawaban|Kunci\s*Jawaban|Ans)\s*[:=]?\s*([A-Ea-e])/i);
        if (inlineKeyMatch) {
            correctOption = inlineKeyMatch[1].toUpperCase();
            // Bersihkan teks pertanyaan dari baris kunci
            questionText = questionText.replace(/(?:Kunci|Jawaban|Kunci\s*Jawaban|Ans)\s*[:=]?\s*[A-Ea-e]/gi, '').trim();
        } else if (answerKeyMap.has(qNum)) {
            correctOption = answerKeyMap.get(qNum)!;
        }

        // Cek penjelasan / pembahasan jika ada
        let explanation = '';
        const explMatch = qBody.match(/(?:Pembahasan|Penjelasan|Solusi)\s*[:=]?\s*([\s\S]*?)(?=$)/i);
        if (explMatch) {
            explanation = explMatch[1].trim();
        }

        const isObjective = options.length >= 2;
        parsedList.push({
            id: qNum,
            number: qNum,
            question: questionText.replace(/\n+/g, ' ').trim(),
            options: isObjective ? options : [],
            correctOption: isObjective ? correctOption : '',
            type: isObjective ? 'PG' : 'ESSAY',
            explanation
        });
    }

    // Jika pola standar nomor tidak menemukan soal (mungkin format berbeda), buat pembagian per paragraf
    if (parsedList.length === 0) {
        const paragraphs = cleanText.split(/\n\s*\n/).filter(p => p.trim().length > 15);
        paragraphs.slice(0, 30).forEach((p, idx) => {
            parsedList.push({
                id: idx + 1,
                number: idx + 1,
                question: p.trim(),
                options: ['A. Opsi 1', 'B. Opsi 2', 'C. Opsi 3', 'D. Opsi 4'],
                correctOption: 'A',
                type: 'PG',
                explanation: ''
            });
        });
    }

    return {
        questions: parsedList,
        rawSnippet: cleanText.substring(0, 1000)
    };
}

/**
 * 1. Upload & Parsing Soal dari File PDF
 */
export const parsePdfSoal = async (req: Request, res: Response) => {
    try {
        if (!req.file) {
            return res.status(400).json({ success: false, message: 'File PDF wajib diunggah' });
        }

        const fileBuffer = req.file.buffer || (req.file.path ? fs.readFileSync(req.file.path) : null);
        if (!fileBuffer) {
            return res.status(400).json({ success: false, message: 'Isi file PDF kosong atau tidak terbaca' });
        }
        
        let pdfData: any;
        try {
            pdfData = await pdfParse(fileBuffer);
        } catch (err: any) {
            return res.status(400).json({
                success: false,
                message: 'Gagal membaca format PDF: ' + (err.message || 'File PDF rusak atau terenkripsi')
            });
        }

        const extracted = extractQuestionsFromText(pdfData.text || '');

        return res.json({
            success: true,
            message: `Berhasil mengekstrak ${extracted.questions.length} butir soal dari file PDF. Silakan lakukan preview dan koreksi manual sebelum menyimpan.`,
            fileName: req.file.originalname,
            totalPages: pdfData.numpages || 1,
            totalQuestions: extracted.questions.length,
            questions: extracted.questions,
            rawPreview: extracted.rawSnippet
        });
    } catch (error: any) {
        console.error('Error in parsePdfSoal:', error);
        return res.status(500).json({ success: false, message: 'Gagal memproses file PDF: ' + error.message });
    }
};

/**
 * 2. Ambil Daftar Soal dari Bank Soal
 */
export const getBankSoal = async (req: Request, res: Response) => {
    try {
        const { subjectName, level, type, search } = req.query;
        const whereClause: any = {};

        if (subjectName && String(subjectName).trim()) {
            whereClause.subjectName = { contains: String(subjectName).trim() };
        }
        if (level && String(level).trim() && level !== 'ALL') {
            whereClause.level = String(level).trim();
        }
        if (type && String(type).trim()) {
            whereClause.type = String(type).trim();
        }
        if (search && String(search).trim()) {
            whereClause.content = { contains: String(search).trim() };
        }

        const items = await (prisma as any).bankSoal.findMany({
            where: whereClause,
            orderBy: { createdAt: 'desc' },
            take: 150
        });

        const mapped = items.map((it: any) => {
            let parsedOpts = [];
            try {
                parsedOpts = it.options ? JSON.parse(it.options) : [];
            } catch (e) {
                parsedOpts = [];
            }
            return {
                ...it,
                optionsList: parsedOpts
            };
        });

        return res.json({
            success: true,
            total: mapped.length,
            data: mapped
        });
    } catch (error: any) {
        console.error('Error in getBankSoal:', error);
        return res.status(500).json({ success: false, message: 'Gagal memuat bank soal: ' + error.message });
    }
};

/**
 * 3. Simpan Butir Soal ke Bank Soal (Setelah Koreksi / Manual)
 */
export const saveQuestionsToBank = async (req: Request, res: Response) => {
    try {
        const user = (req as any).user;
        const { subjectName, level, sourcePdf, questions } = req.body;

        if (!subjectName || !questions || !Array.isArray(questions) || questions.length === 0) {
            return res.status(400).json({
                success: false,
                message: 'Nama mata pelajaran dan daftar soal wajib disertakan'
            });
        }

        const createdItems = [];
        for (const q of questions) {
            const content = q.question || q.content;
            if (!content || !String(content).trim()) continue;

            const optsString = typeof q.options === 'string' 
                ? q.options 
                : (Array.isArray(q.options) ? JSON.stringify(q.options) : null);

            const item = await (prisma as any).bankSoal.create({
                data: {
                    subjectName: String(subjectName).trim(),
                    level: level || 'ALL',
                    type: q.type || 'PG',
                    content: String(content).trim(),
                    options: optsString,
                    correctOption: q.correctOption ? String(q.correctOption).trim().toUpperCase() : 'A',
                    explanation: q.explanation || null,
                    sourcePdf: sourcePdf || null,
                    teacherId: user ? user.id : null
                }
            });
            createdItems.push(item);
        }

        return res.json({
            success: true,
            message: `✔ Berhasil menyimpan ${createdItems.length} butir soal ke Bank Soal ${subjectName}!`,
            totalSaved: createdItems.length
        });
    } catch (error: any) {
        console.error('Error in saveQuestionsToBank:', error);
        return res.status(500).json({ success: false, message: 'Gagal menyimpan butir soal: ' + error.message });
    }
};

/**
 * 4. Hapus Soal dari Bank Soal
 */
export const deleteBankSoal = async (req: Request, res: Response) => {
    try {
        const { id } = req.params;
        await (prisma as any).bankSoal.delete({
            where: { id }
        });
        return res.json({ success: true, message: 'Soal berhasil dihapus dari Bank Soal' });
    } catch (error: any) {
        return res.status(500).json({ success: false, message: 'Gagal menghapus soal: ' + error.message });
    }
};
