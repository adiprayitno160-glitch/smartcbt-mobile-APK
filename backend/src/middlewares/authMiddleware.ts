import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import { revokedUserIds } from '../controllers/adminUserController';

const getSecretKey = () => process.env.JWT_SECRET || 'cbt_prod_secret_f9a83b27c64e10d592a8174fec01849a62bc38102d7e481b9201948271039841';

export interface AuthRequest extends Request {
    user?: any;
}

/**
 * Helper ekstraksi seluruh kandidat token untuk Web SSR & API (Authorization header, ?token= param, atau cookies)
 */
export const extractAllWebTokens = (req: Request): string[] => {
    const candidates: string[] = [];
    const authHeader = req.headers.authorization;
    if (authHeader && authHeader.startsWith('Bearer ')) {
        const candidate = authHeader.substring(7).trim();
        if (candidate && candidate !== 'null' && candidate !== 'undefined') {
            candidates.push(candidate);
        }
    }
    if (req.query.token) {
        const qToken = String(req.query.token).trim();
        if (qToken && qToken !== 'null' && qToken !== 'undefined' && !candidates.includes(qToken)) {
            candidates.push(qToken);
        }
    }
    if (req.headers.cookie) {
        const cookies = req.headers.cookie.split(';');
        for (const c of cookies) {
            const [k, v] = c.trim().split('=');
            if (k === 'token' || k === 'jwt_token' || k === 'admin_token') {
                const dec = decodeURIComponent(v || '').trim();
                if (dec && dec !== 'null' && dec !== 'undefined' && !candidates.includes(dec)) {
                    candidates.push(dec);
                }
            }
        }
    }
    return candidates;
};

export const extractWebToken = (req: Request): string | null => {
    const candidates = extractAllWebTokens(req);
    // Jika ada token yang valid, utamakan yang valid
    for (const token of candidates) {
        try {
            const user = jwt.verify(token, getSecretKey());
            if (user) return token;
        } catch (e) {
            // Coba kandidat berikutnya
        }
    }
    return candidates.length > 0 ? candidates[0] : null;
};

export const authenticateJWT = (req: AuthRequest, res: Response, next: NextFunction) => {
    const candidates = extractAllWebTokens(req);

    if (candidates.length === 0) {
        return res.status(401).json({ message: "Akses ditolak. Token tidak ditemukan." });
    }

    let verifiedUser: any = null;

    for (const token of candidates) {
        try {
            const user: any = jwt.verify(token, getSecretKey());
            if (user) {
                verifiedUser = user;
                break;
            }
        } catch (err) {
            // Stale candidate, check next candidate
        }
    }

    if (!verifiedUser) {
        return res.status(401).json({ 
            success: false,
            message: "Sesi telah berakhir atau tidak valid.",
            code: "TOKEN_EXPIRED"
        });
    }

    if (revokedUserIds.has(verifiedUser.id) || revokedUserIds.has(verifiedUser.username) || (verifiedUser.nisn && revokedUserIds.has(verifiedUser.nisn))) {
        return res.status(401).json({
            success: false,
            message: "Sesi login Anda telah diputus oleh Administrator atau Operator sekolah. Silakan masuk kembali.",
            code: "FORCE_LOGOUT",
            forceLogout: true
        });
    }

    req.user = verifiedUser;
    next();
};

export const requireRole = (roles: (string | String)[]) => {
    return (req: AuthRequest, res: Response, next: NextFunction) => {
        if (!req.user) {
            return res.status(403).json({ message: "Akses ditolak. Anda tidak memiliki izin untuk tindakan ini." });
        }
        const userRole = String(req.user.role || '').toUpperCase().trim();
        const upperRoles = roles.map(r => String(r).toUpperCase().trim());

        // ADMIN & SUPERADMIN have global access to all API routes
        if (userRole === 'ADMIN' || userRole === 'ADMINISTRATOR' || userRole === 'SUPERADMIN') {
            return next();
        }

        // Check if role is allowed
        let isAllowed = upperRoles.includes(userRole);
        if (!isAllowed) {
            if (upperRoles.includes('COUNSELOR') && (['BK', 'GURU_BK', 'GURU BK'].includes(userRole) || req.user.isCounselor)) isAllowed = true;
            if (upperRoles.includes('TEACHER') && ['GURU'].includes(userRole)) isAllowed = true;
            if (upperRoles.includes('MEDICAL') && ['UKS'].includes(userRole)) isAllowed = true;
            if (upperRoles.includes('LIBRARIAN') && ['PERPUSTAKAAN'].includes(userRole)) isAllowed = true;
        }

        if (!isAllowed) {
            return res.status(403).json({ message: "Akses ditolak. Anda tidak memiliki izin untuk tindakan ini." });
        }
        next();
    };
};

/**
 * Pembuat middleware autentikasi SSR berdasarkan peran (roles).
 * Admin memiliki hak akses penuh ke seluruh halaman portal.
 * Jika tidak terotentikasi, otomatis redirect ke halaman login portal (/?error=unauthorized).
 */
export const authenticateWebRoles = (allowedRoles: string[]) => {
    return (req: AuthRequest, res: Response, next: NextFunction) => {
        const token = extractWebToken(req);

        if (!token) {
            if (req.xhr || req.headers.accept?.includes('application/json')) {
                return res.status(401).json({ success: false, message: 'Autentikasi login diperlukan.' });
            }
            return res.redirect('/?error=unauthorized');
        }

        jwt.verify(token, getSecretKey(), (err, decoded: any) => {
            if (err || !decoded) {
                if (req.xhr || req.headers.accept?.includes('application/json')) {
                    return res.status(401).json({ success: false, message: 'Sesi login telah berakhir.' });
                }
                return res.redirect('/?error=session_expired');
            }

            const userRole = String(decoded.role || '').toUpperCase().trim();
            const upperAllowed = allowedRoles.map(r => r.toUpperCase().trim());

            // 1. ADMIN & SUPERADMIN dapat mengakses SELURUH halaman portal!
            if (userRole === 'ADMIN' || userRole === 'ADMINISTRATOR' || userRole === 'SUPERADMIN') {
                req.user = decoded;
                return next();
            }

            // 2. Cek apakah role langsung terdaftar
            let isAllowed = upperAllowed.includes(userRole);

            // 3. Alias peran & Multi-role
            if (!isAllowed) {
                // Guru BK / Konselor
                if (upperAllowed.includes('COUNSELOR') || upperAllowed.includes('BK')) {
                    if (['COUNSELOR', 'BK', 'GURU_BK', 'GURU BK'].includes(userRole) || decoded.isCounselor) {
                        isAllowed = true;
                    }
                }
                // Guru
                if (upperAllowed.includes('TEACHER') && ['TEACHER', 'GURU'].includes(userRole)) {
                    isAllowed = true;
                }
                // Medis UKS
                if (upperAllowed.includes('MEDICAL') && ['MEDICAL', 'UKS'].includes(userRole)) {
                    isAllowed = true;
                }
                // Perpustakaan
                if (upperAllowed.includes('LIBRARIAN') && ['LIBRARIAN', 'PERPUSTAKAAN'].includes(userRole)) {
                    isAllowed = true;
                }
            }

            if (!isAllowed) {
                return res.status(403).send('Akses Ditolak: Anda tidak memiliki hak akses untuk halaman ini.');
            }
            req.user = decoded;
            next();
        });
    };
};

export const authenticateWebAdmin = authenticateWebRoles(['ADMIN', 'OPERATOR']);
export const authenticateWebBk = authenticateWebRoles(['ADMIN', 'OPERATOR', 'COUNSELOR', 'BK', 'GURU_BK']);
export const authenticateWebGuru = authenticateWebRoles(['ADMIN', 'OPERATOR', 'TEACHER']);
export const authenticateWebUks = authenticateWebRoles(['ADMIN', 'OPERATOR', 'MEDICAL', 'UKS', 'COUNSELOR']);
export const authenticateWebOperator = authenticateWebRoles(['ADMIN', 'OPERATOR']);

export const requireExamBrowser = (req: AuthRequest, res: Response, next: NextFunction) => {
    const clientType = req.headers['x-client-type'];
    const userAgent = String(req.headers['user-agent'] || '');
    
    // Guru, Admin, Operator bebas akses
    const user = req.user;
    if (user && (user.role === 'ADMIN' || user.role === 'OPERATOR' || user.role === 'TEACHER')) {
        return next();
    }

    // Untuk Siswa: wajib melalui APK Smart School ExamBrowser
    const isExambro = clientType === 'SmartSchool-ExamBrowser' ||
                      userAgent.includes('SmartSchool-ExamBrowser') ||
                      userAgent.includes('SmartSchool-CBT') ||
                      userAgent.includes('SmartSchool') ||
                      userAgent.includes('okhttp') ||
                      userAgent.includes('Dalvik');

    if (!isExambro && user && user.role === 'STUDENT') {
        return res.status(403).json({
            success: false,
            message: 'Akses Ditolak! Ujian CBT SMPN 1 Boyolangu HANYA dapat diakses melalui Aplikasi Resmi Smart School ExamBrowser (APK). Demi menjaga integritas dan keamanan asesmen nasional, akses melalui web browser biasa diblokir.'
        });
    }
    next();
};


