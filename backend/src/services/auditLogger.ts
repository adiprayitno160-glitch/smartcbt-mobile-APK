import { Request } from 'express';
import prisma from '../utils/db';

export async function logAudit(
    req: Request | null,
    action: string,
    target?: string,
    details?: any
) {
    try {
        const user = req ? (req as any).user : null;
        const ip = req ? (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '') : '';
        const detailsStr = typeof details === 'object' ? JSON.stringify(details) : (details || null);

        await prisma.auditLog.create({
            data: {
                userId: user && user.id !== undefined && user.id !== null ? String(user.id) : null,
                userName: user ? String(user.name) : 'SYSTEM',
                role: user ? String(user.role) : 'SYSTEM',
                action,
                target: target || null,
                details: detailsStr,
                ipAddress: String(ip)
            }
        });
    } catch (err) {
        console.error('[AuditLog Error]', err);
    }
}
