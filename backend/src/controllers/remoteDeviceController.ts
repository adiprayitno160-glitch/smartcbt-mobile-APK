import { Request, Response } from 'express';
import prisma from '../utils/db';
import path from 'path';
import fs from 'fs';
import { classifyFolder } from '../utils/folderClassifier';
import { revokedUserIds } from './adminUserController';
const archiver = require('archiver');

function paramStr(param: string | string[] | undefined): string {
    if (Array.isArray(param)) return param[0];
    return param || '';
}

/**
 * Safe BigInt → Number conversion untuk JSON serialization.
 * Prisma SQLite mengembalikan BigInt yang tidak bisa di-JSON.stringify.
 */
function safeNumber(val: any): number | null {
    if (val == null) return null;
    try { return Number(val); } catch { return null; }
}

/**
 * POST /api/device/register
 * APK mendaftarkan device ke server saat pertama kali login
 */
export const registerDevice = async (req: Request, res: Response) => {
    try {
        const {
            deviceAndroidId,
            deviceName,
            deviceModel,
            deviceBrand,
            deviceOsVersion,
            appVersion,
            storagePath,
            username,
            userId,
            nisn,
            batteryLevel,
            isCharging,
            totalStorage,
            freeStorage,
            totalSdCard,
            freeSdCard,
            wifiSsid
        } = req.body;

        if (!deviceAndroidId) {
            return res.status(400).json({ message: 'deviceAndroidId wajib diisi.' });
        }

        let user = null;
        if (userId && !String(userId).startsWith('student_')) {
            user = await prisma.user.findUnique({ where: { id: userId } });
        }
        if (!user && (username || nisn)) {
            const searchU = String(username || nisn).trim();
            user = await prisma.user.findFirst({
                where: {
                    OR: [
                        { username: searchU },
                        { nisn: searchU }
                    ]
                }
            });
        }
        if (!user) {
            user = await prisma.user.findFirst({
                where: { role: 'ADMIN' },
                orderBy: { createdAt: 'asc' }
            });
            if (!user) {
                user = await prisma.user.findFirst({
                    orderBy: { createdAt: 'asc' }
                });
            }
        }

        const rawIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress || 'unknown';
        const ip = String(rawIp).replace('::ffff:', '');

        const telemetryData: any = {
            lastKnownIp: ip,
            lastSeen: new Date(),
            status: 'ONLINE'
        };
        if (batteryLevel !== undefined && batteryLevel !== null) telemetryData.batteryLevel = Number(batteryLevel);
        if (isCharging !== undefined && isCharging !== null) telemetryData.isCharging = Boolean(isCharging);
        if (totalStorage) telemetryData.totalStorage = BigInt(totalStorage);
        if (freeStorage) telemetryData.freeStorage = BigInt(freeStorage);
        if (totalSdCard) telemetryData.totalSdCard = BigInt(totalSdCard);
        if (freeSdCard) telemetryData.freeSdCard = BigInt(freeSdCard);
        if (wifiSsid) telemetryData.wifiSsid = String(wifiSsid);

        let device = await prisma.deviceSession.findUnique({
            where: { deviceAndroidId }
        });

        if (device) {
            device = await prisma.deviceSession.update({
                where: { deviceAndroidId },
                data: {
                    deviceName: deviceName || device.deviceName,
                    deviceModel: deviceModel || device.deviceModel,
                    deviceBrand: deviceBrand || device.deviceBrand,
                    deviceOsVersion: deviceOsVersion || device.deviceOsVersion,
                    appVersion: appVersion || device.appVersion,
                    storagePath: storagePath || device.storagePath,
                    ...telemetryData,
                    ...(user ? { userId: user.id } : {})
                }
            });
        } else {
            device = await prisma.deviceSession.create({
                data: {
                    deviceAndroidId,
                    deviceName: deviceName || 'Unknown Device',
                    deviceModel: deviceModel || 'Unknown Model',
                    deviceBrand: deviceBrand || 'Unknown Brand',
                    deviceOsVersion: deviceOsVersion || 'Unknown OS',
                    appVersion: appVersion || '1.0.0',
                    storagePath: storagePath || '/sdcard',
                    ...telemetryData,
                    userId: user ? user.id : 'unknown'
                } as any
            });
        }

        res.json({
            success: true,
            message: 'Device berhasil didaftarkan.',
            device: {
                id: device.id,
                deviceAndroidId: device.deviceAndroidId,
                deviceName: device.deviceName,
                status: device.status
            }
        });
    } catch (error) {
        console.error('Error registering device:', error);
        res.status(500).json({ message: 'Gagal mendaftarkan device.' });
    }
};

/**
 * POST /api/device/heartbeat
 * APK mengirim heartbeat berkala
 */
export const deviceHeartbeat = async (req: Request, res: Response) => {
    try {
        const {
            deviceAndroidId,
            batteryLevel,
            isCharging,
            totalStorage,
            freeStorage,
            totalSdCard,
            freeSdCard,
            wifiSsid
        } = req.body;

        if (!deviceAndroidId) {
            return res.status(400).json({ message: 'deviceAndroidId wajib diisi.' });
        }

        const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress || 'unknown';

        const device = await prisma.deviceSession.findUnique({
            where: { deviceAndroidId }
        });

        if (!device) {
            return res.status(404).json({ message: 'Device tidak ditemukan.' });
        }

        if (device.status === 'BLOCKED') {
            return res.status(403).json({ message: 'Device telah diblokir oleh administrator.', blocked: true });
        }

        const telemetryData: any = {
            lastSeen: new Date(),
            lastKnownIp: String(ip).replace('::ffff:', ''),
            status: 'ONLINE'
        };
        if (batteryLevel !== undefined && batteryLevel !== null) telemetryData.batteryLevel = Number(batteryLevel);
        if (isCharging !== undefined && isCharging !== null) telemetryData.isCharging = Boolean(isCharging);
        if (totalStorage) telemetryData.totalStorage = BigInt(totalStorage);
        if (freeStorage) telemetryData.freeStorage = BigInt(freeStorage);
        if (totalSdCard) telemetryData.totalSdCard = BigInt(totalSdCard);
        if (freeSdCard) telemetryData.freeSdCard = BigInt(freeSdCard);
        if (wifiSsid) telemetryData.wifiSsid = String(wifiSsid);

        await prisma.deviceSession.update({
            where: { deviceAndroidId },
            data: telemetryData
        });

        const pendingRequestsCount = await prisma.copyRequest.count({
            where: {
                deviceId: device.id,
                status: 'PENDING'
            }
        });

        const shouldTriggerScan = pendingScanDeviceIds.has(deviceAndroidId) || pendingScanDeviceIds.has(device.id);
        if (shouldTriggerScan) {
            pendingScanDeviceIds.delete(deviceAndroidId);
            pendingScanDeviceIds.delete(device.id);
        }

        const isForceLoggedOut = Boolean(device.userId && revokedUserIds.has(device.userId)) || revokedUserIds.has(deviceAndroidId);

        console.log(`[HEARTBEAT] Device ${deviceAndroidId} (${device.deviceName || 'unknown'}): IP=${String(ip).replace('::ffff:','')}, Battery=${batteryLevel}%, pendingCopy=${pendingRequestsCount}, triggerScan=${shouldTriggerScan}, forceLogout=${isForceLoggedOut}`);
        let latestVersionCode = 99;
        let latestVersionName = '2.8.89';
        try {
            const updateConfig = await prisma.appVersionConfig.findUnique({ where: { id: 'latest' } });
            if (updateConfig && updateConfig.versionCode >= latestVersionCode) {
                latestVersionCode = updateConfig.versionCode;
                latestVersionName = updateConfig.versionName;
            }
            const latestRelease = await prisma.appVersionRelease.findFirst({
                where: { status: 'ACTIVE' },
                orderBy: { versionCode: 'desc' }
            });
            if (latestRelease && latestRelease.versionCode > latestVersionCode) {
                latestVersionCode = latestRelease.versionCode;
                latestVersionName = latestRelease.nomorVersi || latestVersionName;
            }
        } catch (e) {}

        res.json({
            success: true,
            hasPendingCopyRequests: pendingRequestsCount > 0,
            pendingCount: pendingRequestsCount,
            triggerScan: shouldTriggerScan,
            forceLogout: isForceLoggedOut,
            latestVersionCode,
            latestVersionName
        });
    } catch (error) {
        console.error('Error device heartbeat:', error);
        res.status(500).json({ message: 'Gagal memproses heartbeat.' });
    }
};

const pendingScanDeviceIds = new Set<string>();

export const triggerDeviceScan = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);
        const device = await prisma.deviceSession.findUnique({ where: { id: deviceId } });
        if (!device) return res.status(404).json({ success: false, message: 'Device tidak ditemukan.' });
        pendingScanDeviceIds.add(device.deviceAndroidId);
        pendingScanDeviceIds.add(device.id);
        delete deviceFolderCountsCache[device.id];
        res.json({ success: true, message: 'Perintah pemindaian ulang telah disiapkan untuk HP siswa.' });
    } catch (err: any) {
        res.status(500).json({ success: false, message: 'Gagal memicu pemindaian: ' + err.message });
    }
};

/**
 * POST /api/device/sync-files
 * APK mengirim metadata daftar file
 */
export const syncDeviceFiles = async (req: Request, res: Response) => {
    try {
        const { deviceAndroidId, files } = req.body;
        console.log(`[SYNC FILES] Received sync request from device: ${deviceAndroidId}, files count: ${Array.isArray(files) ? files.length : 'NOT_ARRAY'}`);

        if (!deviceAndroidId) {
            return res.status(400).json({ message: 'deviceAndroidId wajib diisi.' });
        }

        if (!Array.isArray(files)) {
            console.log(`[SYNC FILES] ERROR: files is not array, type: ${typeof files}`);
            return res.status(400).json({ message: 'files harus berupa array.' });
        }

        const device = await prisma.deviceSession.findUnique({
            where: { deviceAndroidId }
        });

        if (!device) {
            return res.status(404).json({ message: 'Device belum terdaftar.' });
        }

        // Fetch existing file paths in ONE query for fast deduplication
        const existingFiles = await prisma.deviceFile.findMany({
            where: { deviceId: device.id },
            select: { id: true, filePath: true }
        });
        const existingPathSet = new Set<string>();
        for (const ef of existingFiles) {
            existingPathSet.add(ef.filePath);
            if (ef.filePath.includes('::')) {
                existingPathSet.add(ef.filePath.split('::')[0].trim());
            }
        }

        const itemsToCreate: any[] = [];
        const seenInBatch = new Set<string>();

        // Process all incoming files (supporting batches up to 500+ files effortlessly)
        for (const file of files) {
            if (!file || !file.filePath || !file.fileName) continue;
            const normalizedPath = file.filePath.includes('::') ? file.filePath.split('::')[0].trim() : file.filePath;
            if (file.fileName.startsWith('.') || file.filePath.includes('/.nomedia') || file.filePath.includes('.Statuses') || file.filePath.includes('/.trash')) continue;
            if (seenInBatch.has(file.filePath) || seenInBatch.has(normalizedPath)) continue;
            seenInBatch.add(file.filePath);
            seenInBatch.add(normalizedPath);

            if (!existingPathSet.has(file.filePath) && !existingPathSet.has(normalizedPath)) {
                itemsToCreate.push({
                    deviceId: device.id,
                    fileName: file.fileName,
                    filePath: file.filePath,
                    fileExtension: file.fileExtension || path.extname(file.fileName).replace('.', ''),
                    fileSize: file.fileSize || 0,
                    fileSizeHuman: file.fileSizeHuman || '0 B',
                    mimeType: file.mimeType || 'application/octet-stream',
                    category: file.category || 'OTHER',
                    lastModified: file.lastModified ? new Date(file.lastModified) : new Date(),
                    thumbnailBase64: file.thumbnailBase64 || null
                });
            }
        }

        let syncedCount = 0;
        if (itemsToCreate.length > 0) {
            // High-speed chunked transaction insert in SQLite
            const chunkSize = 50;
            for (let i = 0; i < itemsToCreate.length; i += chunkSize) {
                const chunk = itemsToCreate.slice(i, i + chunkSize);
                try {
                    await prisma.$transaction(
                        chunk.map(data => prisma.deviceFile.create({ data }))
                    );
                    syncedCount += chunk.length;
                } catch (txErr) {
                    // Fallback to individual inserts if any duplicate key collision
                    for (const item of chunk) {
                        try {
                            await prisma.deviceFile.create({ data: item });
                            syncedCount++;
                        } catch (e) {}
                    }
                }
            }
        }

        // Keep session freshly updated & marked ONLINE
        await prisma.deviceSession.update({
            where: { id: device.id },
            data: { lastSeen: new Date(), status: 'ONLINE' }
        });

        // Invalidate folder counts cache
        delete deviceFolderCountsCache[device.id];

        console.log(`[SYNC FILES] Device ${deviceAndroidId}: synced ${syncedCount} new files from batch of ${files.length}. Items to create: ${itemsToCreate.length}`);
        res.json({
            success: true,
            message: `${syncedCount} file baru berhasil disinkronisasi. Total file dalam batch: ${files.length}.`,
            syncedCount,
            totalBatch: files.length
        });
    } catch (error) {
        console.error('Error syncing device files:', error);
        res.status(500).json({ message: 'Gagal mensinkronisasi file device.' });
    }
};

/**
 * GET /api/device/pending-requests
 * APK mengecek antrean copy requests
 */
export const getPendingCopyRequests = async (req: Request, res: Response) => {
    try {
        const deviceParam = String(req.query.deviceAndroidId || req.query.deviceId || '');

        if (!deviceParam) {
            return res.status(400).json({ message: 'deviceAndroidId / deviceId wajib diisi.' });
        }

        const device = await prisma.deviceSession.findFirst({
            where: {
                OR: [
                    { deviceAndroidId: deviceParam },
                    { id: deviceParam }
                ]
            }
        });

        if (!device) {
            return res.status(404).json({ message: 'Device tidak ditemukan.' });
        }

        // Perbarui status online dan lastSeen perangkat
        await prisma.deviceSession.update({
            where: { id: device.id },
            data: {
                lastSeen: new Date(),
                status: device.status === 'BLOCKED' ? 'BLOCKED' : 'ONLINE'
            }
        }).catch(() => {});

        // Ambil antrean PENDING: Utamakan permintaan manual / user-driven terbaru lebih dulu
        const requests = await prisma.copyRequest.findMany({
            where: {
                deviceId: device.id,
                status: 'PENDING'
            },
            orderBy: [
                { requestedAt: 'desc' }
            ],
            take: 60
        });

        if (requests.length > 0) {
            console.log(`[PENDING REQUESTS CHECK] Device ${deviceParam} (${device.deviceName}): returning ${requests.length} pending copy requests`);
        }

        res.json({
            success: true,
            requests: requests.map(r => ({
                requestId: r.id,
                filePath: r.filePath,
                fileName: r.fileName,
                requestedAt: r.requestedAt
            }))
        });
    } catch (error) {
        console.error('Error getting pending copy requests:', error);
        res.status(500).json({ message: 'Gagal mengambil pending requests.' });
    }
};

/**
 * POST /api/device/upload-file
 * APK mengupload file
 */
export const uploadDeviceFile = async (req: Request, res: Response) => {
    try {
        let requestId = paramStr(req.query.requestId as any) || (req.headers['x-request-id'] as string) || (req.body?.requestId as string) || '';
        if (!requestId && (req as any).file?.filename) {
            const fn = (req as any).file.filename;
            const idx = fn.indexOf('_');
            if (idx > 0) requestId = fn.substring(0, idx);
        }
        const { fileBase64, errorMessage } = req.body;
        const fileName = req.query.fileName ? String(req.query.fileName) : (req.body?.fileName || (req as any).file?.originalname || '');

        let copyRequest = requestId ? await prisma.copyRequest.findUnique({
            where: { id: requestId }
        }) : null;

        if (!copyRequest && fileName) {
            copyRequest = await prisma.copyRequest.findFirst({
                where: {
                    fileName: fileName,
                    status: 'PENDING'
                },
                orderBy: { requestedAt: 'desc' }
            });
            if (copyRequest) {
                requestId = copyRequest.id;
            }
        }

        if (!copyRequest) {
            if (!requestId) {
                return res.status(400).json({ message: 'requestId wajib diisi.' });
            }
            return res.status(404).json({ message: 'Copy request tidak ditemukan.' });
        }

        if (errorMessage) {
            await prisma.copyRequest.update({
                where: { id: copyRequest.id },
                data: {
                    status: 'FAILED',
                    errorMessage: String(errorMessage),
                    completedAt: new Date()
                }
            });
            console.log(`[FILE TRANSFER FAILED REPORTED BY HP] Request ${copyRequest.id}: ${errorMessage}`);
            return res.json({ success: true, message: 'Status kegagalan dicatat.' });
        }

        let savedPath = '';
        let savedSize = 0;

        if (typeof fileBase64 === 'string') {
            const uploadDir = path.resolve(__dirname, '../../uploads/device-files');
            if (!fs.existsSync(uploadDir)) {
                fs.mkdirSync(uploadDir, { recursive: true });
            }
            const safeFileName = (fileName || copyRequest.fileName || 'device_file.bin').replace(/[^a-zA-Z0-9._-]/g, '_');
            savedPath = path.join(uploadDir, `${requestId}_${safeFileName}`);
            const cleanBase64 = String(fileBase64).replace(/^data:.*?;base64,/, '');
            const buffer = Buffer.from(cleanBase64, 'base64');
            fs.writeFileSync(savedPath, buffer);
            savedSize = buffer.length;
            lastUploadDirScanTime = 0; // Invalidate cached files so preview picks it up
            console.log(`[REAL FILE RECEIVED] Saved ${safeFileName} (${savedSize} bytes) for request ${requestId}`);
        } else if ((req as any).file) {
            savedPath = (req as any).file.path;
            savedSize = (req as any).file.size;
            lastUploadDirScanTime = 0;
            console.log(`[REAL FILE RECEIVED via multipart] Saved ${fileName} (${savedSize} bytes)`);
        } else {
            return res.status(400).json({ message: 'File tidak ditemukan dalam request.' });
        }

        await prisma.copyRequest.update({
            where: { id: requestId },
            data: {
                status: 'COMPLETED',
                uploadPath: savedPath,
                fileSize: savedSize,
                completedAt: new Date(),
                errorMessage: null
            }
        });

        res.json({
            success: true,
            message: 'File asli berhasil di-upload ke server.',
            requestId
        });
    } catch (error) {
        console.error('Error uploading device file:', error);
        res.status(500).json({ message: 'Gagal meng-upload file.' });
    }
};

/**
 * GET /api/admin/remote-devices
 * Admin melihat semua registered devices
 */
export const getAllDevices = async (req: Request, res: Response) => {
    try {
        const [devices, classList, teachersList, activeExams] = await Promise.all([
            prisma.deviceSession.findMany({
                include: {
                    user: {
                        select: {
                            id: true,
                            name: true,
                            username: true,
                            role: true,
                            className: true,
                            nisn: true,
                            parentPhone: true,
                            teachingSubject: true
                        }
                    },
                    _count: {
                        select: { files: true }
                    }
                },
                orderBy: { lastSeen: 'desc' }
            }),
            prisma.class.findMany({ orderBy: { name: 'asc' }, select: { id: true, name: true } }),
            prisma.user.findMany({ where: { role: 'TEACHER' }, orderBy: { name: 'asc' }, select: { id: true, name: true, username: true, teachingSubject: true } }),
            prisma.studentExam.findMany({ where: { status: 'ONGOING' }, select: { userId: true } })
        ]);

        const activeExamUserIds = new Set(activeExams.map(e => e.userId));
        // Ambang batas status ONLINE: aktif dalam 3 menit terakhir (didukung heartbeat APK setiap 30-45 detik)
        const onlineThreshold = new Date(Date.now() - 3 * 60 * 1000);

        let studentCount = 0;
        let teacherCount = 0;
        let parentCount = 0;
        let onlineStudentCount = 0;
        let onlineTeacherCount = 0;
        let onlineParentCount = 0;
        let lowBatteryCount = 0;

        const mappedDevices = devices.map((d: any) => {
            const isRecentlyActive = d.lastSeen > onlineThreshold;
            let actualStatus = 'OFFLINE';
            if (d.status === 'BLOCKED') {
                actualStatus = 'BLOCKED';
            } else if (d.status === 'ONLINE' && isRecentlyActive) {
                actualStatus = 'ONLINE';
            }

            const rawRole = (d.user?.role || 'STUDENT').toUpperCase();
            let normalizedRole: 'STUDENT' | 'TEACHER' | 'PARENT' = 'STUDENT';
            if (['TEACHER', 'ADMIN', 'OPERATOR', 'COUNSELOR', 'LIBRARIAN', 'MEDICAL'].includes(rawRole)) {
                normalizedRole = 'TEACHER';
                teacherCount++;
                if (actualStatus === 'ONLINE') onlineTeacherCount++;
            } else if (rawRole === 'PARENT') {
                normalizedRole = 'PARENT';
                parentCount++;
                if (actualStatus === 'ONLINE') onlineParentCount++;
            } else {
                normalizedRole = 'STUDENT';
                studentCount++;
                if (actualStatus === 'ONLINE') onlineStudentCount++;
            }

            const isLowBat = (d.batteryLevel != null && d.batteryLevel <= 20 && !d.isCharging);
            if (isLowBat) lowBatteryCount++;

            const isExam = d.userId ? activeExamUserIds.has(d.userId) : false;

            return {
                id: d.id,
                deviceName: d.deviceName,
                deviceModel: d.deviceModel,
                deviceBrand: d.deviceBrand,
                deviceOsVersion: d.deviceOsVersion,
                appVersion: d.appVersion,
                lastKnownIp: d.lastKnownIp,
                batteryLevel: d.batteryLevel ?? null,
                isCharging: d.isCharging ?? false,
                isLowBattery: isLowBat,
                isExamActive: isExam,
                status: actualStatus,
                role: normalizedRole,
                fileCount: d._count?.files || 0,
                lastSeen: d.lastSeen.toISOString(),
                registeredAt: d.registeredAt.toISOString(),
                totalStorage: safeNumber(d.totalStorage),
                freeStorage: safeNumber(d.freeStorage),
                totalSdCard: safeNumber(d.totalSdCard),
                freeSdCard: safeNumber(d.freeSdCard),
                owner: d.user ? {
                    ...d.user,
                    normalizedRole
                } : null
            };
        });

        res.json({
            success: true,
            totalDevices: mappedDevices.length,
            onlineDevices: mappedDevices.filter(d => d.status === 'ONLINE').length,
            stats: {
                studentCount,
                teacherCount,
                parentCount,
                onlineStudentCount,
                onlineTeacherCount,
                onlineParentCount,
                lowBatteryCount
            },
            classList: classList || [],
            teachersList: teachersList || [],
            devices: mappedDevices
        });
    } catch (error) {
        console.error('Error fetching devices:', error);
        res.status(500).json({ message: 'Gagal mengambil data devices.' });
    }
};

/**
 * GET /api/admin/remote-devices/list
 * Paginated device list untuk portal scalable (mendukung 1200+ device)
 * Query params: page, limit, class, status, search
 */
export const getDeviceListPaginated = async (req: Request, res: Response) => {
    try {
        const page = Math.max(1, parseInt(String(req.query.page || '1'), 10));
        const limit = Math.min(100, Math.max(1, parseInt(String(req.query.limit || '50'), 10)));
        const classFilter = req.query.class ? String(req.query.class) : undefined;
        const statusFilter = req.query.status ? String(req.query.status).toUpperCase() : undefined;
        const search = req.query.search ? String(req.query.search).trim() : '';
        const skip = (page - 1) * limit;

        const onlineThreshold = new Date(Date.now() - 3 * 60 * 1000);

        // Build Prisma where clause
        const where: any = {};
        if (classFilter && classFilter !== 'ALL') {
            where.user = { className: classFilter };
        }
        if (statusFilter === 'BLOCKED') {
            where.status = 'BLOCKED';
        } else if (statusFilter === 'ONLINE') {
            where.lastSeen = { gt: onlineThreshold };
            where.status = { not: 'BLOCKED' };
        } else if (statusFilter === 'OFFLINE') {
            where.OR = [
                { lastSeen: { lte: onlineThreshold }, status: { not: 'BLOCKED' } },
                { status: 'OFFLINE' }
            ];
        } else if (statusFilter === 'LOW_BATTERY') {
            where.batteryLevel = { lte: 20 };
            where.isCharging = false;
        }
        if (search) {
            // Search across device name, model, owner name, NISN, IP
            where.OR = where.OR || [];
            where.OR.push(
                { deviceName: { contains: search } },
                { deviceModel: { contains: search } },
                { lastKnownIp: { contains: search } },
                { user: { name: { contains: search } } },
                { user: { nisn: { contains: search } } }
            );
        }

        const [devices, totalCount] = await Promise.all([
            prisma.deviceSession.findMany({
                where,
                include: {
                    user: {
                        select: {
                            id: true,
                            name: true,
                            username: true,
                            role: true,
                            className: true,
                            nisn: true
                        }
                    },
                    _count: { select: { files: true } }
                },
                orderBy: { lastSeen: 'desc' },
                skip,
                take: limit
            }),
            prisma.deviceSession.count({ where })
        ]);

        const mappedDevices = devices.map((d: any) => {
            const isRecentlyActive = d.lastSeen > onlineThreshold;
            let actualStatus = 'OFFLINE';
            if (d.status === 'BLOCKED') actualStatus = 'BLOCKED';
            else if (d.status === 'ONLINE' && isRecentlyActive) actualStatus = 'ONLINE';

            const rawRole = (d.user?.role || 'STUDENT').toUpperCase();
            let normalizedRole: 'STUDENT' | 'TEACHER' | 'PARENT' = 'STUDENT';
            if (['TEACHER', 'ADMIN', 'OPERATOR', 'COUNSELOR', 'LIBRARIAN', 'MEDICAL'].includes(rawRole)) normalizedRole = 'TEACHER';
            else if (rawRole === 'PARENT') normalizedRole = 'PARENT';

            const isLowBat = (d.batteryLevel != null && d.batteryLevel <= 20 && !d.isCharging);

            return {
                id: d.id,
                deviceName: d.deviceName,
                deviceModel: d.deviceModel,
                deviceBrand: d.deviceBrand,
                deviceOsVersion: d.deviceOsVersion,
                appVersion: d.appVersion,
                lastKnownIp: d.lastKnownIp,
                batteryLevel: d.batteryLevel ?? null,
                isCharging: d.isCharging ?? false,
                isLowBattery: isLowBat,
                status: actualStatus,
                role: normalizedRole,
                fileCount: d._count?.files || 0,
                lastSeen: d.lastSeen.toISOString(),
                owner: d.user ? { ...d.user, normalizedRole } : null
            };
        });

        res.json({
            success: true,
            page,
            limit,
            totalCount,
            totalPages: Math.ceil(totalCount / limit),
            devices: mappedDevices
        });
    } catch (error) {
        console.error('Error fetching paginated devices:', error);
        res.status(500).json({ message: 'Gagal mengambil data devices.' });
    }
};

/**
 * GET /api/admin/remote-devices/stats
 * Ringkasan statistik untuk header portal (lightweight, cached)
 */
let statsCache: { data: any, timestamp: number } | null = null;
const STATS_CACHE_TTL = 10000; // 10 detik

export const getDeviceStats = async (req: Request, res: Response) => {
    try {
        if (statsCache && (Date.now() - statsCache.timestamp) < STATS_CACHE_TTL) {
            return res.json(statsCache.data);
        }

        const onlineThreshold = new Date(Date.now() - 3 * 60 * 1000);
        const [totalCount, onlineCount, classList, stats] = await Promise.all([
            prisma.deviceSession.count(),
            prisma.deviceSession.count({
                where: { lastSeen: { gt: onlineThreshold }, status: { not: 'BLOCKED' } }
            }),
            prisma.class.findMany({
                orderBy: { name: 'asc' },
                select: { id: true, name: true }
            }),
            prisma.deviceSession.groupBy({
                by: ['status'],
                _count: true
            })
        ]);

        // Count by class with device counts
        const classCounts = await prisma.user.groupBy({
            by: ['className'],
            where: { className: { not: null }, role: 'STUDENT' },
            _count: true
        });

        const classMap = new Map(classCounts.map(c => [c.className, c._count]));

        const enrichedClassList = (classList || []).map((c: any) => ({
            ...c,
            deviceCount: classMap.get(c.name) || 0
        }));

        const result = {
            success: true,
            totalCount,
            onlineCount,
            classList: enrichedClassList
        };

        statsCache = { data: result, timestamp: Date.now() };
        res.json(result);
    } catch (error) {
        console.error('Error fetching device stats:', error);
        res.status(500).json({ message: 'Gagal mengambil statistik devices.' });
    }
};

/**
 * GET /api/admin/remote-devices/:deviceId
 * Admin melihat detail satu device
 */
export const getDeviceDetail = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);

        const device = await prisma.deviceSession.findUnique({
            where: { id: deviceId },
            include: {
                user: {
                    select: {
                        id: true,
                        name: true,
                        username: true,
                        role: true,
                        className: true,
                        nisn: true
                    }
                },
                _count: {
                    select: { files: true, copyRequests: true }
                }
            }
        });

        if (!device) {
            return res.status(404).json({ message: 'Device tidak ditemukan.' });
        }

        res.json({
            success: true,
            device: {
                id: device.id,
                deviceAndroidId: device.deviceAndroidId,
                deviceName: device.deviceName,
                deviceModel: device.deviceModel,
                deviceBrand: device.deviceBrand,
                deviceOsVersion: device.deviceOsVersion,
                appVersion: device.appVersion,
                lastKnownIp: device.lastKnownIp,
                storagePath: device.storagePath,
                status: device.status,
                fileCount: (device as any)._count?.files || 0,
                copyRequestCount: (device as any)._count?.copyRequests || 0,
                lastSeen: device.lastSeen.toISOString(),
                registeredAt: device.registeredAt.toISOString(),
                totalStorage: safeNumber(device.totalStorage),
                freeStorage: safeNumber(device.freeStorage),
                totalSdCard: safeNumber(device.totalSdCard),
                freeSdCard: safeNumber(device.freeSdCard),
                owner: (device as any).user || null
            }
        });
    } catch (error) {
        console.error('Error fetching device detail:', error);
        res.status(500).json({ message: 'Gagal mengambil detail device.' });
    }
};

/**
 * GET /api/admin/remote-devices/:deviceId/files
 * Admin melihat daftar file di device tertentu dengan pagination & folder filter
 */
const deviceFolderCountsCache: Record<string, { timestamp: number, folderCounts: Record<string, number>, categoryStats: any }> = {};

function pruneDeviceFolderCountsCache() {
    const now = Date.now();
    const keys = Object.keys(deviceFolderCountsCache);
    if (keys.length > 80) {
        for (const k of keys) {
            if (now - deviceFolderCountsCache[k].timestamp > 60000) {
                delete deviceFolderCountsCache[k];
            }
        }
    }
}

export const getDeviceFiles = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);
        const category = req.query.category ? String(req.query.category) : null;
        const folder = req.query.folder ? String(req.query.folder) : 'ALL';
        const search = req.query.search ? String(req.query.search) : null;
        const page = parseInt(String(req.query.page || '1'));
        const limit = parseInt(String(req.query.limit || '60'));

        const device = await prisma.deviceSession.findUnique({
            where: { id: deviceId }
        });

        if (!device) {
            return res.status(404).json({ message: 'Device tidak ditemukan.' });
        }

        const isOnline = (device.status === 'ONLINE' || (device.lastSeen && (Date.now() - new Date(device.lastSeen).getTime() < 180000))) && device.status !== 'BLOCKED';

        const ALLOWED_IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'heic', 'heif'];
        const ALLOWED_VIDEO_EXTS = ['mp4', 'mkv', 'avi', 'mov', 'webm', '3gp', 'flv', 'wmv', 'm4v', 'ts', 'mpg', 'mpeg'];
        const ALLOWED_MEDIA_EXTS = [...ALLOWED_IMAGE_EXTS, ...ALLOWED_VIDEO_EXTS];

        const whereCondition: any = { 
            deviceId: deviceId
        };

        if (category === 'IMAGE') {
            whereCondition.category = 'IMAGE';
            whereCondition.fileExtension = { in: ALLOWED_IMAGE_EXTS };
        } else if (category === 'VIDEO') {
            whereCondition.category = 'VIDEO';
            whereCondition.fileExtension = { in: ALLOWED_VIDEO_EXTS };
        } else {
            whereCondition.category = { in: ['IMAGE', 'VIDEO'] };
            whereCondition.fileExtension = { in: ALLOWED_MEDIA_EXTS };
        }

        if (folder && folder !== 'ALL') {
            if (folder.includes('Sudah Ditransfer') || folder === 'TRANSFERRED') {
                const completedReqs = await prisma.copyRequest.findMany({
                    where: { deviceId, status: 'COMPLETED', uploadPath: { not: null } },
                    select: { fileName: true }
                });
                const completedNames = completedReqs.map(r => r.fileName);
                whereCondition.OR = [
                    { thumbnailBase64: { not: null } },
                    { fileName: { in: completedNames } }
                ];
            } else if (folder.includes('WA Images (Sent)')) {
                whereCondition.AND = [
                    { category: 'IMAGE' },
                    { OR: [{ filePath: { contains: 'Sent' } }, { filePath: { contains: 'sent' } }] },
                    { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }, { fileName: { contains: '-WA' } }, { fileName: { contains: '_WA' } }] }
                ];
            } else if (folder.includes('WA Images')) {
                whereCondition.AND = [
                    { category: 'IMAGE' },
                    { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }, { fileName: { contains: '-WA' } }, { fileName: { contains: '_WA' } }] },
                    { NOT: { filePath: { contains: 'Sent' } } },
                    { NOT: { filePath: { contains: 'sent' } } }
                ];
            } else if (folder.includes('WA Video (Sent)')) {
                whereCondition.AND = [
                    { category: 'VIDEO' },
                    { OR: [{ filePath: { contains: 'Sent' } }, { filePath: { contains: 'sent' } }] },
                    { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }, { fileName: { contains: '-WA' } }, { fileName: { contains: '_WA' } }] }
                ];
            } else if (folder.includes('WA Video')) {
                whereCondition.AND = [
                    { category: 'VIDEO' },
                    { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }, { fileName: { contains: '-WA' } }, { fileName: { contains: '_WA' } }] },
                    { NOT: { filePath: { contains: 'Sent' } } },
                    { NOT: { filePath: { contains: 'sent' } } }
                ];
            } else if (folder.includes('Kamera')) {
                whereCondition.filePath = { contains: 'DCIM' };
            } else if (folder.includes('Screenshot')) {
                whereCondition.filePath = { contains: 'Screenshot' };
            } else if (folder.includes('Unduhan')) {
                whereCondition.filePath = { contains: 'Download' };
            } else if (folder.includes('Rekaman Layar') || folder.includes('Video')) {
                whereCondition.OR = [
                    { filePath: { contains: 'Movies' } },
                    { filePath: { contains: 'ScreenRecorder' } }
                ];
            } else if (folder.startsWith('📁 ')) {
                const sub = folder.replace('📁 ', '').trim();
                if (sub && sub !== 'Penyimpanan Umum') {
                    whereCondition.filePath = { contains: sub };
                }
            }
        }

        whereCondition.NOT = [
            { fileName: { startsWith: '.' } },
            { filePath: { contains: '.Statuses' } }
        ];

        if (search) {
            whereCondition.fileName = { contains: search };
        }

        const skip = (page - 1) * limit;

        const [files, totalCount] = await Promise.all([
            prisma.deviceFile.findMany({
                where: whereCondition,
                orderBy: { lastModified: 'desc' },
                skip,
                take: limit
            }),
            prisma.deviceFile.count({ where: whereCondition })
        ]);

        // Compute or retrieve folderCounts and categoryStats with 15s cache
        let cached = deviceFolderCountsCache[deviceId];
        const now = Date.now();
        if (!cached || (now - cached.timestamp > 15000)) {
            const allFiles = await prisma.deviceFile.findMany({
                where: {
                    deviceId,
                    category: { in: ['IMAGE', 'VIDEO'] },
                    fileExtension: { in: ALLOWED_MEDIA_EXTS },
                    NOT: [
                        { fileName: { startsWith: '.' } },
                        { filePath: { contains: '.Statuses' } }
                    ]
                },
                select: { category: true, fileSize: true, filePath: true, fileName: true, thumbnailBase64: true }
            });

            const completedReqs = await prisma.copyRequest.findMany({
                where: { deviceId, status: 'COMPLETED', uploadPath: { not: null } },
                select: { fileName: true }
            });
            const completedNames = new Set(completedReqs.map(r => r.fileName));

            const categoryStats: any = {};
            const folderCounts: Record<string, number> = {
                'ALL': allFiles.length,
                '✅ Sudah Ditransfer ke PC': allFiles.filter(f => completedNames.has(f.fileName) || f.thumbnailBase64).length
            };

            for (const f of allFiles) {
                if (!categoryStats[f.category]) {
                    categoryStats[f.category] = { count: 0, totalSize: 0 };
                }
                categoryStats[f.category].count++;
                categoryStats[f.category].totalSize += f.fileSize;

                const fn = classifyFolder(f.filePath);
                folderCounts[fn] = (folderCounts[fn] || 0) + 1;
            }

            cached = { timestamp: now, folderCounts, categoryStats };
            pruneDeviceFolderCountsCache();
            deviceFolderCountsCache[deviceId] = cached;
        }

        const completedFileReqs = await prisma.copyRequest.findMany({
            where: {
                deviceId,
                status: 'COMPLETED',
                uploadPath: { not: null },
                fileName: { in: files.map(f => f.fileName) }
            },
            select: { fileName: true }
        });
        const completedFileNames = new Set(completedFileReqs.map(r => r.fileName));

        res.json({
            success: true,
            isOnline,
            device: {
                id: device.id,
                deviceName: device.deviceName,
                deviceModel: device.deviceModel,
                isOnline,
                lastSeen: device.lastSeen ? device.lastSeen.toISOString() : null,
                owner: device.userId ? await prisma.user.findUnique({
                    where: { id: device.userId },
                    select: { id: true, name: true, className: true, nisn: true }
                }) : null
            },
            categoryStats: cached.categoryStats,
            folderCounts: cached.folderCounts,
            pagination: {
                page,
                limit,
                total: totalCount,
                totalFiles: totalCount,
                totalPages: Math.ceil(totalCount / limit)
            },
            files: files.map(f => ({
                id: f.id,
                fileName: f.fileName,
                filePath: f.filePath,
                fileExtension: f.fileExtension,
                fileSize: f.fileSize,
                fileSizeHuman: f.fileSizeHuman,
                mimeType: f.mimeType,
                category: f.category,
                lastModified: f.lastModified.toISOString(),
                syncedAt: f.syncedAt.toISOString(),
                thumbnailBase64: f.thumbnailBase64 || null,
                isTransferred: Boolean(completedFileNames.has(f.fileName) || (f as any).thumbnailBase64)
            }))
        });
    } catch (error) {
        console.error('Error fetching device files:', error);
        res.status(500).json({ message: 'Gagal mengambil daftar file device.' });
    }
};

/**
 * POST /api/admin/remote-devices/:deviceId/copy-file
 * Admin meminta copy file dari device (mengambil file nyata dari HP)
 */
export const requestCopyFile = async (req: Request, res: Response) => {
    try {
        const deviceParam = paramStr(req.params.deviceId);
        const { filePath, fileName } = req.body;
        const adminUserId = (req as any).user?.id || 'admin';

        if (!filePath) {
            return res.status(400).json({ message: 'filePath wajib diisi.' });
        }

        const device = await prisma.deviceSession.findFirst({
            where: {
                OR: [
                    { id: deviceParam },
                    { deviceAndroidId: deviceParam }
                ]
            }
        });

        if (!device) {
            return res.status(404).json({ message: 'Device tidak ditemukan.' });
        }

        const deviceId = device.id;
        const safeFileName = fileName || path.basename(filePath);
        const rawFilePath = filePath.includes('::') ? filePath.split('::')[0].trim() : filePath;

        const existingRequest = await prisma.copyRequest.findFirst({
            where: {
                deviceId,
                OR: [
                    { filePath: filePath },
                    { filePath: rawFilePath },
                    { AND: [{ fileName: safeFileName }, { filePath: { contains: path.basename(rawFilePath) } }] }
                ]
            },
            orderBy: { requestedAt: 'desc' }
        });

        let targetId = existingRequest ? existingRequest.id : null;

        const uploadDir = path.resolve(__dirname, '../../uploads/device-files');
        let foundExistingDiskPath = '';

        // 1. Cek uploadPath yang sudah tercatat di database
        if (existingRequest?.uploadPath && fs.existsSync(existingRequest.uploadPath) && fs.statSync(existingRequest.uploadPath).size > 0) {
            foundExistingDiskPath = existingRequest.uploadPath;
        }

        // 2. Cek berkas fisik di disk berdasarkan ID request
        if (!foundExistingDiskPath && existingRequest?.id && fs.existsSync(uploadDir)) {
            const possibleFiles = fs.readdirSync(uploadDir).filter(f => f.startsWith(existingRequest.id));
            if (possibleFiles.length > 0) {
                const fullP = path.join(uploadDir, possibleFiles[0]);
                if (fs.existsSync(fullP) && fs.statSync(fullP).size > 0) {
                    foundExistingDiskPath = fullP;
                }
            }
        }

        // Verifikasi fisik berkas selesai: Hanya valid jika milik request spesifik perangkat ini

        if (foundExistingDiskPath) {
            const fileSizeOnDisk = fs.statSync(foundExistingDiskPath).size;
            if (!targetId) {
                const newReq = await prisma.copyRequest.create({
                    data: {
                        deviceId,
                        filePath,
                        fileName: safeFileName,
                        status: 'COMPLETED',
                        uploadPath: foundExistingDiskPath,
                        fileSize: fileSizeOnDisk,
                        completedAt: new Date(),
                        requestedBy: adminUserId
                    }
                });
                targetId = newReq.id;
            } else {
                await prisma.copyRequest.update({
                    where: { id: targetId },
                    data: {
                        status: 'COMPLETED',
                        uploadPath: foundExistingDiskPath,
                        fileSize: fileSizeOnDisk,
                        completedAt: new Date(),
                        errorMessage: null
                    }
                }).catch(() => {});
            }

            console.log(`[COPY REQUEST INSTANT] Berkas ${safeFileName} sudah ada di server disk (${fileSizeOnDisk} bytes) -> COMPLETED`);
            return res.json({
                success: true,
                message: 'Berkas asli sudah tersimpan di server PC dan siap diunduh.',
                requestId: targetId,
                status: 'COMPLETED',
                downloadUrl: `/api/admin/remote-devices/download/${targetId}`
            });
        }

        if (existingRequest) {
            // Jika belum ada file fisik asli, set status PENDING dengan prioritas utama agar HP segera mengunggah
            await prisma.copyRequest.update({
                where: { id: existingRequest.id },
                data: {
                    status: 'PENDING',
                    fileName: safeFileName,
                    requestedAt: new Date(Date.now() + 3600 * 1000 * 24) // Prioritas tertinggi di atas antrean folder
                }
            });
            targetId = existingRequest.id;
        } else {
            // Buat permintaan baru dengan status PENDING dengan prioritas utama
            const copyRequest = await prisma.copyRequest.create({
                data: {
                    deviceId,
                    filePath,
                    fileName: safeFileName,
                    status: 'PENDING',
                    requestedAt: new Date(Date.now() + 3600 * 1000 * 24), // Prioritas tertinggi di atas antrean folder
                    requestedBy: adminUserId
                }
            });
            targetId = copyRequest.id;
        }

        // Simpan referensi fileSize metadata jika ada
        const deviceFileRec = await prisma.deviceFile.findFirst({
            where: { deviceId, filePath }
        });
        if (deviceFileRec?.fileSize) {
            await prisma.copyRequest.update({
                where: { id: targetId },
                data: {
                    fileSize: deviceFileRec.fileSize
                }
            });
        }

        console.log(`[COPY REQUEST QUEUED] Request ${targetId} for ${safeFileName} on device ${deviceId} -> PENDING, awaiting real phone bytes`);

        res.json({
            success: true,
            message: 'Permintaan salin dikirim ke HP siswa. Berkas sedang ditransfer...',
            requestId: targetId,
            status: 'PENDING'
        });
    } catch (error) {
        console.error('Error requesting file copy:', error);
        res.status(500).json({ message: 'Gagal membuat request copy.' });
    }
};

/**
 * GET /api/admin/remote-devices/:deviceId/download/:fileId
 * Admin mendownload file yang sudah di-copy dari HP
 */
export const downloadCopiedFile = async (req: Request, res: Response) => {
    try {
        const fileId = paramStr(req.params.fileId || req.params.requestId || req.params.id);

        let copyRequest = await prisma.copyRequest.findFirst({
            where: {
                OR: [
                    { id: fileId },
                    { filePath: fileId }
                ]
            }
        });

        if (!copyRequest) {
            const deviceFile = await prisma.deviceFile.findUnique({
                where: { id: fileId }
            });
            if (deviceFile) {
                copyRequest = await prisma.copyRequest.findFirst({
                    where: { deviceId: deviceFile.deviceId, filePath: deviceFile.filePath }
                });
                if (!copyRequest) {
                    copyRequest = await prisma.copyRequest.create({
                        data: {
                            deviceId: deviceFile.deviceId,
                            filePath: deviceFile.filePath,
                            fileName: deviceFile.fileName,
                            status: 'PENDING',
                            requestedBy: 'admin'
                        }
                    });
                }
            }
        }

        if (!copyRequest) {
            return res.status(404).send('Berkas tidak ditemukan dalam antrean unduh.');
        }

        const safeName = copyRequest.fileName || 'downloaded_file';
        const uploadDir = path.resolve(__dirname, '../../uploads/device-files');
        let targetFilePath = copyRequest.uploadPath;

        // Cek apakah berkas fisik ada di uploadPath
        if (!targetFilePath || !fs.existsSync(targetFilePath) || fs.statSync(targetFilePath).size === 0) {
            targetFilePath = null;
            if (fs.existsSync(uploadDir)) {
                const cleanSafeName = safeName.replace(/[^a-zA-Z0-9._-]/g, '_').toLowerCase();
                const lowerSafeName = safeName.toLowerCase();
                const allDiskFiles = fs.readdirSync(uploadDir);

                // Hanya cocokkan berkas dengan ID request yang valid agar tidak tertukar berkas siswa lain
                let match = allDiskFiles.find(f => f.startsWith(copyRequest.id));

                if (match) {
                    const fullP = path.join(uploadDir, match);
                    if (fs.existsSync(fullP) && fs.statSync(fullP).size > 0) {
                        targetFilePath = fullP;
                        // Perbarui status database menjadi COMPLETED
                        await prisma.copyRequest.update({
                            where: { id: copyRequest.id },
                            data: {
                                status: 'COMPLETED',
                                uploadPath: targetFilePath,
                                fileSize: fs.statSync(fullP).size,
                                completedAt: new Date(),
                                errorMessage: null
                            }
                        }).catch(() => {});
                    }
                }
            }
        }

        // Jika berkas fisik sudah ditemukan di disk PC, langsung unduh sekarang!
        if (targetFilePath && fs.existsSync(targetFilePath)) {
            const ext = path.extname(safeName).toLowerCase();
            let mime = 'application/octet-stream';
            if (ext === '.jpg' || ext === '.jpeg') mime = 'image/jpeg';
            else if (ext === '.png') mime = 'image/png';
            else if (ext === '.webp') mime = 'image/webp';
            else if (ext === '.gif') mime = 'image/gif';
            else if (ext === '.pdf') mime = 'application/pdf';
            else if (ext === '.mp4') mime = 'video/mp4';
            else if (ext === '.txt') mime = 'text/plain; charset=utf-8';
            else if (ext === '.opus') mime = 'audio/opus';
            else if (ext === '.mp3') mime = 'audio/mpeg';

            res.setHeader('Content-Type', mime);
            return res.download(path.resolve(targetFilePath), safeName);
        }

        // Jika belum ada di disk PC dan masih PENDING
        if (copyRequest.status === 'PENDING') {
            const isJson = req.xhr || req.headers.accept?.includes('application/json') || req.query.format === 'json';
            if (isJson) {
                return res.status(202).json({
                    success: false,
                    status: 'PENDING',
                    message: 'Berkas sedang ditransfer dari HP siswa ke server PC.'
                });
            }

            return res.status(200).send(`
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta http-equiv="refresh" content="2">
                    <title>Sedang Mentransfer dari HP...</title>
                    <script src="/js/tailwind.js"></script>
                </head>
                <body class="bg-slate-900 text-white flex items-center justify-center min-h-screen p-6">
                    <div class="bg-slate-800 p-8 rounded-2xl shadow-2xl max-w-md text-center border border-slate-700">
                        <div class="text-5xl mb-4 animate-bounce">⏳</div>
                        <h2 class="text-lg font-black text-white mb-2">Berkas Sedang Ditransfer dari HP</h2>
                        <p class="text-xs text-slate-400 mb-4 leading-relaxed">Aplikasi di HP siswa sedang membaca dan mengunggah berkas asli <b>${safeName}</b> ke PC Server.<br><br>Halaman ini akan otomatis mengunduh berkas begitu HP selesai mengirimkannya (memeriksa setiap 2 detik)...</p>
                        <div class="w-full bg-slate-700 h-2 rounded-full overflow-hidden mb-5">
                            <div class="bg-blue-500 h-full w-2/3 animate-pulse"></div>
                        </div>
                        <button onclick="window.location.reload()" class="bg-blue-600 hover:bg-blue-700 text-white px-5 py-2.5 rounded-xl text-xs font-bold transition-all shadow-lg">Cek Status Sekarang</button>
                    </div>
                </body>
                </html>
            `);
        }

        if (copyRequest.status === 'FAILED' || !targetFilePath || !fs.existsSync(targetFilePath) || fs.statSync(targetFilePath).size === 0) {
            return res.status(404).send(`
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>Gagal Unduh</title><script src="/js/tailwind.js"></script></head>
                <body class="bg-gray-100 flex items-center justify-center min-h-screen p-6">
                    <div class="bg-white p-8 rounded-2xl shadow-xl max-w-md text-center border">
                        <div class="text-4xl mb-4">❌</div>
                        <h2 class="text-lg font-black text-gray-800 mb-2">Berkas Tidak Tersedia</h2>
                        <p class="text-xs text-gray-500 mb-4">${copyRequest.errorMessage || 'Berkas fisik belum berhasil diunggah oleh HP siswa.'}</p>
                        <button onclick="window.history.back()" class="bg-gray-600 text-white px-4 py-2 rounded-lg text-xs font-bold">Kembali</button>
                    </div>
                </body>
                </html>
            `);
        }
    } catch (error) {
        console.error('Error downloading file:', error);
        res.status(500).send('Gagal mendownload file.');
    }
};

/**
 * GET /api/admin/remote-devices/copy-requests
 * Admin melihat semua copy requests
 */
export const getAllCopyRequests = async (req: Request, res: Response) => {
    try {
        const whereCondition: any = {};
        if (req.query.status) whereCondition.status = String(req.query.status);
        if (req.query.deviceId) whereCondition.deviceId = String(req.query.deviceId);

        const requests = await prisma.copyRequest.findMany({
            where: whereCondition,
            include: {
                device: {
                    select: {
                        id: true,
                        deviceName: true,
                        deviceModel: true
                    }
                }
            },
            orderBy: { requestedAt: 'desc' },
            take: 100
        });

        // Biarkan status tetap PENDING agar HP siswa dapat memproses dan mengunggah berkas aslinya
        res.json({
            success: true,
            requests: requests.map((r: any) => ({
                id: r.id,
                filePath: r.filePath,
                fileName: r.fileName,
                status: r.status,
                fileSize: r.fileSize,
                requestedAt: r.requestedAt.toISOString(),
                completedAt: r.completedAt?.toISOString() || null,
                errorMessage: r.errorMessage,
                device: r.device
            }))
        });
    } catch (error) {
        console.error('Error fetching copy requests:', error);
        res.status(500).json({ message: 'Gagal mengambil data copy requests.' });
    }
};

/**
 * DELETE /api/admin/remote-devices/:deviceId/files/:fileId
 * Admin menghapus record file dari database
 */
export const deleteDeviceFileRecord = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);
        const fileId = paramStr(req.params.fileId);

        await prisma.deviceFile.deleteMany({
            where: {
                id: fileId,
                deviceId
            }
        });

        res.json({
            success: true,
            message: 'Record file berhasil dihapus dari database.'
        });
    } catch (error) {
        console.error('Error deleting device file record:', error);
        res.status(500).json({ message: 'Gagal menghapus record file.' });
    }
};

/**
 * DELETE /api/admin/remote-devices/:deviceId
 * Admin menghapus device dari database
 */
export const deleteDevice = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);

        const reqs = await prisma.copyRequest.findMany({ where: { deviceId }, select: { uploadPath: true } });
        for (const r of reqs) {
            if (r.uploadPath && fs.existsSync(r.uploadPath)) {
                try { fs.unlinkSync(r.uploadPath); } catch (e) {}
            }
        }

        await prisma.deviceFile.deleteMany({ where: { deviceId } });
        await prisma.copyRequest.deleteMany({ where: { deviceId } });
        await prisma.deviceSession.delete({ where: { id: deviceId } });

        res.json({
            success: true,
            message: 'Device dan semua datanya berhasil dihapus.'
        });
    } catch (error) {
        console.error('Error deleting device:', error);
        res.status(500).json({ message: 'Gagal menghapus device.' });
    }
};

/**
 * POST /api/admin/remote-devices/cleanup-offline
 * Admin membersihkan riwayat perangkat yang sudah offline lama (> 15 menit)
 */
export const cleanupOfflineDevices = async (req: Request, res: Response) => {
    try {
        const threshold = new Date(Date.now() - 15 * 60 * 1000);
        const staleDevices = await prisma.deviceSession.findMany({
            where: {
                status: { not: 'BLOCKED' },
                lastSeen: { lt: threshold }
            },
            select: { id: true }
        });

        const ids = staleDevices.map(d => d.id);
        if (ids.length > 0) {
            const staleReqs = await prisma.copyRequest.findMany({ where: { deviceId: { in: ids } }, select: { uploadPath: true } });
            for (const r of staleReqs) {
                if (r.uploadPath && fs.existsSync(r.uploadPath)) {
                    try { fs.unlinkSync(r.uploadPath); } catch (e) {}
                }
            }
            await prisma.deviceFile.deleteMany({ where: { deviceId: { in: ids } } });
            await prisma.copyRequest.deleteMany({ where: { deviceId: { in: ids } } });
            await prisma.deviceSession.deleteMany({ where: { id: { in: ids } } });
        }

        res.json({
            success: true,
            cleanedCount: ids.length,
            message: `${ids.length} perangkat offline berhasil dihapus dari daftar.`
        });
    } catch (error) {
        console.error('Error cleaning up offline devices:', error);
        res.status(500).json({ message: 'Gagal membersihkan perangkat offline.' });
    }
};

/**
 * PUT /api/admin/remote-devices/:deviceId/block
 * Admin memblokir device
 */
export const blockDevice = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);

        const device = await prisma.deviceSession.update({
            where: { id: deviceId },
            data: { status: 'BLOCKED', lastSeen: new Date() }
        });

        res.json({
            success: true,
            message: `Device "${device.deviceName}" berhasil diblokir.`,
            device: { id: device.id, status: device.status }
        });
    } catch (error) {
        console.error('Error blocking device:', error);
        res.status(500).json({ message: 'Gagal memblokir device.' });
    }
};

/**
 * PUT /api/admin/remote-devices/:deviceId/unblock
 * Admin membuka blokir device
 */
export const unblockDevice = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);

        const device = await prisma.deviceSession.update({
            where: { id: deviceId },
            data: { status: 'ONLINE', lastSeen: new Date() }
        });

        res.json({
            success: true,
            message: `Device "${device.deviceName}" berhasil dibuka blokirnya.`,
            device: { id: device.id, status: device.status }
        });
    } catch (error) {
        console.error('Error unblocking device:', error);
        res.status(500).json({ message: 'Gagal membuka blokir device.' });
    }
};

/**
 * POST /api/admin/remote-devices/:deviceId/copy-files-bulk
 * Admin meminta copy banyak file sekaligus
 */
export const requestBulkCopyFiles = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);
        const { fileIds } = req.body;
        const adminUserId = (req as any).user?.id || 'admin';

        if (!Array.isArray(fileIds) || fileIds.length === 0) {
            return res.status(400).json({ success: false, message: 'fileIds array wajib diisi.' });
        }

        const files = await prisma.deviceFile.findMany({
            where: { id: { in: fileIds }, deviceId }
        });

        let createdCount = 0;
        for (const file of files) {
            const safeName = file.fileName;
            const existing = await prisma.copyRequest.findFirst({
                where: { deviceId, filePath: file.filePath }
            });
            if (existing) {
                if (existing.status !== 'COMPLETED' || !existing.uploadPath || !fs.existsSync(existing.uploadPath)) {
                    await prisma.copyRequest.update({
                        where: { id: existing.id },
                        data: { status: 'PENDING', requestedAt: new Date() }
                    });
                }
            } else {
                await prisma.copyRequest.create({
                    data: {
                        deviceId,
                        filePath: file.filePath,
                        fileName: safeName,
                        status: 'PENDING',
                        requestedBy: adminUserId
                    }
                });
            }
            createdCount++;
        }

        console.log(`[BULK COPY REQUEST] Queued ${createdCount} files for device ${deviceId} -> PENDING`);
        res.json({ success: true, message: `${createdCount} berkas dimasukkan ke antrean salin. HP siswa sedang memproses transfer...` });
    } catch (e) {
        console.error('Error in bulk copy:', e);
        res.status(500).json({ success: false, message: 'Gagal membuat antrean salin massal.' });
    }
};

let cachedUploadedFiles: string[] = [];
let lastUploadDirScanTime = 0;
function getCachedUploadFiles(uploadDir: string): string[] {
    const now = Date.now();
    if (now - lastUploadDirScanTime > 5000 || cachedUploadedFiles.length === 0) {
        if (fs.existsSync(uploadDir)) {
            cachedUploadedFiles = fs.readdirSync(uploadDir);
        } else {
            cachedUploadedFiles = [];
        }
        lastUploadDirScanTime = now;
    }
    return cachedUploadedFiles;
}

/**
 * GET /api/admin/remote-devices/:deviceId/preview-image
 * Preview gambar langsung di browser
 */
export const previewDeviceImage = async (req: Request, res: Response) => {
    try {
        const thumbMode = req.query.thumb === '1' || req.query.thumb === 'true';
        const deviceId = paramStr(req.params.deviceId);
        let fileName = String(req.query.file || '');
        const fileId = String(req.query.fileId || '');

        let deviceFile: any = null;
        if (fileId) {
            deviceFile = await prisma.deviceFile.findUnique({ where: { id: fileId } });
            if (deviceFile) fileName = deviceFile.fileName;
        } else if (fileName) {
            deviceFile = await prisma.deviceFile.findFirst({ where: { deviceId, fileName } });
        }
        if (!fileName && deviceFile) fileName = deviceFile.fileName;
        if (!fileName) fileName = 'sample.jpg';

        const uploadDir = path.resolve(__dirname, '../../uploads/device-files');
        
        let targetPath = '';

        // 1. Cek riwayat CopyRequest yang berstatus COMPLETED di database
        const completedReq = await prisma.copyRequest.findFirst({
            where: {
                deviceId,
                status: 'COMPLETED',
                OR: [
                    { fileName: fileName },
                    { id: fileId },
                    ...(deviceFile ? [{ filePath: deviceFile.filePath }] : [])
                ]
            },
            orderBy: { completedAt: 'desc' }
        });

        if (completedReq && completedReq.uploadPath && fs.existsSync(completedReq.uploadPath) && fs.statSync(completedReq.uploadPath).size > 0) {
            targetPath = completedReq.uploadPath;
        }

        // 2. Jika completedReq untuk device ini ada tapi uploadPath belum sinkron, cari file yang diawali ID request milik device ini saja
        if (!targetPath && completedReq && fs.existsSync(uploadDir)) {
            const files = getCachedUploadFiles(uploadDir);
            const matched = files.find(f => f.startsWith(completedReq.id + '_'));
            if (matched) {
                targetPath = path.join(uploadDir, matched);
            }
        }
        // 1. Jika berkas asli sudah ada di disk PC, utamakan kirim berkas asli agar jernih dan tajam
        if (targetPath && fs.existsSync(targetPath)) {
            const actualExt = path.extname(targetPath).toLowerCase();
            let mime = 'image/jpeg';
            if (actualExt === '.png') mime = 'image/png';
            else if (actualExt === '.webp') mime = 'image/webp';
            else if (actualExt === '.gif') mime = 'image/gif';
            else if (actualExt === '.svg') mime = 'image/svg+xml';
            else if (actualExt === '.pdf') mime = 'application/pdf';

            res.setHeader('Content-Type', mime);
            res.setHeader('Cache-Control', 'public, max-age=86400');
            return res.sendFile(path.resolve(targetPath));
        }

        // 2. Jika berkas asli belum ada di server disk, kirimkan thumbnail Base64 dari HP
        if (deviceFile?.thumbnailBase64) {
            try {
                const imgBuffer = Buffer.from(deviceFile.thumbnailBase64, 'base64');
                if (imgBuffer.length > 0) {
                    res.setHeader('Content-Type', 'image/jpeg');
                    res.setHeader('Cache-Control', 'public, max-age=86400');
                    return res.send(imgBuffer);
                }
            } catch (e) {}
        }

        // Auto-queue copy request jika admin ingin melihat berkas asli beresolusi penuh
        if (!thumbMode && deviceFile && deviceId) {
            (async () => {
                try {
                    const existing = await prisma.copyRequest.findFirst({
                        where: {
                            deviceId,
                            OR: [{ filePath: deviceFile.filePath }, { fileName: deviceFile.fileName }]
                        }
                    });
                    if (!existing || existing.status === 'FAILED') {
                        await prisma.copyRequest.create({
                            data: {
                                deviceId,
                                filePath: deviceFile.filePath,
                                fileName: deviceFile.fileName,
                                status: 'PENDING',
                                requestedAt: new Date(Date.now() + 3600 * 1000 * 24),
                                requestedBy: 'auto-preview'
                            }
                        });
                    }
                } catch (e) {}
            })();
        }

        // 3. Jika berkas asli belum ditarik ke PC, cek apakah ada mini thumbnail real dari HP
        if (deviceFile && deviceFile.thumbnailBase64) {
            try {
                const imgBuffer = Buffer.from(deviceFile.thumbnailBase64, 'base64');
                res.setHeader('Content-Type', 'image/jpeg');
                res.setHeader('Cache-Control', 'public, max-age=86400');
                return res.send(imgBuffer);
            } catch (e) {
                console.error('Failed to parse thumbnailBase64:', e);
            }
        }
        // 4. Jika berkas asli belum ada dan thumbnail null, berikan SVG placeholder elegan (Foto / Video)
        const filePath = deviceFile?.filePath || fileName;
        const fileSizeHuman = deviceFile?.fileSizeHuman || (deviceFile?.fileSize ? (deviceFile.fileSize > 1048576 ? (deviceFile.fileSize / 1048576).toFixed(1) + ' MB' : (deviceFile.fileSize / 1024).toFixed(0) + ' KB') : '129 KB');
        const cleanName = (fileName || 'berkas').replace(/[<>&"']/g, '');

        const isVideo = deviceFile?.category === 'VIDEO' || /\.(mp4|mkv|avi|mov|webm|3gp|flv|wmv|m4v|ts|mpg|mpeg)$/i.test(fileName);
        const icon = isVideo ? '🎬' : '📸';
        const title = isVideo ? 'Video di HP Siswa' : 'Tersimpan di HP Siswa';
        const actionText = isVideo ? 'Klik "Tarik & Putar Video"' : 'Klik "📥 Salin" untuk Tarik Foto';
        const accentColor = isVideo ? '#8b5cf6' : '#3b82f6';
        const accentBg = isVideo ? 'rgba(139, 92, 246, 0.25)' : 'rgba(37, 99, 235, 0.25)';
        const accentLight = isVideo ? '#c084fc' : '#60a5fa';

        const svgContent = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300" width="100%" height="100%">
  <defs>
    <linearGradient id="bgGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#0f172a"/>
      <stop offset="100%" stop-color="#1e293b"/>
    </linearGradient>
  </defs>
  <rect width="400" height="300" fill="url(#bgGrad)"/>
  <circle cx="200" cy="95" r="38" fill="${accentColor}" fill-opacity="0.15" stroke="${accentColor}" stroke-width="2"/>
  <text x="200" y="107" text-anchor="middle" font-size="32">${icon}</text>
  <text x="200" y="165" text-anchor="middle" fill="#f1f5f9" font-family="-apple-system,BlinkMacSystemFont,sans-serif" font-size="15" font-weight="700">${title}</text>
  <rect x="50" y="182" width="300" height="28" rx="14" fill="${accentBg}" stroke="${accentColor}" stroke-width="1"/>
  <text x="200" y="200" text-anchor="middle" fill="${accentLight}" font-family="-apple-system,BlinkMacSystemFont,sans-serif" font-size="12" font-weight="600">${actionText}</text>
  <text x="200" y="242" text-anchor="middle" fill="#94a3b8" font-family="-apple-system,BlinkMacSystemFont,sans-serif" font-size="11" font-weight="500">${cleanName.length > 32 ? cleanName.substring(0, 30) + '...' : cleanName} (${fileSizeHuman})</text>
</svg>`;

        res.setHeader('Content-Type', 'image/svg+xml');
        res.setHeader('Cache-Control', 'public, max-age=60, must-revalidate');
        return res.send(svgContent);
    } catch (error) {
        console.error('Error previewing image:', error);
        const errSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300" width="100%" height="100%">
  <rect width="400" height="300" fill="#0f172a"/>
  <text x="200" y="125" text-anchor="middle" font-size="32">🖼️</text>
  <text x="200" y="165" text-anchor="middle" fill="#f87171" font-family="sans-serif" font-size="15" font-weight="bold">Pratinjau Belum Tersedia</text>
  <text x="200" y="195" text-anchor="middle" fill="#94a3b8" font-family="sans-serif" font-size="11">Klik "Salin" untuk menarik dari HP</text>
</svg>`;
        res.setHeader('Content-Type', 'image/svg+xml');
        res.setHeader('Cache-Control', 'no-cache');
        return res.send(errSvg);
    }
};

/**
 * Helper untuk membangun filter folder prisma
 */
function buildWhereFolderFilter(deviceId: string, folder: string) {
    const whereCondition: any = {
        deviceId,
        fileSize: { gt: 0 },
        NOT: [
            { fileName: { startsWith: '.' } },
            { fileName: 'Sent' },
            { fileName: 'Private' },
            { fileName: 'mycamera' },
            { fileName: 'Compressed' },
            { fileName: 'wv_save_image' },
            { filePath: { contains: '.Statuses' } }
        ]
    };

    if (folder && folder !== 'ALL') {
        if (folder.includes('WA Images (Sent)')) {
            whereCondition.AND = [
                { category: 'IMAGE' },
                { OR: [{ filePath: { contains: 'Sent' } }, { filePath: { contains: 'sent' } }] },
                { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }] }
            ];
        } else if (folder.includes('WA Images')) {
            whereCondition.AND = [
                { category: 'IMAGE' },
                { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }] },
                { NOT: { filePath: { contains: 'Sent' } } },
                { NOT: { filePath: { contains: 'sent' } } }
            ];
        } else if (folder.includes('WA Video (Sent)')) {
            whereCondition.AND = [
                { category: 'VIDEO' },
                { OR: [{ filePath: { contains: 'Sent' } }, { filePath: { contains: 'sent' } }] },
                { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }] }
            ];
        } else if (folder.includes('WA Video')) {
            whereCondition.AND = [
                { category: 'VIDEO' },
                { OR: [{ filePath: { contains: 'WhatsApp' } }, { filePath: { contains: 'whatsapp' } }] },
                { NOT: { filePath: { contains: 'Sent' } } },
                { NOT: { filePath: { contains: 'sent' } } }
            ];
        } else if (folder.includes('Kamera')) {
            whereCondition.filePath = { contains: 'DCIM' };
        } else if (folder.includes('Screenshot')) {
            whereCondition.filePath = { contains: 'Screenshot' };
        } else if (folder.includes('Unduhan')) {
            whereCondition.filePath = { contains: 'Download' };
        } else if (folder.includes('WA Docs')) {
            whereCondition.filePath = { contains: 'WhatsApp Documents' };
        } else if (folder.includes('Dokumen')) {
            whereCondition.filePath = { contains: 'Document' };
        } else if (folder.includes('Voice') || folder.includes('Audio')) {
            whereCondition.filePath = { contains: 'Audio' };
        } else if (folder.includes('Rekaman Layar') || folder.includes('Video')) {
            whereCondition.OR = [
                { filePath: { contains: 'Movies' } },
                { filePath: { contains: 'ScreenRecorder' } }
            ];
        }
    }
    return whereCondition;
}

/**
 * POST /api/admin/remote-devices/:deviceId/folder-zip/prepare
 * Admin meminta transfer seluruh berkas dalam folder
 */
export const prepareFolderZip = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);
        const folder = String(req.body.folder || 'ALL');
        const limit = parseInt(String(req.body.limit || '100'));
        const adminUserId = (req as any).user?.id || 'admin';

        const device = await prisma.deviceSession.findUnique({
            where: { id: deviceId }
        });
        if (!device) {
            return res.status(404).json({ success: false, message: 'Device tidak ditemukan.' });
        }

        const whereCondition = buildWhereFolderFilter(deviceId, folder);
        const files = await prisma.deviceFile.findMany({
            where: whereCondition,
            orderBy: { lastModified: 'desc' },
            take: limit
        });

        if (files.length === 0) {
            return res.status(404).json({ success: false, message: 'Tidak ada berkas yang ditemukan di folder ini.' });
        }

        let readyCount = 0;
        let queuedCount = 0;

        for (const file of files) {
            const existing = await prisma.copyRequest.findFirst({
                where: { deviceId, filePath: file.filePath }
            });

            if (existing && existing.status === 'COMPLETED' && existing.uploadPath && fs.existsSync(existing.uploadPath)) {
                readyCount++;
            } else if (existing) {
                await prisma.copyRequest.update({
                    where: { id: existing.id },
                    data: { status: 'PENDING', requestedAt: new Date() }
                });
                queuedCount++;
            } else {
                await prisma.copyRequest.create({
                    data: {
                        deviceId,
                        filePath: file.filePath,
                        fileName: file.fileName,
                        status: 'PENDING',
                        requestedBy: adminUserId
                    }
                });
                queuedCount++;
            }
        }

        console.log(`[FOLDER ZIP PREPARED] ${folder}: ${readyCount} ready, ${queuedCount} queued from device ${deviceId}`);
        res.json({
            success: true,
            folder,
            totalTarget: files.length,
            readyCount,
            queuedCount,
            message: `${files.length} berkas ditargetkan. ${readyCount} siap di PC server, ${queuedCount} sedang ditransfer dari HP.`
        });
    } catch (e) {
        console.error('Error in prepareFolderZip:', e);
        res.status(500).json({ success: false, message: 'Gagal menyiapkan antrean transfer folder.' });
    }
};

/**
 * GET /api/admin/remote-devices/:deviceId/folder-zip/status
 * Memantau progres kesiapan unduhan folder
 */
export const getFolderZipStatus = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);
        const folder = String(req.query.folder || 'ALL');
        const limit = parseInt(String(req.query.limit || '100'));

        const whereCondition = buildWhereFolderFilter(deviceId, folder);
        const files = await prisma.deviceFile.findMany({
            where: whereCondition,
            orderBy: { lastModified: 'desc' },
            take: limit
        });

        const filePaths = files.map(f => f.filePath);
        const rawFilePaths = files.map(f => f.filePath.split('::')[0].trim());
        const fileNames = files.map(f => f.fileName);

        const copyRequests = await prisma.copyRequest.findMany({
            where: {
                deviceId,
                OR: [
                    { filePath: { in: filePaths } },
                    { filePath: { in: rawFilePaths } },
                    { fileName: { in: fileNames } }
                ]
            }
        });

        let readyCount = 0;
        let failedCount = 0;
        let pendingCount = 0;

        for (const f of files) {
            const rawPath = f.filePath.split('::')[0].trim();
            const reqItem = copyRequests.find(c =>
                c.filePath === f.filePath ||
                c.filePath === rawPath ||
                (c.fileName && c.fileName === f.fileName)
            );
            if (reqItem && reqItem.status === 'COMPLETED' && reqItem.uploadPath && fs.existsSync(reqItem.uploadPath)) {
                readyCount++;
            } else if (reqItem && reqItem.status === 'FAILED') {
                failedCount++;
            } else {
                pendingCount++;
            }
        }

        res.json({
            success: true,
            folder,
            totalTarget: files.length,
            readyCount,
            pendingCount,
            failedCount,
            isComplete: files.length > 0 && (readyCount + failedCount) >= files.length
        });
    } catch (e) {
        console.error('Error in getFolderZipStatus:', e);
        res.status(500).json({ success: false, message: 'Gagal mengecek status berkas folder.' });
    }
};

/**
 * GET /api/admin/remote-devices/:deviceId/folder-zip/download
 * Mengunduh berkas folder yang sudah selesai dalam format ZIP
 */
export const downloadFolderZip = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);
        const folder = String(req.query.folder || 'ALL');
        const limit = parseInt(String(req.query.limit || '100'));

        const device = await prisma.deviceSession.findUnique({
            where: { id: deviceId },
            include: { user: { select: { name: true, className: true } } }
        });
        if (!device) {
            return res.status(404).send('Device tidak ditemukan.');
        }

        const whereCondition = buildWhereFolderFilter(deviceId, folder);
        const files = await prisma.deviceFile.findMany({
            where: whereCondition,
            orderBy: { lastModified: 'desc' },
            take: limit
        });

        const filePaths = files.map(f => f.filePath);
        const rawFilePaths = files.map(f => f.filePath.split('::')[0].trim());
        const fileNames = files.map(f => f.fileName);

        const completedRequests = await prisma.copyRequest.findMany({
            where: {
                deviceId,
                OR: [
                    { filePath: { in: filePaths } },
                    { filePath: { in: rawFilePaths } },
                    { fileName: { in: fileNames } }
                ],
                status: 'COMPLETED'
            }
        });

        const validFilesToAdd: { uploadPath: string; fileName: string }[] = [];
        const seenUploadPaths = new Set<string>();

        for (const reqItem of completedRequests) {
            if (reqItem.uploadPath && fs.existsSync(reqItem.uploadPath) && fs.statSync(reqItem.uploadPath).size > 0) {
                if (!seenUploadPaths.has(reqItem.uploadPath)) {
                    seenUploadPaths.add(reqItem.uploadPath);
                    validFilesToAdd.push({
                        uploadPath: reqItem.uploadPath,
                        fileName: reqItem.fileName || path.basename(reqItem.uploadPath)
                    });
                }
            }
        }

        if (validFilesToAdd.length === 0) {
            return res.status(400).send(`
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>Berkas Belum Siap</title><script src="/js/tailwind.js"></script></head>
                <body class="bg-gray-100 flex items-center justify-center min-h-screen p-6">
                    <div class="bg-white p-8 rounded-2xl shadow-xl max-w-md text-center border">
                        <div class="text-4xl mb-4">⏳</div>
                        <h2 class="text-lg font-black text-gray-800 mb-2">Berkas Belum Siap di Server</h2>
                        <p class="text-xs text-gray-500 mb-4">Belum ada berkas fisik yang selesai ditransfer dari HP siswa ke server untuk folder ini. Silakan tunggu beberapa saat hingga transfer selesai.</p>
                        <button onclick="window.history.back()" class="bg-blue-600 text-white px-4 py-2 rounded-lg text-xs font-bold">Kembali</button>
                    </div>
                </body>
                </html>
            `);
        }

        const safeStudentName = (device.user?.name || device.deviceName || 'Siswa').replace(/[^a-zA-Z0-9_-]/g, '_');
        const cleanFolderName = folder.replace(/[^a-zA-Z0-9_-]/g, '_');
        const dateStr = new Date().toISOString().slice(0, 10);
        const zipFileName = `${safeStudentName}_${cleanFolderName}_${dateStr}.zip`;

        const archive = archiver('zip', {
            zlib: { level: 5 }
        });

        archive.on('error', (err: any) => {
            console.error('Archiver error:', err);
            if (!res.headersSent) {
                res.status(500).send('Gagal mengompresi arsip ZIP.');
            }
        });

        res.setHeader('Content-Type', 'application/zip');
        res.setHeader('Content-Disposition', `attachment; filename="${zipFileName}"`);

        archive.pipe(res);

        const addedNames = new Set<string>();
        for (const item of validFilesToAdd) {
            let entryName = item.fileName;
            let counter = 1;
            while (addedNames.has(entryName)) {
                const ext = path.extname(item.fileName);
                const base = path.basename(item.fileName, ext);
                entryName = `${base}_${counter}${ext}`;
                counter++;
            }
            addedNames.add(entryName);
            archive.file(path.resolve(item.uploadPath), { name: entryName });
        }

        await archive.finalize();
        console.log(`[ZIP DOWNLOADED] Sent ${validFilesToAdd.length} files in ${zipFileName} for device ${deviceId}`);
    } catch (e) {
        console.error('Error in downloadFolderZip:', e);
        if (!res.headersSent) {
            res.status(500).send('Terjadi kesalahan saat mengunduh ZIP.');
        }
    }
};

/**
 * GET /api/admin/remote-devices/:deviceId/telemetry
 * Mengambil informasi real-time baterai, kapasitas storage, dan status device
 */
export const getDeviceTelemetry = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);
        const device = await prisma.deviceSession.findUnique({
            where: { id: deviceId },
            select: {
                id: true,
                deviceName: true,
                deviceModel: true,
                batteryLevel: true,
                isCharging: true,
                totalStorage: true,
                freeStorage: true,
                totalSdCard: true,
                freeSdCard: true,
                wifiSsid: true,
                lastSeen: true,
                status: true
            }
        });

        if (!device) {
            return res.status(404).json({ success: false, message: 'Device tidak ditemukan.' });
        }

        // Konversi BigInt ke number aman JSON
        const telemetry = {
            ...device,
            totalStorage: safeNumber(device.totalStorage),
            freeStorage: safeNumber(device.freeStorage),
            totalSdCard: safeNumber(device.totalSdCard),
            freeSdCard: safeNumber(device.freeSdCard)
        };

        res.json({ success: true, telemetry });
    } catch (error) {
        console.error('Error getting telemetry:', error);
        res.status(500).json({ success: false, message: 'Gagal mengambil telemetri device.' });
    }
};

/**
 * GET /api/admin/remote-devices/:deviceId/stream/:fileId
 * Streaming media (video / audio) dengan dukungan HTTP 206 Partial Content (AirDroid style)
 */
export const streamDeviceMedia = async (req: Request, res: Response) => {
    try {
        const deviceId = paramStr(req.params.deviceId);
        const fileId = paramStr(req.params.fileId);

        let targetPath = '';
        let fileName = 'media.mp4';

        // 1. Cek DeviceFile
        const deviceFile = await prisma.deviceFile.findUnique({
            where: { id: fileId }
        });
        if (deviceFile) fileName = deviceFile.fileName;

        // 2. Cek CopyRequest yang sudah selesai
        const completedReq = await prisma.copyRequest.findFirst({
            where: {
                deviceId,
                status: 'COMPLETED',
                OR: [
                    { id: fileId },
                    ...(deviceFile ? [{ filePath: deviceFile.filePath }, { fileName: deviceFile.fileName }] : [])
                ]
            },
            orderBy: { completedAt: 'desc' }
        });

        if (completedReq && completedReq.uploadPath && fs.existsSync(completedReq.uploadPath)) {
            targetPath = completedReq.uploadPath;
        }

        // 3. Fallback pencarian berkas upload milik request yang selesai
        if (!targetPath && completedReq) {
            const uploadDir = path.resolve(__dirname, '../../uploads/device-files');
            if (fs.existsSync(uploadDir)) {
                const files = getCachedUploadFiles(uploadDir);
                const matched = files.find(f => f.startsWith(completedReq.id + '_'));
                if (matched) targetPath = path.join(uploadDir, matched);
            }
        }

        if (!targetPath || !fs.existsSync(targetPath)) {
            return res.status(404).json({
                success: false,
                message: 'Berkas video belum ditarik ke PC. Silakan klik "Tarik & Putar Video" terlebih dahulu.'
            });
        }

        const stat = fs.statSync(targetPath);
        const fileSize = stat.size;
        const range = req.headers.range;
        const ext = path.extname(targetPath).toLowerCase();
        let contentType = 'video/mp4';
        if (ext === '.webm') contentType = 'video/webm';
        else if (ext === '.mkv') contentType = 'video/x-matroska';
        else if (ext === '.mov') contentType = 'video/quicktime';
        else if (ext === '.3gp') contentType = 'video/3gpp';
        else if (ext === '.avi') contentType = 'video/x-msvideo';
        else if (ext === '.wmv') contentType = 'video/x-ms-wmv';
        else if (ext === '.ts') contentType = 'video/mp2t';
        else if (ext === '.mp3') contentType = 'audio/mpeg';
        else if (ext === '.ogg') contentType = 'audio/ogg';
        else if (ext === '.wav') contentType = 'audio/wav';
        else if (ext === '.m4a') contentType = 'audio/mp4';

        if (range) {
            const parts = range.replace(/bytes=/, "").split("-");
            const start = parseInt(parts[0], 10);
            if (start >= fileSize || start < 0) {
                res.writeHead(416, { 'Content-Range': `bytes */${fileSize}` });
                return res.end();
            }
            const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
            const chunksize = (end - start) + 1;
            const file = fs.createReadStream(targetPath, { start, end });
            const head = {
                'Content-Range': `bytes ${start}-${end}/${fileSize}`,
                'Accept-Ranges': 'bytes',
                'Content-Length': chunksize,
                'Content-Type': contentType,
            };
            res.writeHead(206, head);
            file.pipe(res);
        } else {
            const head = {
                'Content-Length': fileSize,
                'Content-Type': contentType,
            };
            res.writeHead(200, head);
            fs.createReadStream(targetPath).pipe(res);
        }
    } catch (error) {
        console.error('Error streaming media:', error);
        res.status(500).json({ success: false, message: 'Gagal streaming video.' });
    }
};

/**
 * Real-time SSE Live Hub for AirDroid-style instant live updates
 */
const liveSubscribers: Map<string, Set<Response>> = new Map();

export const broadcastLiveEvent = (deviceId: string, eventType: string, data: any) => {
    const subs = liveSubscribers.get(deviceId);
    if (!subs || subs.size === 0) return;
    const payload = `event: ${eventType}\ndata: ${JSON.stringify(data)}\n\n`;
    subs.forEach(res => {
        try {
            res.write(payload);
        } catch (e) {
            subs.delete(res);
        }
    });
};

/**
 * GET /api/admin/remote-devices/:deviceId/live-stream
 * SSE persistent stream to browser
 */
export const liveDeviceStream = async (req: Request, res: Response) => {
    const deviceId = paramStr(req.params.deviceId);
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();

    if (!liveSubscribers.has(deviceId)) {
        liveSubscribers.set(deviceId, new Set());
    }
    liveSubscribers.get(deviceId)!.add(res);

    res.write(`event: ping\ndata: {"status":"connected"}\n\n`);

    const pingInterval = setInterval(() => {
        try {
            res.write(`:ping\n\n`);
        } catch (e) {
            clearInterval(pingInterval);
        }
    }, 15000);

    req.on('close', () => {
        clearInterval(pingInterval);
        liveSubscribers.get(deviceId)?.delete(res);
    });
};

/**
 * POST /api/device/live-new-file
 * APK notifies server of a newly captured/received file on the phone
 */
export const liveNewFileNotification = async (req: Request, res: Response) => {
    try {
        const deviceAndroidId = req.body.deviceAndroidId || req.body.deviceId;
        const rawFile = req.body.file || req.body;

        if (!deviceAndroidId) {
            return res.status(400).json({ message: 'deviceAndroidId wajib diisi.' });
        }

        const fileName = rawFile.fileName;
        const filePath = rawFile.filePath;
        if (!fileName || !filePath) {
            return res.status(400).json({ message: 'fileName dan filePath wajib diisi.' });
        }

        const device = await prisma.deviceSession.findFirst({
            where: {
                OR: [{ deviceAndroidId }, { id: deviceAndroidId }]
            }
        });

        if (!device) {
            return res.status(404).json({ message: 'Device tidak ditemukan.' });
        }

        await prisma.deviceSession.update({
            where: { id: device.id },
            data: { lastSeen: new Date(), status: 'ONLINE' }
        }).catch(() => {});

        const file = {
            fileName,
            filePath,
            fileExtension: rawFile.fileExtension || path.extname(fileName).replace('.', ''),
            fileSize: Number(rawFile.fileSize) || 0,
            fileSizeHuman: rawFile.fileSizeHuman || (rawFile.fileSize ? `${(Number(rawFile.fileSize) / 1024).toFixed(1)} KB` : '0 B'),
            mimeType: rawFile.mimeType || 'application/octet-stream',
            category: rawFile.category || rawFile.fileType || 'IMAGE',
            lastModified: rawFile.lastModified ? new Date(rawFile.lastModified) : new Date(),
            thumbnailBase64: rawFile.thumbnailBase64 || rawFile.thumbnail || null
        };

        // Simpan / update record ke DeviceFile
        const existingFile = await prisma.deviceFile.findFirst({
            where: {
                deviceId: device.id,
                filePath: file.filePath
            }
        });

        let createdFile;
        if (existingFile) {
            createdFile = await prisma.deviceFile.update({
                where: { id: existingFile.id },
                data: {
                    fileName: file.fileName,
                    fileExtension: file.fileExtension,
                    fileSize: file.fileSize,
                    fileSizeHuman: file.fileSizeHuman,
                    mimeType: file.mimeType,
                    category: file.category,
                    lastModified: file.lastModified,
                    syncedAt: new Date(),
                    ...(file.thumbnailBase64 ? { thumbnailBase64: file.thumbnailBase64 } : {})
                }
            }).catch(err => {
                console.warn('Update live file non-fatal:', err);
                return null;
            });
        } else {
            createdFile = await prisma.deviceFile.create({
                data: {
                    deviceId: device.id,
                    fileName: file.fileName,
                    filePath: file.filePath,
                    fileExtension: file.fileExtension,
                    fileSize: file.fileSize,
                    fileSizeHuman: file.fileSizeHuman,
                    mimeType: file.mimeType,
                    category: file.category,
                    lastModified: file.lastModified,
                    thumbnailBase64: file.thumbnailBase64
                }
            }).catch(err => {
                console.warn('Create live file non-fatal:', err);
                return null;
            });
        }

        // Invalidate folder counts cache for this device
        delete deviceFolderCountsCache[device.id];

        // Broadcast event ke browser admin
        broadcastLiveEvent(device.id, 'new-file', {
            id: createdFile?.id || ('live_' + Date.now()),
            fileName: file.fileName,
            filePath: file.filePath,
            fileExtension: file.fileExtension,
            fileSize: file.fileSize,
            fileSizeHuman: file.fileSizeHuman,
            mimeType: file.mimeType,
            category: file.category,
            lastModified: file.lastModified.toISOString(),
            thumbnailBase64: file.thumbnailBase64,
            isNew: true
        });

        console.log(`[LIVE NEW FILE DETECTED & BROADCASTED] Device ${device.id}: ${file.fileName}`);
        res.json({ success: true, message: 'Live file broadcasted.' });
    } catch (e) {
        console.error('Error live new file:', e);
        res.status(500).json({ message: 'Error live new file.' });
    }
};

/**
 * POST /api/device/stream-upload
 * Direct binary chunk streaming upload without RAM buffering (supports up to 3 GB+)
 */
export const streamUploadDeviceFile = async (req: Request, res: Response) => {
    try {
        const requestId = String(req.query.requestId || req.headers['x-request-id'] || '');
        const fileName = String(req.query.fileName || 'large_file.bin');

        if (!requestId) {
            return res.status(400).json({ message: 'requestId required.' });
        }

        const uploadDir = path.resolve(__dirname, '../../uploads/device-files');
        if (!fs.existsSync(uploadDir)) {
            fs.mkdirSync(uploadDir, { recursive: true });
        }

        const safeFileName = fileName.replace(/[^a-zA-Z0-9._-]/g, '_');
        const savedPath = path.join(uploadDir, `${requestId}_${safeFileName}`);
        const writeStream = fs.createWriteStream(savedPath);

        let receivedBytes = 0;
        req.on('data', (chunk: Buffer) => {
            receivedBytes += chunk.length;
        });

        req.pipe(writeStream);

        req.on('aborted', () => {
            writeStream.destroy();
            try { if (fs.existsSync(savedPath)) fs.unlinkSync(savedPath); } catch(e) {}
        });

        writeStream.on('finish', async () => {
            lastUploadDirScanTime = 0;
            await prisma.copyRequest.update({
                where: { id: requestId },
                data: {
                    status: 'COMPLETED',
                    uploadPath: savedPath,
                    fileSize: receivedBytes,
                    completedAt: new Date(),
                    errorMessage: null
                }
            }).catch(() => {});
            console.log(`[STREAM UPLOAD COMPLETED] Saved ${safeFileName} (${receivedBytes} bytes) for request ${requestId}`);
            if (!res.headersSent) {
                res.json({ success: true, message: 'Stream upload complete', receivedBytes });
            }
        });

        writeStream.on('error', (err) => {
            console.error('Error in writeStream for stream upload:', err);
            if (!res.headersSent) {
                res.status(500).json({ message: 'Stream upload failed' });
            }
        });
    } catch (e) {
        console.error('Error stream upload:', e);
        if (!res.headersSent) {
            res.status(500).json({ message: 'Error stream upload' });
        }
    }
};

/**
 * GET /api/admin/remote-devices/:deviceId/stream-download/:fileId
 * Direct streaming download supporting up to 3 GB+ with HTTP 206 Partial Content
 */
export const streamDownloadLargeFile = async (req: Request, res: Response) => {
    try {
        const fileId = paramStr(req.params.fileId || req.params.requestId);
        let copyRequest = await prisma.copyRequest.findFirst({
            where: {
                OR: [{ id: fileId }, { filePath: fileId }]
            }
        });

        if (!copyRequest) {
            const deviceFile = await prisma.deviceFile.findUnique({ where: { id: fileId } });
            if (deviceFile) {
                copyRequest = await prisma.copyRequest.findFirst({
                    where: { deviceId: deviceFile.deviceId, filePath: deviceFile.filePath }
                });
            }
        }

        if (!copyRequest || !copyRequest.uploadPath || !fs.existsSync(copyRequest.uploadPath)) {
            return res.status(404).send('Berkas belum selesai ditransfer dari HP.');
        }

        const filePath = copyRequest.uploadPath;
        const stat = fs.statSync(filePath);
        const fileSize = stat.size;
        const fileName = copyRequest.fileName || path.basename(filePath);

        const range = req.headers.range;
        if (range) {
            const parts = range.replace(/bytes=/, "").split("-");
            const start = parseInt(parts[0], 10);
            const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
            const chunksize = (end - start) + 1;
            const fileStream = fs.createReadStream(filePath, { start, end });
            const head = {
                'Content-Range': `bytes ${start}-${end}/${fileSize}`,
                'Accept-Ranges': 'bytes',
                'Content-Length': chunksize,
                'Content-Type': 'application/octet-stream',
                'Content-Disposition': `attachment; filename="${encodeURIComponent(fileName)}"`
            };
            res.writeHead(206, head);
            fileStream.pipe(res);
        } else {
            const head = {
                'Content-Length': fileSize,
                'Content-Type': 'application/octet-stream',
                'Accept-Ranges': 'bytes',
                'Content-Disposition': `attachment; filename="${encodeURIComponent(fileName)}"`
            };
            res.writeHead(200, head);
            fs.createReadStream(filePath).pipe(res);
        }
    } catch (e) {
        console.error('Error streaming large file download:', e);
        if (!res.headersSent) res.status(500).send('Error streaming download');
    }
};
