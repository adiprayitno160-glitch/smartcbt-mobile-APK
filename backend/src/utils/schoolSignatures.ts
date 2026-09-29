import prisma from './db';

export interface SchoolSignatures {
    schoolName: string;
    headmasterName: string;
    headmasterNip: string;
    headmasterTitle: string;
    bkCoordinatorName: string;
    bkCoordinatorNip: string;
    bkCoordinatorTitle: string;
}

/**
 * Mengambil informasi identitas sekolah dan tanda tangan resmi (Kepala Sekolah & Guru BK / Koordinator)
 * secara dinamis dari tabel `Settings` (prisma.settings).
 * Perubahan di Pengaturan Sistem akan langsung aktif di seluruh dokumen/laporan/BAP.
 */
export async function getSchoolSignatures(): Promise<SchoolSignatures> {
    try {
        const settings = await prisma.settings.findMany({
            where: {
                key: {
                    in: [
                        'SCHOOL_NAME',
                        'HEADMASTER_NAME',
                        'HEADMASTER_NIP',
                        'BK_COORDINATOR_NAME',
                        'BK_COORDINATOR_NIP',
                        'BK_COORDINATOR_TITLE'
                    ]
                }
            }
        });

        const map: Record<string, string> = {};
        for (const s of settings) {
            if (s.value && s.value.trim().length > 0) {
                map[s.key] = s.value.trim();
            }
        }

        const schoolName = map['SCHOOL_NAME'] || 'SMPN 1 Boyolangu';
        const headmasterName = map['HEADMASTER_NAME'] || 'Drs. H. SUKIRNO, M.Pd.';
        const headmasterNip = map['HEADMASTER_NIP'] || '19680514 199412 1 002';
        const headmasterTitle = `Kepala ${schoolName}`;

        const bkCoordinatorName = map['BK_COORDINATOR_NAME'] || 'Dra. NURUL HIDAYATI';
        const bkCoordinatorNip = map['BK_COORDINATOR_NIP'] || '19740921 199903 2 005';
        const bkCoordinatorTitle = map['BK_COORDINATOR_TITLE'] || 'Guru BK / Koordinator Presensi';

        return {
            schoolName,
            headmasterName,
            headmasterNip,
            headmasterTitle,
            bkCoordinatorName,
            bkCoordinatorNip,
            bkCoordinatorTitle
        };
    } catch (e) {
        console.error('Error in getSchoolSignatures:', e);
        return {
            schoolName: 'SMPN 1 Boyolangu',
            headmasterName: 'Drs. H. SUKIRNO, M.Pd.',
            headmasterNip: '19680514 199412 1 002',
            headmasterTitle: 'Kepala SMPN 1 Boyolangu',
            bkCoordinatorName: 'Dra. NURUL HIDAYATI',
            bkCoordinatorNip: '19740921 199903 2 005',
            bkCoordinatorTitle: 'Guru BK / Koordinator Presensi'
        };
    }
}
