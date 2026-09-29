import express from "express";
import cors from "cors";
import dotenv from "dotenv";
import path from "path";
import fs from "fs";
import { PrismaClient } from "@prisma/client";
import { classifyFolder } from "./utils/folderClassifier";

dotenv.config();

const app = express();
const prisma = new PrismaClient();
const PORT = process.env.PORT || 3000;

/** Safe BigInt → Number conversion untuk JSON/EJS serialization */
function safeNumber(val: any): number | null {
  if (val == null) return null;
  try { return Number(val); } catch { return null; }
}

app.use(cors());
app.use(express.json({ limit: "150mb" }));
app.use(express.urlencoded({ limit: "150mb", extended: true }));

// Setup EJS for Web Portal
app.set("view engine", "ejs");
const candidateViewDirs = [
  path.join(process.cwd(), "src/views"),
  path.join(process.cwd(), "backend/src/views"),
  path.join(__dirname, "../src/views"),
  path.join(__dirname, "views"),
  path.join(process.cwd(), "dist/views"),
  path.join(process.cwd(), "backend/dist/views")
];
const resolvedViewsDir = candidateViewDirs.find(d => fs.existsSync(d)) || path.join(__dirname, "views");
app.set("views", resolvedViewsDir);

// Explicit route for APK download with correct Android Package headers
app.get([
  '/uploads/SmartSchool_CBT_Release.apk',
  '/SmartSchool_CBT_Release.apk',
  '/uploads/smartcbt-latest.apk',
  '/smartcbt-latest.apk',
  '/download-apk',
  '/download/apk',
  '/apk',
  '/app',
  '/app-release.apk',
  '/smartschool.apk'
], (req, res) => {
  const candidateApkPaths = [
    path.join(process.cwd(), "uploads/SmartCBT_v2.8.88.apk"),
    path.join(process.cwd(), "uploads/smartcbt-latest.apk"),
    path.join(process.cwd(), "public/uploads/smartcbt-latest.apk"),
    path.join(process.cwd(), "public/smartcbt-latest.apk"),
    path.join(process.cwd(), "smartcbt-latest.apk"),
    path.join(process.cwd(), "../android_app/app/build/outputs/apk/debug/app-debug.apk"),
    path.join(process.cwd(), "../android_app/app/build/outputs/apk/release/app-release.apk"),
    path.join(__dirname, "../uploads/smartcbt-latest.apk"),
    path.join(__dirname, "../public/uploads/smartcbt-latest.apk")
  ];

  const existingFiles = candidateApkPaths
    .filter(p => fs.existsSync(p))
    .map(p => ({ path: p, stat: fs.statSync(p) }))
    .sort((a, b) => b.stat.mtimeMs - a.stat.mtimeMs);

  if (existingFiles.length > 0) {
    const apkPath = existingFiles[0].path;
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    res.setHeader('Content-Disposition', 'attachment; filename="SmartSchool_CBT_Release.apk"');
    res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');
    return res.sendFile(path.resolve(apkPath));
  }
  res.status(404).send('APK file not found on server');
});

app.use(express.static(path.join(process.cwd(), "public")));
app.use("/uploads", express.static(path.join(process.cwd(), "uploads")));
app.use(express.static(path.join(process.cwd(), "uploads")));

import apiRoutes from "./routes/api";
import { 
  authenticateWebAdmin, 
  authenticateWebBk, 
  authenticateWebGuru, 
  authenticateWebUks, 
  authenticateWebOperator,
  extractWebToken 
} from "./middlewares/authMiddleware";

import { checkVersionV1 } from "./controllers/appUpdateController";

// --- API Routes ---
app.use("/api", apiRoutes);
app.get("/api/v1/app/check-version", checkVersionV1);

// Endpoint Jam Server (WIB 24 Jam)
app.get("/api/server-time", (req, res) => {
  const now = new Date();
  res.json({
    success: true,
    serverTime: now.toISOString(),
    timestamp: now.getTime(),
    timezone: "Asia/Jakarta"
  });
});

// Endpoint Heartbeat & Status Server untuk Monitoring APK Siswa & Guru
app.get(["/api/health", "/api/ping"], (req, res) => {
  const now = new Date();
  res.json({
    status: "ok",
    success: true,
    serverTime: now.toISOString(),
    timestamp: now.getTime(),
    uptime: process.uptime()
  });
});

// --- Web Portal Routes ---
// Mount View Routes (EJS) - Dukung rute root dan /login eksplisit
app.get(['/', '/login', '/masuk', '/signin', '/portal'], (req, res) => res.render('index', { title: "Smart School & CBT Portal" }));

app.get('/logout', (req, res) => {
  res.clearCookie('token');
  res.clearCookie('admin_token');
  res.clearCookie('jwt_token');
  res.redirect('/login');
});

// ADMIN Routes (Navy Blue) - Dilindungi Autentikasi Sisi Server
app.get('/admin', authenticateWebAdmin, (req, res) => res.render('admin_dashboard'));
app.get('/admin/portal', authenticateWebAdmin, (req, res) => res.render('manajemen_portal'));
app.get('/admin/users', authenticateWebAdmin, (req, res) => res.render('manajemen_user'));
app.get('/admin/evoting', authenticateWebAdmin, (req, res) => res.render('manajemen_evoting'));
app.get('/admin/pengaturan', authenticateWebAdmin, (req, res) => res.render('pengaturan_sistem'));
app.get('/admin/settings', authenticateWebAdmin, (req, res) => res.render('pengaturan_sistem'));
app.get('/admin/feature-panel', authenticateWebAdmin, (req, res) => res.render('manajemen_feature_panel'));
app.get('/admin/remote-devices', authenticateWebAdmin, async (req, res) => {
  try {
    // Standardized 3-minute threshold for ONLINE indicator (matching APK 20s heartbeat)
    const ONLINE_THRESHOLD_MS = 3 * 60 * 1000;
    const onlineThreshold = new Date(Date.now() - ONLINE_THRESHOLD_MS);
    
    // Load registered devices and exact database counts
    const [totalDeviceCount, onlineDeviceCount, devices, classList] = await Promise.all([
      prisma.deviceSession.count({
        where: { status: { not: 'BLOCKED' } }
      }),
      prisma.deviceSession.count({
        where: { lastSeen: { gt: onlineThreshold }, status: { not: 'BLOCKED' } }
      }),
      prisma.deviceSession.findMany({
        where: {
          status: { not: 'BLOCKED' }
        },
        include: {
          user: { select: { name: true, username: true, nisn: true, className: true, role: true } },
          _count: { select: { files: true } }
        },
        orderBy: { lastSeen: 'desc' },
        take: 200 // Load all registered devices for left panel selection
      }),
      prisma.class.findMany({ orderBy: { name: 'asc' }, select: { id: true, name: true } })
    ]);
    
    // Status evaluation: Aktif dalam 3 menit terakhir = ONLINE
    const mappedDevices = devices.map(d => {
      const isOnline = d.lastSeen > onlineThreshold;
      const rawRole = (d.user as any)?.role || 'STUDENT';
      let normalizedRole = 'STUDENT';
      if (['TEACHER', 'ADMIN', 'OPERATOR', 'COUNSELOR', 'LIBRARIAN', 'MEDICAL'].includes(rawRole)) normalizedRole = 'TEACHER';
      else if (rawRole === 'PARENT') normalizedRole = 'PARENT';

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
        status: (d.status === 'BLOCKED') ? 'BLOCKED' : (isOnline ? 'ONLINE' : 'OFFLINE'),
        role: normalizedRole,
        fileCount: d._count?.files || 0,
        lastSeen: d.lastSeen.toISOString(),
        totalStorage: safeNumber(d.totalStorage),
        freeStorage: safeNumber(d.freeStorage),
        totalSdCard: safeNumber(d.totalSdCard),
        freeSdCard: safeNumber(d.freeSdCard),
        owner: d.user || null
      };
    });

    const sortedDevices = mappedDevices.sort((a, b) => {
      if (a.status === 'ONLINE' && b.status !== 'ONLINE') return -1;
      if (a.status !== 'ONLINE' && b.status === 'ONLINE') return 1;
      if (a.fileCount > 0 && b.fileCount === 0) return -1;
      if (a.fileCount === 0 && b.fileCount > 0) return 1;
      return new Date(b.lastSeen).getTime() - new Date(a.lastSeen).getTime();
    });

    const onlineDevices = sortedDevices.filter(d => d.status === 'ONLINE');
    // Prioritaskan device online dengan berkas, lalu online apa saja, lalu device terakhir yang memiliki berkas
    const firstDevice = onlineDevices.find(d => d.fileCount > 0) 
      || onlineDevices[0] 
      || sortedDevices.find(d => d.fileCount > 0) 
      || sortedDevices[0] 
      || null;

    let initialFiles: any[] = [];
    let initialTotalFiles = 0;
    let initialFolderCounts: Record<string, number> = { 'ALL': 0 };

    if (firstDevice) {
      initialTotalFiles = firstDevice.fileCount;
      const [files, pathRows, completedReqs] = await Promise.all([
        prisma.deviceFile.findMany({
          where: { deviceId: firstDevice.id, NOT: { fileName: { startsWith: '.' } } },
          orderBy: { lastModified: 'desc' },
          take: 60
        }),
        prisma.deviceFile.findMany({
          where: { deviceId: firstDevice.id, NOT: { fileName: { startsWith: '.' } } },
          select: { filePath: true, fileName: true, thumbnailBase64: true }
        }),
        prisma.copyRequest.findMany({
          where: { deviceId: firstDevice.id, status: 'COMPLETED', uploadPath: { not: null } },
          select: { fileName: true }
        })
      ]);
      const completedNames = new Set(completedReqs.map(r => r.fileName));
      initialFiles = files.map(f => ({
        ...f,
        isTransferred: Boolean(completedNames.has(f.fileName) || (f as any).thumbnailBase64)
      }));
      initialFolderCounts['ALL'] = pathRows.length;
      initialFolderCounts['✅ Sudah Ditransfer ke PC'] = pathRows.filter(r => completedNames.has(r.fileName) || (r as any).thumbnailBase64).length;
      for (const r of pathRows) {
        const f = classifyFolder(r.filePath);
        initialFolderCounts[f] = (initialFolderCounts[f] || 0) + 1;
      }
    }

    const activeToken = extractWebToken(req) || '';

    res.render('manajemen_remote_device', {
      initialDevices: sortedDevices,
      initialOnlineDevices: onlineDevices.length > 0 ? onlineDevices : [],
      initialTotalDevices: totalDeviceCount,
      initialOnlineCount: onlineDeviceCount,
      initialSelectedDevice: firstDevice,
      initialFiles: initialFiles,
      initialTotalFiles: initialTotalFiles,
      totalFiles: initialTotalFiles,
      initialFolderCounts: initialFolderCounts,
      initialClassList: classList || [],
      serverToken: activeToken
    });
  } catch (err) {
    const activeToken = extractWebToken(req) || '';
    res.render('manajemen_remote_device', {
      initialDevices: [],
      initialOnlineDevices: [],
      initialTotalDevices: 0,
      initialOnlineCount: 0,
      initialSelectedDevice: null,
      initialFiles: [],
      initialTotalFiles: 0,
      totalFiles: 0,
      initialFolderCounts: { 'ALL': 0 },
      initialClassList: [],
      serverToken: activeToken
    });
  }
});
import { downloadCopiedFile } from "./controllers/remoteDeviceController";
import { getBookCoverSvg } from "./controllers/libraryController";
app.get('/admin/remote-devices/download/:fileId', authenticateWebAdmin, downloadCopiedFile);
app.get('/admin/remote-devices/download-request/:fileId', authenticateWebAdmin, downloadCopiedFile);
app.get('/admin/remote-devices/:deviceId/download/:fileId', authenticateWebAdmin, downloadCopiedFile);
app.get('/elibrary/cover/:id', getBookCoverSvg);
app.get('/library/cover/:id', getBookCoverSvg);
app.get('/api/library/cover/:id', getBookCoverSvg);
app.get('/admin/files', authenticateWebAdmin, (req, res) => res.render('remote_file_manager'));
app.get('/admin/elibrary', authenticateWebAdmin, (req, res) => res.render('manajemen_elibrary'));
app.get('/elibrary', (req, res) => res.render('manajemen_elibrary'));
app.get('/perpustakaan', (req, res) => res.render('manajemen_elibrary'));
app.get('/admin/monitoring', authenticateWebAdmin, (req, res) => res.render('monitoring_ujian'));
app.get('/admin/siswa', authenticateWebAdmin, async (req, res) => {
  try {
    const students = await prisma.user.findMany({
      where: { role: 'STUDENT' },
      select: {
        id: true,
        name: true,
        username: true,
        role: true,
        className: true,
        nis: true,
        nisn: true,
        parentPhone: true,
        profilePicUrl: true,
        deviceBindingId: true,
        classRole: true,
        createdAt: true
      },
      orderBy: [
        { className: 'asc' },
        { name: 'asc' }
      ]
    });
    res.render('manajemen_siswa', { initialStudents: students });
  } catch (e) {
    res.render('manajemen_siswa', { initialStudents: [] });
  }
});
app.get(['/admin/biodata', '/operator/biodata', '/admin/verifikasi-biodata', '/operator/verifikasi-biodata'], authenticateWebAdmin, (req, res) => res.render('verifikasi_biodata'));
app.get('/admin/kelas', authenticateWebAdmin, (req, res) => res.render('manajemen_kelas'));
app.get([
  '/admin/kelas/print-barcodes', 
  '/operator/kelas/print-barcodes', 
  '/admin/barcodes',
  '/operator/barcodes',
  '/admin/print-barcodes',
  '/operator/print-barcodes'
], authenticateWebAdmin, (req, res) => res.render('cetak_barcode_kelas'));
app.get([
  '/admin/siswa/print-cards', 
  '/operator/siswa/print-cards',
  '/admin/siswa/print-qr',
  '/operator/siswa/print-qr',
  '/admin/print-student-qr',
  '/operator/print-student-qr'
], authenticateWebAdmin, async (req, res) => {
  try {
    const students = await prisma.user.findMany({
      where: { role: 'STUDENT' },
      select: {
        id: true,
        name: true,
        username: true,
        nisn: true,
        nis: true,
        className: true,
        gender: true,
        profilePicUrl: true
      },
      orderBy: [
        { className: 'asc' },
        { name: 'asc' }
      ]
    });
    res.render('cetak_kartu_siswa', { initialStudents: students });
  } catch (e) {
    res.render('cetak_kartu_siswa', { initialStudents: [] });
  }
});
app.get('/admin/guru', authenticateWebAdmin, (req, res) => res.render('manajemen_guru'));
app.get(['/admin/absensi', '/operator/absensi', '/bk/absensi'], authenticateWebBk, (req, res) => {
  const isOperator = req.path.startsWith('/operator') || (req as any).user?.role === 'OPERATOR';
  const isBk = req.path.startsWith('/bk') || ['COUNSELOR', 'BK', 'GURU_BK'].includes((req as any).user?.role);
  res.render('rekap_absensi', { isOperator, isBk });
});
app.get('/absensi/tap-kartu', (req, res) => res.render('kiosk_tap_kartu'));
app.get('/admin/tap-kartu', authenticateWebAdmin, (req, res) => res.render('kiosk_tap_kartu'));
app.get('/admin/cbt', authenticateWebAdmin, (req, res) => res.render('manajemen_cbt', { isOperator: false }));
app.get('/admin/monitoring', authenticateWebAdmin, (req, res) => res.render('monitoring_ujian', { isOperator: false }));
app.get('/cbt', (req, res) => res.redirect('/admin/cbt'));
app.get('/monitoring', (req, res) => res.redirect('/admin/monitoring'));

import { getCapacityDiagnosticsData } from "./controllers/serverCapacityController";
app.get('/admin/cbt/capacity', authenticateWebAdmin, async (req, res) => {
  try {
    const initialData = await getCapacityDiagnosticsData(req.app);
    res.render('server_capacity_dashboard', { initialData, isOperator: false });
  } catch (e) {
    res.render('server_capacity_dashboard', { initialData: null, isOperator: false });
  }
});
app.get('/operator/cbt/capacity', async (req, res) => {
  try {
    const initialData = await getCapacityDiagnosticsData(req.app);
    res.render('server_capacity_dashboard', { initialData, isOperator: true });
  } catch (e) {
    res.render('server_capacity_dashboard', { initialData: null, isOperator: true });
  }
});
app.get(['/admin/perpus', '/admin/elibrary', '/admin/perpustakaan'], authenticateWebAdmin, (req, res) => res.render('manajemen_elibrary', { isOperator: false }));
app.get('/siswa/cbt/room/:examId', (req, res) => res.render('student_cbt_room', { examId: req.params.examId }));
app.get('/admin/tugas', authenticateWebAdmin, (req, res) => res.render('manajemen_tugas'));
app.get('/admin/surat', authenticateWebAdmin, (req, res) => res.render('manajemen_surat'));
app.get('/operator/surat', authenticateWebOperator, (req, res) => res.render('manajemen_surat'));
app.get('/display/kelas-kosong', (req, res) => res.render('display_kelas_kosong'));
app.get('/piket/kelas-kosong', (req, res) => res.render('display_kelas_kosong'));

// --- RUTE MODUL BARU PORTAL (MODUL 0, 4, 5, 8, 11) ---
app.get('/admin/server-monitoring', authenticateWebAdmin, (req, res) => res.render('admin_server_monitoring'));
app.get('/admin/geofence-attendance', authenticateWebAdmin, (req, res) => res.render('admin_geofence_attendance', { isOperator: false }));
app.get('/operator/geofence-attendance', authenticateWebOperator, (req, res) => res.render('admin_geofence_attendance', { isOperator: true }));
app.get('/admin/pengumuman', authenticateWebAdmin, (req, res) => res.render('manajemen_pengumuman', { isOperator: false }));
app.get('/operator/pengumuman', authenticateWebOperator, (req, res) => res.render('manajemen_pengumuman', { isOperator: true }));
app.get('/admin/app-releases', authenticateWebAdmin, (req, res) => res.render('manajemen_app_release', { isOperator: false }));
app.get('/operator/app-releases', authenticateWebOperator, (req, res) => res.render('manajemen_app_release', { isOperator: true }));
app.get('/admin/client-logs', authenticateWebAdmin, (req, res) => res.render('admin_client_logs', { isOperator: false }));
app.get('/operator/client-logs', authenticateWebOperator, (req, res) => res.render('admin_client_logs', { isOperator: true }));
app.get('/operator/feature-panel', authenticateWebOperator, (req, res) => res.render('manajemen_feature_panel'));

// OPERATOR Routes (Amber)
app.get('/operator', authenticateWebOperator, (req, res) => res.render('operator_dashboard'));
app.get('/operator/cbt', authenticateWebOperator, (req, res) => res.render('manajemen_cbt', { isOperator: true }));
app.get('/operator/monitoring', authenticateWebOperator, (req, res) => res.render('monitoring_ujian', { isOperator: true }));
app.get('/operator/jam-pelajaran', authenticateWebOperator, (req, res) => res.render('operator_jam_pelajaran'));
app.get('/operator/piket', authenticateWebOperator, (req, res) => res.render('operator_piket'));
app.get('/operator/perpus', authenticateWebOperator, (req, res) => res.render('manajemen_elibrary'));
app.get('/operator/perpustakaan', authenticateWebOperator, (req, res) => res.render('manajemen_elibrary'));
app.get('/perpus', authenticateWebOperator, (req, res) => res.render('manajemen_elibrary'));
app.get('/perpustakaan', authenticateWebOperator, (req, res) => res.render('manajemen_elibrary'));
app.get('/operator/uks', authenticateWebUks, (req, res) => res.render('uks_dashboard'));
app.get('/operator/bk', authenticateWebBk, (req, res) => res.render('bk_dashboard'));
app.get('/operator/tugas', authenticateWebOperator, (req, res) => res.render('manajemen_tugas'));

// TEACHER Routes (Emerald Green)
app.get('/guru', authenticateWebGuru, (req, res) => res.render('guru_dashboard'));

// STUDENT Routes (Sky Blue)
app.get('/siswa', (req, res) => res.render('siswa_dashboard'));

// PARENT / ORANG TUA Routes
app.get('/orangtua', (req, res) => res.render('parent_dashboard'));

// BK & UKS Routes (Teal & Rose) - Wajib Autentikasi Sisi Server
app.get('/bk', authenticateWebBk, (req, res) => res.render('bk_dashboard'));
app.get('/uks', authenticateWebUks, (req, res) => res.render('uks_dashboard'));

// SATPAM & POS KEAMANAN GATEPASS SCANNER
app.get(['/satpam/gatepass', '/satpam', '/admin/gatepass', '/operator/gatepass'], (req, res) => res.render('satpam_gatepass'));

// Crash Protection Handlers (mencegah server mati seketika akibat unhandled exception)
process.on('uncaughtException', (err) => {
  console.error('[CRITICAL] Uncaught Exception intercepted:', err);
});

process.on('unhandledRejection', (reason, promise) => {
  console.error('[CRITICAL] Unhandled Promise Rejection intercepted:', reason);
});

import { autoMarkUnscannedStudentsAsAlpa } from './controllers/attendanceController';

const server = app.listen(Number(PORT), () => {
  console.log(`[Server] Running dual-stack IPv4/IPv6 on port ${PORT}`);
});

// Anti-Timeout Tuning for Cloudflare Tunnel
server.keepAliveTimeout = 65000;
server.headersTimeout = 66000;

