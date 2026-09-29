import prisma from './db';
import { supabase } from './supabaseClient';

/**
 * Normalizes class names across roman, arabic, hyphen, space, and prefix variations.
 * Examples:
 *  "7K" -> ["7K", "7-K", "7 K", "VII-K", "VIIK", "VII K"]
 *  "VII-K" -> ["7K", "7-K", "7 K", "VII-K", "VIIK", "VII K"]
 *  "Kelas 7K" -> ["7K", "7-K", "7 K", "VII-K", "VIIK", "VII K"]
 */
export function normalizeClassVariations(clsName: string): string[] {
    if (!clsName) return [];
    
    // Remove "KELAS " or "KLS " prefix, trim, uppercase
    let raw = clsName.trim().toUpperCase()
        .replace(/^(KELAS|KLS)\s+/i, '')
        .replace(/\s+/g, ' ')
        .trim();

    const clean = raw.replace(/[\s\-_]+/g, '');
    const v = new Set<string>([clsName.trim(), raw, clean]);

    const rToA: Record<string, string> = {
        'XII': '12', 'XI': '11', 'X': '10', 'IX': '9', 'VIII': '8', 'VII': '7'
    };
    const aToR: Record<string, string> = {
        '12': 'XII', '11': 'XI', '10': 'X', '9': 'IX', '8': 'VIII', '7': 'VII'
    };

    // Check Roman numeral prefix first (e.g. VII, VIII, IX)
    for (const [r, a] of Object.entries(rToA)) {
        if (clean.startsWith(r)) {
            const suf = clean.slice(r.length);
            addVariants(v, r, a, suf);
            break;
        }
    }

    // Check Arabic numeral prefix (e.g. 7, 8, 9, 10, 11, 12)
    for (const [a, r] of Object.entries(aToR)) {
        if (clean.startsWith(a)) {
            const suf = clean.slice(a.length);
            addVariants(v, r, a, suf);
            break;
        }
    }

    return Array.from(v).filter(Boolean);
}

function addVariants(set: Set<string>, roman: string, arabic: string, suffix: string) {
    if (!suffix) {
        set.add(roman);
        set.add(arabic);
        return;
    }
    // Roman combinations
    set.add(`${roman}${suffix}`);
    set.add(`${roman}-${suffix}`);
    set.add(`${roman} ${suffix}`);
    set.add(`${roman}_${suffix}`);

    // Arabic combinations
    set.add(`${arabic}${suffix}`);
    set.add(`${arabic}-${suffix}`);
    set.add(`${arabic} ${suffix}`);
    set.add(`${arabic}_${suffix}`);
}

/**
 * Returns the canonical display class name in standard Roman hyphen format.
 * e.g. "7K" -> "VII-K", "7-k" -> "VII-K", "8a" -> "VIII-A", "9-B" -> "IX-B"
 */
export function canonicalClassName(clsName: string): string {
    if (!clsName) return '';
    let raw = clsName.trim().toUpperCase()
        .replace(/^(KELAS|KLS)\s+/i, '')
        .replace(/\s+/g, '');

    const rToA: Record<string, string> = {
        'XII': '12', 'XI': '11', 'X': '10', 'IX': '9', 'VIII': '8', 'VII': '7'
    };
    const aToR: Record<string, string> = {
        '12': 'XII', '11': 'XI', '10': 'X', '9': 'IX', '8': 'VIII', '7': 'VII'
    };

    for (const [r] of Object.entries(rToA)) {
        if (raw.startsWith(r)) {
            const suf = raw.slice(r.length).replace(/^[_\s-]+/, '');
            return suf ? `${r}-${suf}` : r;
        }
    }

    for (const [a, r] of Object.entries(aToR)) {
        if (raw.startsWith(a)) {
            const suf = raw.slice(a.length).replace(/^[_\s-]+/, '');
            return suf ? `${r}-${suf}` : r;
        }
    }

    return clsName.trim().toUpperCase();
}

/**
 * Check if two class strings refer to the same class
 */
export function isSameClass(classA: string, classB: string): boolean {
    if (!classA || !classB) return false;
    if (classA.trim().toUpperCase() === classB.trim().toUpperCase()) return true;
    const normA = canonicalClassName(classA);
    const normB = canonicalClassName(classB);
    return normA === normB;
}

/**
 * Authoritative Homeroom Teacher Resolver for any class variation.
 * Queries Supabase & SQLite to find the assigned wali kelas (name, NIP, phone, id).
 */
export async function resolveHomeroomTeacher(className: string): Promise<{
    name: string;
    nip?: string;
    phone?: string;
    teacherId?: string;
} | null> {
    if (!className) return null;
    const variations = normalizeClassVariations(className);

    // 1. Cek di Supabase tabel kelas
    try {
        const { data: kData } = await supabase
            .from('kelas')
            .select('id, nama, wali_kelas_id')
            .or(variations.map(v => `nama.eq.${v}`).join(','));

        if (Array.isArray(kData) && kData.length > 0) {
            const matchedK = kData.find(k => k.wali_kelas_id);
            if (matchedK && matchedK.wali_kelas_id) {
                const { data: gData } = await supabase
                    .from('guru')
                    .select('id, nama, nip, no_hp')
                    .eq('id', matchedK.wali_kelas_id)
                    .single();

                if (gData && gData.nama) {
                    // Temukan ID lokal jika ada
                    const localT = await prisma.user.findFirst({
                        where: {
                            role: 'TEACHER',
                            OR: [
                                { name: { equals: gData.nama } },
                                { username: { equals: gData.nip } }
                            ]
                        },
                        select: { id: true, parentPhone: true }
                    });

                    return {
                        name: gData.nama,
                        nip: gData.nip || undefined,
                        phone: gData.no_hp || localT?.parentPhone || undefined,
                        teacherId: localT?.id || gData.id
                    };
                }
            }
        }
    } catch (e) {
        // Fallback to SQLite if Supabase unavailable
    }

    // 2. Cek di SQLite lokal: Guru dengan className yang cocok
    const localTeacher = await prisma.user.findFirst({
        where: {
            role: 'TEACHER',
            OR: [
                { className: { in: variations } },
                { teachingClasses: { contains: canonicalClassName(className) } }
            ]
        },
        select: { id: true, name: true, username: true, parentPhone: true, tugasTambahan: true, className: true }
    });

    if (localTeacher && (localTeacher.tugasTambahan?.includes('WALI') || localTeacher.className)) {
        return {
            name: localTeacher.name,
            nip: localTeacher.username || undefined,
            phone: localTeacher.parentPhone || undefined,
            teacherId: localTeacher.id
        };
    }

    // 3. Cek dari siswa di rombel ini yang sudah memiliki homeroomTeacher valid (bukan 'null')
    const sampleStudent = await prisma.user.findFirst({
        where: {
            role: 'STUDENT',
            className: { in: variations },
            homeroomTeacher: { not: null }
        },
        select: { homeroomTeacher: true }
    });

    if (sampleStudent?.homeroomTeacher && sampleStudent.homeroomTeacher !== 'null' && sampleStudent.homeroomTeacher.trim() !== '-') {
        const t = await prisma.user.findFirst({
            where: { role: 'TEACHER', name: sampleStudent.homeroomTeacher },
            select: { id: true, name: true, username: true, parentPhone: true }
        });
        return {
            name: sampleStudent.homeroomTeacher,
            nip: t?.username || undefined,
            phone: t?.parentPhone || undefined,
            teacherId: t?.id || undefined
        };
    }

    return null;
}
