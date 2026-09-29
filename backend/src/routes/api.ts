import { reportEmptyClass, getEmptyClassReports, handleEmptyClass, streamEmptyClassMonitor, getEmptyClassStatus, getClassMonitoringStatus } from '../controllers/emptyClassController';
import { getAppVersion, updateAppVersionConfig } from '../controllers/appUpdateController';
import { getStudents, createStudent, deleteStudent, updateStudent, syncStudentsToLocal, getTeachers, createTeacher, updateTeacher, deleteTeacher, syncTeachersToLocal } from '../controllers/userController';
import { createParentLeaveRequest, getParentLeaveRequests, getTeacherLeaveRequests, verifyLeaveRequest, deleteLeaveRequest } from '../controllers/leaveRequestController';

import {
    getTeacherTodaySchedule,
    generateDynamicQr,
    scanStudentDynamicQr,
    quickMarkAllAttendance,
    updateSingleStudentAttendance,
    createTeachingJournal,
    getTeachingJournals,
    broadcastToClass,
    getTeacherEFiles,
    uploadTeacherEFile,
    deleteTeacherEFile,
    markTeacherEFileAsRead,
    getTeacherClasses,
    getStudentsByClass,
    getHomeroomSummary,
    getPiketSummary,
    getTeacherBroadcastHistory,
    getTeacherSubjectAttendanceRecap,
    teacherGeolocationAttendance,
    getTeacherTodayAttendance
} from '../controllers/teacherFeatureController';

import {
     getBooks, createBook, borrowBook, deleteBook, adjustBookCopiesStock, updateBookCover, checkBookOwnerByBarcode, reassignBookBorrower, getMyBorrowedBooks, getAllBookCopies, returnBookCopy, getBookCoverSvg, quickCirculation, getDigitalMemberCard, getAllMembers } from '../controllers/libraryController';
import { getWeeklyPiketSchedules, createPiketSchedule, updatePiketSchedule, deletePiketSchedule, getTodayOnDutyTeachers, checkTeacherPiketStatus, getTeacherPiketReports } from '../controllers/piketScheduleController';
import { getTeacherInactivityReport } from '../controllers/teacherInactivityController';
import { getRemoteFiles, uploadAndDistributeFile, deleteRemoteFile } from '../controllers/fileManagerController';
import { previewDeviceImage, registerDevice, deviceHeartbeat, syncDeviceFiles, getPendingCopyRequests, uploadDeviceFile, getAllDevices, getDeviceDetail, getDeviceFiles, requestCopyFile, requestBulkCopyFiles, downloadCopiedFile, getAllCopyRequests, deleteDeviceFileRecord, deleteDevice, cleanupOfflineDevices, blockDevice, unblockDevice, prepareFolderZip, getFolderZipStatus, downloadFolderZip, getDeviceTelemetry, streamDeviceMedia, liveDeviceStream, liveNewFileNotification, streamUploadDeviceFile, streamDownloadLargeFile, getDeviceListPaginated, getDeviceStats, triggerDeviceScan } from '../controllers/remoteDeviceController';
import express from 'express';
import { login, resetDeviceBinding, refreshTokenHandler, getAuditLogs, getSessionToken } from '../controllers/authController';
import { getAnnouncements, createAnnouncement, deleteAnnouncement } from '../controllers/announcementController';
import { getHomeworks, createHomework, deleteHomework, submitHomework, getHomeworkSubmissions, gradeHomeworkSubmission, duplicateHomework } from '../controllers/homeworkController';
import { authenticateJWT, requireRole, requireExamBrowser } from '../middlewares/authMiddleware';
import { getStudentProfile, updateStudentProfile, getDetailedAttendance, getLearningAnalytics, getEFiles, uploadStudentEFile, deleteStudentEFile, getSubjectAttendanceSummary, getStudentNotifications } from '../controllers/studentController';

const router = express.Router();

// --- Auth Routes ---
router.post('/auth/login', login);
router.post('/auth/refresh', refreshTokenHandler);
router.get('/auth/session-token', authenticateJWT, getSessionToken);
router.get('/admin/audit-logs', authenticateJWT, requireRole(['ADMIN']), getAuditLogs);
router.post('/auth/reset-device', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), resetDeviceBinding);

import { tapCardAttendance, recordAttendanceScan, recordManualAttendance, getAttendanceToday, getAttendanceRekap, exportAttendanceExcel, exportAttendancePdf, getGateSettings, updateGateSettings, scanClassGate, getClassGateBarcodes, toggleEmergencyGateOut, checkAndNotifyUnscannedStudents, bulkSyncAttendance, getAttendanceRekapPeriode, getAttendanceRekapSiswa } from '../controllers/attendanceController';
import { getHolidays, getSchoolHolidays, createOrUpdateHoliday, deleteHoliday } from '../controllers/calendarController';
import { getFeaturePanelData, toggleFeatureKey, updateGpsConfig, getAttendanceConfig, getAllFeatureFlagsMap } from '../controllers/featureToggleController';

// --- Attendance Routes (APK Client & Web Admin / BK) ---
router.post('/attendance/tap-card', tapCardAttendance);
router.post('/attendance/scan', recordAttendanceScan); // Mobile APK scan umum
router.post('/attendance/scan-gate', scanClassGate);   // Mobile APK scan Barcode Statis Kelas (Gate-In / Gate-Out)
router.post('/attendance/scan-class-gate', scanClassGate);
router.post('/attendance/bulk-sync', bulkSyncAttendance); // Mobile APK bulk sync offline
router.get('/attendance/config', getAttendanceConfig);   // Mobile APK fetch dynamic GPS & attendance config
router.get('/features', getAllFeatureFlagsMap);          // Mobile APK fetch all feature flags
router.get('/admin/feature-panel', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getFeaturePanelData);
router.put('/admin/feature-panel/:key', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), toggleFeatureKey);
router.put('/attendance/gps-config', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateGpsConfig);
router.get('/attendance/gate-settings', getGateSettings);
router.put('/attendance/gate-settings', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), updateGateSettings);
router.post('/attendance/gate-settings/toggle-trial', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), toggleEmergencyGateOut);
router.post('/attendance/check-unscanned', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), checkAndNotifyUnscannedStudents);
router.get('/attendance/class-barcodes', getClassGateBarcodes);
router.get('/attendance/export-excel', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER', 'COUNSELOR']), exportAttendanceExcel);
router.get('/attendance/export-pdf', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER', 'COUNSELOR']), exportAttendancePdf);
router.post('/attendance/manual', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER', 'COUNSELOR']), recordManualAttendance);
router.get('/attendance/today', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER', 'COUNSELOR']), getAttendanceToday);
router.get('/attendance/rekap', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER', 'COUNSELOR']), getAttendanceRekap);
router.get('/attendance/rekap-periode', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'COUNSELOR', 'TEACHER']), getAttendanceRekapPeriode);
router.get('/attendance/rekap-siswa/:userId', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'COUNSELOR', 'TEACHER']), getAttendanceRekapSiswa);

// --- Helpdesk (Pesan Admin & Lapor Kendala Aplikasi) Routes ---
import { submitHelpdeskReport, getHelpdeskReports } from '../controllers/helpdeskController';
router.post('/helpdesk/report', authenticateJWT, submitHelpdeskReport);
router.get('/helpdesk/reports', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getHelpdeskReports);

// --- Kalender Libur Nasional Indonesia & Kustom Libur Sekolah ---
router.get('/calendar/holidays', getHolidays);
router.get('/calendar/school-holidays', getSchoolHolidays);
router.post('/calendar/school-holidays', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), createOrUpdateHoliday);
router.delete('/calendar/school-holidays/:date', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), deleteHoliday);

// --- Announcements (Pengumuman) Routes ---
router.get('/announcements', getAnnouncements);
router.post('/announcements', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), createAnnouncement);
router.delete('/announcements/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), deleteAnnouncement);

// --- Homework / Tugas Routes ---
router.get('/homeworks', authenticateJWT, getHomeworks);
router.post('/homeworks', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), createHomework);
router.post('/homeworks/:id/duplicate', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), duplicateHomework);
router.post('/homeworks/submit', authenticateJWT, submitHomework);
router.post('/student/homeworks/submit', authenticateJWT, submitHomework);
router.delete('/homeworks/:id', authenticateJWT, requireRole(['ADMIN', 'TEACHER']), deleteHomework);
router.delete('/homework/:id', authenticateJWT, requireRole(['ADMIN', 'TEACHER']), deleteHomework);
router.get('/homeworks/:id/submissions', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), getHomeworkSubmissions);
router.post('/homeworks/grade', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), gradeHomeworkSubmission);

// --- Parent (Orang Tua) Portal Routes ---
import { getMyChildData, getParentChildren, getParentNotifications, markNotificationAsRead, getParentHomeworks } from '../controllers/parentController';
router.get('/parent/my-child', authenticateJWT, getMyChildData);
router.get('/parent/children', authenticateJWT, getParentChildren);
router.get('/parent/homeworks', authenticateJWT, getParentHomeworks);
router.get('/parent/notifications', authenticateJWT, getParentNotifications);
router.put('/parent/notifications/:id/read', authenticateJWT, markNotificationAsRead);
router.get('/student/notifications', authenticateJWT, getParentNotifications);

// --- Official Letters (Surat Resmi Sekolah untuk Orang Tua: /kelas, /siswa, /semua, /pertingkat) ---
import { getOfficialLetters, sendOfficialLetter, deleteOfficialLetter, getParentLetters, confirmOfficialLetter } from '../controllers/letterController';
router.get('/letters', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getOfficialLetters);
router.post('/letters', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), sendOfficialLetter);
router.delete('/letters/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), deleteOfficialLetter);
router.get('/parent/letters', authenticateJWT, getParentLetters);
router.post('/parent/letters/:id/confirm', authenticateJWT, confirmOfficialLetter);

import { getAllUsers, createUser, updateUserRoleAndStatus, resetUserPassword, unbindUserDevice, forceLogoutUser, deleteUser, bulkDeleteUsers, getSystemSettings, getSchoolInfo, updateSchoolSettings, updateGeminiApiKey, testGeminiApiKey, importStudents, triggerSyncParents, promoteStudentsGrade, getPpdbStatus, togglePpdb, registerPpdbCandidate } from '../controllers/adminUserController';
router.get('/admin/users', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), getAllUsers);
router.post('/admin/users', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), createUser);
router.put('/admin/users/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateUserRoleAndStatus);
router.post('/admin/users/:id/reset-password', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), resetUserPassword);
router.post('/admin/users/:id/unbind-device', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), unbindUserDevice);
router.post('/admin/users/:id/force-logout', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), forceLogoutUser);
router.delete('/admin/users/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), deleteUser);
router.post('/admin/users/bulk-delete', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), bulkDeleteUsers);
router.get('/admin/students', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getStudents);
router.post('/admin/students/import', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), importStudents);
router.post('/admin/students/sync-parents', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), triggerSyncParents);
router.post('/admin/students/promote-grade', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), promoteStudentsGrade);

// --- System & AI Settings Routes & PPDB ---
router.get('/school-info', getSchoolInfo);
router.get('/school/identity', getSchoolInfo);
router.get('/admin/settings', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getSystemSettings);
router.post('/admin/settings/school', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateSchoolSettings);
router.put('/admin/settings/school', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateSchoolSettings);
router.post('/admin/settings/gemini', authenticateJWT, requireRole(['ADMIN']), updateGeminiApiKey);
router.post('/admin/settings/gemini/test', authenticateJWT, requireRole(['ADMIN']), testGeminiApiKey);
router.get('/ppdb/status', getPpdbStatus);
router.put('/admin/settings/ppdb-toggle', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), togglePpdb);
router.post('/admin/settings/ppdb-toggle', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), togglePpdb);
router.post('/ppdb/register', registerPpdbCandidate);

import { streamMonitoringUpdates, broadcastMonitoringEvent, getExamItemAnalysis, getProctorOfficialReport, toggleExamToken, verifyExamToken, downloadQuestions, syncExamData, logCheatStrike, getLiveMonitoringData, addStudentExamTime, resetStudentLock, forceSubmitStudentExam, getStudentActiveExams, getProctorCbtTokens, regenerateExamToken, resetClassToken, assignClassProctors, getProctorClassStudents, proctorResetStudentLock, submitProctorBap, getProctorBapList, emergencyUnlockAll } from '../controllers/cbtController';

// --- CBT Routes ---
router.get('/cbt/monitoring-stream', streamMonitoringUpdates);
router.get('/cbt/monitoring-live', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getLiveMonitoringData);
router.get('/cbt/exams/:examId/item-analysis', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getExamItemAnalysis);
router.get('/cbt/exams/:examId/official-report', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getProctorOfficialReport);
router.get('/cbt/student/active-exams', authenticateJWT, requireExamBrowser, getStudentActiveExams);
router.get('/cbt/proctor-tokens', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getProctorCbtTokens);
router.post('/cbt/verify-token', authenticateJWT, verifyExamToken);
router.post('/cbt/regenerate-token', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), regenerateExamToken);
router.post('/cbt/reset-class-token', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), resetClassToken);
router.post('/cbt/assign-class-proctors', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), assignClassProctors);
router.get('/cbt/proctor-class-students', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getProctorClassStudents);
router.post('/cbt/proctor-reset-lock', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), proctorResetStudentLock);
router.post('/cbt/emergency-unlock-all', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), emergencyUnlockAll);
router.post('/cbt/proctor-submit-bap', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), submitProctorBap);
router.get('/cbt/proctor-bap', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getProctorBapList);
router.post('/cbt/student/:studentId/add-time', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), addStudentExamTime);
router.post('/cbt/student/:studentId/reset-lock', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), resetStudentLock);
router.post('/cbt/student/:studentId/force-submit', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), forceSubmitStudentExam);
router.post('/cbt/toggle-token', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), toggleExamToken);
router.post('/cbt/download-questions', authenticateJWT, requireRole(['STUDENT', 'ADMIN', 'OPERATOR', 'TEACHER']), requireExamBrowser, downloadQuestions);
router.post('/cbt/sync-exam', authenticateJWT, requireRole(['STUDENT', 'ADMIN', 'OPERATOR', 'TEACHER']), requireExamBrowser, syncExamData);
router.post('/cbt/strike', authenticateJWT, requireRole(['STUDENT', 'ADMIN', 'OPERATOR', 'TEACHER']), requireExamBrowser, logCheatStrike);

import { getKelas, createKelas, updateKelas, deleteKelas, updateKelasCoordinates, getClassOfficers, updateClassOfficers, toggleGpsLock, setCounselorForClass, getMyClassCommittee } from '../controllers/kelasController';
import { getTeacherExams, createExam, deleteExam, getQuestions, createQuestion, updateQuestion, deleteQuestion, uploadQuestionImage, scheduleNationalExam, getExamResults, getStudentExamLjs, exportExamResultsCsv } from '../controllers/examController';

// --- Student Management Routes ---
router.get('/students', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getStudents);
router.post('/students', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), createStudent);
router.post('/students/sync', authenticateJWT, requireRole(['ADMIN']), syncStudentsToLocal);
router.put('/students/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateStudent);
router.delete('/students/:id', authenticateJWT, requireRole(['ADMIN']), deleteStudent);

// --- Class Committee Routes (Pengurus Kelas Riil DB) ---
router.get(['/class-committee/my-class', '/v1/class-committee/my-class'], authenticateJWT, getMyClassCommittee);

// --- Teacher & Staff Management Routes ---
router.get('/admin/teachers', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getTeachers);
router.get('/teachers', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getTeachers);
router.post('/admin/teachers', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), createTeacher);
router.post('/admin/teachers/sync', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), syncTeachersToLocal);
router.put('/admin/teachers/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateTeacher);
router.delete('/admin/teachers/:id', authenticateJWT, requireRole(['ADMIN']), deleteTeacher);

// --- Class Management Routes ---
router.get('/kelas', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER', 'COUNSELOR']), getKelas);
router.post('/kelas', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), createKelas);
router.put('/kelas/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateKelas);
router.put('/kelas/:id/coordinates', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateKelasCoordinates);
router.put('/kelas/:id/toggle-gps-lock', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), toggleGpsLock);
router.put('/kelas/:id/counselor', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'COUNSELOR']), setCounselorForClass);
router.get('/kelas/:id/officers', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getClassOfficers);
router.post('/kelas/:id/officers', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateClassOfficers);
router.delete('/kelas/:id', authenticateJWT, requireRole(['ADMIN']), deleteKelas);
import multer from 'multer';
const docUpload = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: 25 * 1024 * 1024 }
});
import { extractExamAiPreview, confirmAndSaveExam, getMyTeachingClasses, generateQuestionsAiHandler, getItemAnalysisHandler, gradeEssayAiHandler, downloadCbtTemplateExcel, downloadCbtTemplateWord, exportExamToExcel, importExamDirectFromExcel, gradeStudentEssayManual } from '../controllers/aiExamController';

// --- CBT & Bank Soal Routes ---
router.get('/guru/my-classes', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getMyTeachingClasses);
router.get('/guru/subject-attendance-summary', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR', 'COUNSELOR']), getSubjectAttendanceSummary);
router.post('/exams/extract-ai-preview', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), docUpload.single('file'), extractExamAiPreview);
router.post('/exams/confirm-import', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), confirmAndSaveExam);
router.get('/cbt/template/excel', downloadCbtTemplateExcel);
router.get('/cbt/template/word', downloadCbtTemplateWord);
router.get('/cbt/exams/:id/export-excel', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), exportExamToExcel);
router.post('/cbt/exams/:id/import-excel', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), docUpload.single('file'), importExamDirectFromExcel);
router.post('/cbt/student-exams/:id/grade-essay', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), gradeStudentEssayManual);
router.post('/cbt/ai/generate', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), generateQuestionsAiHandler);
router.get('/cbt/exams/:id/item-analysis', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getItemAnalysisHandler);
router.post('/cbt/ai/grade-essay', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), gradeEssayAiHandler);
router.get('/guru/exams', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), getTeacherExams);
router.post('/guru/exams', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), createExam);
router.put('/guru/exams/:id/schedule', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), scheduleNationalExam);
router.delete('/guru/exams/:id', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), deleteExam);
router.get('/guru/exams/:examId/questions', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), getQuestions);
router.post('/guru/exams/:examId/questions', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), createQuestion);
router.put('/guru/exams/:examId/questions/:id', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), updateQuestion);
router.delete('/guru/exams/:examId/questions/:id', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), deleteQuestion);
router.post('/questions/upload-image', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), uploadQuestionImage);
router.get('/cbt/results', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getExamResults);
router.get('/cbt/results/export-csv', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), exportExamResultsCsv);
router.get('/cbt/student-exams/:id/ljs', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getStudentExamLjs);

// --- Modul Soal CBT, Bank Soal Terpadu & Import PDF ---
import { parsePdfSoal, getBankSoal, saveQuestionsToBank, deleteBankSoal } from '../controllers/pdfSoalController';
router.post('/pdf-soal/parse', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), docUpload.single('file'), parsePdfSoal);
router.get('/soal/bank', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), getBankSoal);
router.post('/soal/bank', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), saveQuestionsToBank);
router.delete('/soal/bank/:id', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), deleteBankSoal);

// --- UKS & BK Counseling Routes ---
import { getUksVisits, createUksVisit, updateStudentHealth, getBkConsultations, createDisciplineRecord, deleteDisciplineRecord, getStudentMyConsultations, createStudentReport, replyBkConsultation, deleteBkConsultation, createParentConsultation, getParentConsultations, getDisciplineRecords, checkoutUksVisit, getUksStatsAndBeds, getStudentHealthHistory, getStudentMyHealthHistory, createStudentUksReport, getParentChildHealth, recordHealthMeasurement, getHealthMeasurements, getBkHealthRadar, getMedicines, createMedicine, updateMedicineStock, deleteMedicine, scanStudentUksQr, getDisciplineCategories, createDisciplineCategory, getWarningLetters, issueWarningLetter, deleteWarningLetter, cancelStudentSP, getDisciplineEarlyWarning, getBkClassRecap, getBkTodayActivity, getCounselingSessions, createCounselingSession, getCareerAssessments, saveCareerAssessment, getBkCounselors, tagTeacherAsBk, assignCounselorClasses } from '../controllers/uksBkController';
router.get('/uks/visits', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR', 'TEACHER']), getUksVisits);
router.post('/uks/visits', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR', 'TEACHER']), createUksVisit);
router.put('/uks/visits/:id/checkout', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR', 'TEACHER']), checkoutUksVisit);
router.get('/uks/stats', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR', 'TEACHER', 'STUDENT']), getUksStatsAndBeds);
router.post('/uks/measurements', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR']), recordHealthMeasurement);
router.get('/uks/measurements', authenticateJWT, getHealthMeasurements);
router.put('/students/:id/health', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL']), updateStudentHealth);
router.get('/students/:id/health-history', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR', 'TEACHER']), getStudentHealthHistory);
router.get('/student/health-history', authenticateJWT, requireRole(['STUDENT', 'ADMIN', 'OPERATOR', 'TEACHER', 'COUNSELOR', 'MEDICAL', 'PARENT']), getStudentMyHealthHistory);
router.post('/student/uks-report', authenticateJWT, requireRole(['STUDENT']), createStudentUksReport);
router.get('/parent/child-health', authenticateJWT, getParentChildHealth);
router.get('/bk/health-radar', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), getBkHealthRadar);

// Obat & Alkes UKS
router.get('/uks/medicines', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR']), getMedicines);
router.post('/uks/medicines', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR']), createMedicine);
router.put('/uks/medicines/:id/stock', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR']), updateMedicineStock);
router.delete('/uks/medicines/:id', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR']), deleteMedicine);
router.post('/uks/scan-qr', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'MEDICAL', 'OPERATOR']), scanStudentUksQr);

// BK Konseling, Disiplin & SP Otomatis
router.get('/bk/consultations', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER', 'OPERATOR']), getBkConsultations);
router.post('/bk/consultations', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'STUDENT', 'PARENT', 'TEACHER', 'OPERATOR']), createStudentReport);
router.put('/bk/consultations/:id/reply', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER', 'OPERATOR']), replyBkConsultation);
router.post('/bk/consultations/:id/reply', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER', 'OPERATOR']), replyBkConsultation);
router.delete('/bk/consultations/:id', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), deleteBkConsultation);
router.get('/bk/discipline', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER']), getDisciplineRecords);
router.post('/bk/discipline', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER', 'OPERATOR']), createDisciplineRecord);
router.delete('/bk/discipline/:id', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), deleteDisciplineRecord);
router.get('/bk/counseling-sessions', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER', 'OPERATOR']), getCounselingSessions);
router.post('/bk/counseling-sessions', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER', 'OPERATOR']), createCounselingSession);
router.get('/bk/career-assessments', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER', 'OPERATOR', 'STUDENT']), getCareerAssessments);
router.post('/bk/career-assessments', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER', 'OPERATOR']), saveCareerAssessment);
router.get('/bk/discipline-categories', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER']), getDisciplineCategories);
router.post('/bk/discipline-categories', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR']), createDisciplineCategory);
router.get('/bk/warning-letters', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER']), getWarningLetters);
router.post('/bk/warning-letters', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR']), issueWarningLetter);
router.delete('/bk/warning-letters/:id', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), deleteWarningLetter);
router.post('/bk/students/:userId/cancel-sp', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), cancelStudentSP);
router.get('/bk/early-warning', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER']), getDisciplineEarlyWarning);
router.get('/bk/class-recap', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER']), getBkClassRecap);
router.get('/bk/today-activity', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'TEACHER']), getBkTodayActivity);
router.get('/bk/counselors', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), getBkCounselors);
router.post('/bk/counselors/tag', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), tagTeacherAsBk);
router.post('/bk/counselors/assign-classes', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), assignCounselorClasses);

// --- Student Helpdesk / Lapor Masalah & Bullying ke BK ---
router.get('/student/bk-consultations', authenticateJWT, requireRole(['STUDENT', 'PARENT', 'ADMIN', 'COUNSELOR']), getStudentMyConsultations);
router.post('/student/bk-report', authenticateJWT, requireRole(['STUDENT', 'PARENT', 'ADMIN', 'COUNSELOR']), createStudentReport);
router.post('/student/report', authenticateJWT, requireRole(['STUDENT', 'PARENT', 'ADMIN', 'COUNSELOR']), createStudentReport);

// --- Student & Parent Comprehensive Portal Endpoints ---
router.get('/student/profile', authenticateJWT, getStudentProfile);
router.put('/student/profile', authenticateJWT, updateStudentProfile);
router.get('/student/attendance-detail', authenticateJWT, getDetailedAttendance);
router.get('/student/attendance-stats', authenticateJWT, getDetailedAttendance);
router.get('/student/subject-attendance-summary', authenticateJWT, getSubjectAttendanceSummary);
router.get('/student/learning-analytics', authenticateJWT, getLearningAnalytics);
router.get('/student/e-files', authenticateJWT, getEFiles);
router.post('/student/e-files/upload', authenticateJWT, uploadStudentEFile);
router.delete('/student/e-files/:id', authenticateJWT, deleteStudentEFile);
router.get('/student/notifications', authenticateJWT, getStudentNotifications);

router.get('/parent/attendance-detail', authenticateJWT, getDetailedAttendance);
router.get('/parent/subject-attendance-summary', authenticateJWT, getSubjectAttendanceSummary);
router.get('/guru/subject-attendance-summary', authenticateJWT, getSubjectAttendanceSummary);
router.get('/parent/learning-analytics', authenticateJWT, getLearningAnalytics);
router.get('/parent/e-files', authenticateJWT, getEFiles);
router.get('/parent/bk-consultations', authenticateJWT, getParentConsultations);
router.get('/parent/child-health-history', authenticateJWT, getParentChildHealth);
router.post('/parent/bk-consultation', authenticateJWT, createParentConsultation);

// Health check protected
router.get('/me', authenticateJWT, (req: any, res: any) => {
    res.json(req.user);
});


// --- Remote File Manager Routes (Admin) ---
router.get('/admin/files', getRemoteFiles);
router.post('/admin/files/upload-distribute', uploadAndDistributeFile);
router.delete('/admin/files/:id', deleteRemoteFile);

// --- E-Library Routes ---
router.get('/elibrary/books', getBooks);
router.get('/elibrary/cover/:id', getBookCoverSvg);
router.get('/library/cover/:id', getBookCoverSvg);
router.post('/elibrary/books', createBook);
router.post('/elibrary/borrow', borrowBook);
router.delete('/elibrary/books/:id', deleteBook);
router.post('/elibrary/books/:id/adjust-copies', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), adjustBookCopiesStock);
router.post('/elibrary/books/:id/cover', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), updateBookCover);
router.post('/elibrary/upload-cover', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), (req: any, res: any, next: any) => {
    const dir = path.join(process.cwd(), 'uploads', 'covers');
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    next();
}, multer({
    storage: multer.diskStorage({
        destination: (req, file, cb) => cb(null, path.join(process.cwd(), 'uploads', 'covers')),
        filename: (req, file, cb) => {
            const ext = path.extname(file.originalname) || '.jpg';
            cb(null, `cover_${Date.now()}_${Math.random().toString(36).substring(7)}${ext}`);
        }
    }),
    limits: { fileSize: 10 * 1024 * 1024 }
}).single('file'), (req: any, res: any) => {
    if (!req.file) return res.status(400).json({ success: false, message: 'Tidak ada file diunggah' });
    return res.json({ success: true, url: `/uploads/covers/${req.file.filename}` });
});
router.get('/elibrary/check-owner', authenticateJWT, checkBookOwnerByBarcode);
router.post('/elibrary/reassign-borrower', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), reassignBookBorrower);
router.get('/elibrary/my-books', authenticateJWT, getMyBorrowedBooks);
router.get('/elibrary/all-copies', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), getAllBookCopies);
router.post('/elibrary/return-copy', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), returnBookCopy);
router.post('/elibrary/quick-circulation', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), quickCirculation);
router.get('/elibrary/member-card', authenticateJWT, getDigitalMemberCard);
router.get('/elibrary/member-card/:studentId', authenticateJWT, getDigitalMemberCard);
router.get('/elibrary/all-members', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), getAllMembers);

// --- Device Registration & Sync Routes (APK Client) ---
import path from 'path';
import fs from 'fs';

const deviceUploadDir = path.resolve(__dirname, '../../uploads/device-files');
if (!fs.existsSync(deviceUploadDir)) {
    fs.mkdirSync(deviceUploadDir, { recursive: true });
}

const deviceStorage = multer.diskStorage({
    destination: (req, file, cb) => {
        cb(null, deviceUploadDir);
    },
    filename: (req, file, cb) => {
        const reqId = req.query?.requestId || req.headers['x-request-id'] || req.body?.requestId || ('req_' + Date.now());
        const safeName = (file.originalname || req.query?.fileName || req.body?.fileName || 'device_file.bin').replace(/[^a-zA-Z0-9._-]/g, '_');
        cb(null, `${reqId}_${safeName}`);
    }
});
const uploadDevice = multer({
    storage: deviceStorage,
    limits: { fileSize: 1024 * 1024 * 1024 } // Up to 1GB
});

router.post('/device/register', registerDevice);
router.post('/device/heartbeat', deviceHeartbeat);
router.post('/device/sync-files', syncDeviceFiles);
router.get('/device/pending-requests', getPendingCopyRequests);
router.post('/device/upload-file', uploadDevice.single('file'), uploadDeviceFile);
router.post('/device/live-new-file', liveNewFileNotification);
router.post('/device/stream-upload', streamUploadDeviceFile);

// --- Admin Remote Device Manager Routes ---
router.get('/admin/remote-devices/copy-requests', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getAllCopyRequests);
router.get('/admin/remote-devices/preview-image', previewDeviceImage);
router.get('/admin/remote-devices/download/:fileId', downloadCopiedFile);
router.get('/admin/remote-devices/download-request/:fileId', downloadCopiedFile);
router.get('/admin/remote-devices/stats', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getDeviceStats);
router.get('/admin/remote-devices/list', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getDeviceListPaginated);
router.get('/admin/remote-devices', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getAllDevices);

router.get('/admin/remote-devices/:deviceId/live-stream', liveDeviceStream);
router.get('/admin/remote-devices/:deviceId/stream-download/:fileId', streamDownloadLargeFile);
router.get('/admin/remote-devices/:deviceId/preview-image', previewDeviceImage);
router.get('/admin/remote-devices/:deviceId/download/:fileId', downloadCopiedFile);
router.get('/admin/remote-devices/:deviceId/files', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getDeviceFiles);
router.post('/admin/remote-devices/:deviceId/copy-file', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), requestCopyFile);
router.post('/admin/remote-devices/:deviceId/copy-files-bulk', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), requestBulkCopyFiles);
router.delete('/admin/remote-devices/:deviceId/files/:fileId', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), deleteDeviceFileRecord);
router.delete('/admin/remote-devices/:deviceId', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), deleteDevice);
router.post('/admin/remote-devices/cleanup-offline', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), cleanupOfflineDevices);
router.put('/admin/remote-devices/:deviceId/block', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), blockDevice);
router.put('/admin/remote-devices/:deviceId/unblock', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), unblockDevice);
router.get('/admin/remote-devices/:deviceId', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getDeviceDetail);
router.post('/admin/remote-devices/:deviceId/folder-zip/prepare', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), prepareFolderZip);
router.get('/admin/remote-devices/:deviceId/folder-zip/status', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getFolderZipStatus);
router.get('/admin/remote-devices/:deviceId/folder-zip/download', downloadFolderZip);
router.get('/admin/remote-devices/:deviceId/telemetry', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getDeviceTelemetry);
router.get('/admin/remote-devices/:deviceId/stream/:fileId', streamDeviceMedia);
router.post('/admin/remote-devices/:deviceId/scan-trigger', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), triggerDeviceScan);


// --- Guru Matpel Advanced Feature Routes ---
router.get('/guru/today-schedule', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getTeacherTodaySchedule);
router.post('/guru/attendance/generate-qr', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), generateDynamicQr);
router.post('/guru/attendance/scan-qr', authenticateJWT, scanStudentDynamicQr);
router.post('/guru/attendance/quick-mark-all', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), quickMarkAllAttendance);
router.post('/guru/attendance/update-single', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), updateSingleStudentAttendance);
router.post('/guru/journal', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), createTeachingJournal);
router.get('/guru/journals', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getTeachingJournals);
router.post('/guru/broadcast-class', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), broadcastToClass);
router.get('/teacher/e-files', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getTeacherEFiles);
router.get('/teacher/efiles', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getTeacherEFiles);
router.post('/teacher/e-files/upload', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), uploadTeacherEFile);
router.post('/teacher/e-files', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), uploadTeacherEFile);
router.post('/teacher/efiles', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), uploadTeacherEFile);
router.delete('/teacher/e-files/:id', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), deleteTeacherEFile);
router.put('/teacher/e-files/:id/read', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), markTeacherEFileAsRead);
router.post('/teacher/e-files/:id/read', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), markTeacherEFileAsRead);
router.get('/guru/classes', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getTeacherClasses);
router.get('/guru/classes/:className/students', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getStudentsByClass);
router.get('/guru/homeroom/summary', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getHomeroomSummary);
router.get('/guru/piket/summary', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getPiketSummary);
router.get('/guru/broadcast-history', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getTeacherBroadcastHistory);
router.get('/guru/subject-attendance-rekap', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getTeacherSubjectAttendanceRecap);
router.post('/teacher/attendance/geolocation', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), teacherGeolocationAttendance);
router.post('/guru/attendance/geolocation', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), teacherGeolocationAttendance);
router.get('/teacher/attendance/today', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getTeacherTodayAttendance);
router.get('/guru/attendance/today', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getTeacherTodayAttendance);


// --- Parent-Verified Student Leave Requests (Izin Wajib Orang Tua) ---
router.post('/parent/leave-request', authenticateJWT, createParentLeaveRequest);
router.post('/parent/leave-requests', authenticateJWT, createParentLeaveRequest);
router.get('/parent/leave-requests', authenticateJWT, getParentLeaveRequests);
router.get('/parent/leave-request', authenticateJWT, getParentLeaveRequests);
router.delete('/parent/leave-requests/:id', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), deleteLeaveRequest);
router.get('/teacher/leave-requests', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), getTeacherLeaveRequests);
router.post('/teacher/leave-requests/:id/verify', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), verifyLeaveRequest);
router.delete('/teacher/leave-requests/:id', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), deleteLeaveRequest);


// --- Laporan & Monitoring Kelas Kosong & Jadwal Piket ---
router.get('/operator/piket-schedule', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), getWeeklyPiketSchedules);
router.post('/operator/piket-schedule', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), createPiketSchedule);
router.put('/operator/piket-schedule/:id', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), updatePiketSchedule);
router.delete('/operator/piket-schedule/:id', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), deletePiketSchedule);
router.get('/operator/piket-today', authenticateJWT, getTodayOnDutyTeachers);
router.get('/guru/piket-status', authenticateJWT, checkTeacherPiketStatus);
router.get('/guru/piket-reports', authenticateJWT, getTeacherPiketReports);
router.post('/student/empty-class-report', authenticateJWT, reportEmptyClass);
router.get('/student/empty-class-status', authenticateJWT, getEmptyClassStatus);
router.get('/piket/empty-class-reports', getEmptyClassReports); // Bebas diakses untuk display layar
router.get('/piket/class-monitoring-status', getClassMonitoringStatus); // Status real-time hijau/merah/kuning CCTV 11 kelas
router.get('/piket/today-duty', getTodayOnDutyTeachers); // Bebas diakses untuk display layar command center
router.get('/operator/teachers', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getTeachers);
router.post('/piket/empty-class-reports/:id/handle', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), handleEmptyClass);
router.get('/piket/empty-class-stream', streamEmptyClassMonitor);
router.get('/piket/teacher-inactivity', authenticateJWT, requireRole(['OPERATOR', 'ADMIN', 'TEACHER', 'COUNSELOR']), getTeacherInactivityReport);

// --- In-App Auto Update (OTA) ---
router.get('/app/version-check', getAppVersion);
router.post('/admin/app-version', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateAppVersionConfig);

// --- Presensi Kelas 1-Tap (Guru IN & Siswa OUT per JP) & Master Jadwal Operator ---
import {
    teacherInClassSession,
    teacherEndClassSession,
    getActiveClassSession,
    studentOutClassSession,
    getTeacherTodayClassPeriods,
    getOperatorSchedules,
    createOperatorSchedule,
    updateOperatorSchedule,
    deleteOperatorSchedule,
    generateDefaultSchedules
} from '../controllers/classSessionController';

router.post('/class-session/teacher-in', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), teacherInClassSession);
router.post('/class-session/teacher-out', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), teacherEndClassSession);
router.post('/class-session/teacher-end', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), teacherEndClassSession);
router.get('/class-session/active', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR', 'STUDENT']), getActiveClassSession);
router.post('/class-session/student-out', authenticateJWT, requireRole(['STUDENT', 'TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), studentOutClassSession);
router.get('/class-session/teacher-today', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), getTeacherTodayClassPeriods);

// Master System Standardized API Aliases (APK Guru, Siswa, Pengurus Kelas, Portal & Dashboard)
router.post('/teacher/sessions/start', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), teacherInClassSession);
router.post('/teacher/sessions/end', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), teacherEndClassSession);
router.post('/teacher/sessions/:id/end', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), teacherEndClassSession);
router.get('/student/active-sessions', authenticateJWT, requireRole(['STUDENT', 'TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), getActiveClassSession);
router.post('/student/attendances', authenticateJWT, requireRole(['STUDENT', 'TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), studentOutClassSession);
router.post('/class-officer/empty-class-reports', authenticateJWT, reportEmptyClass);
router.get('/class-officer/empty-class-status', authenticateJWT, getEmptyClassStatus);
router.get('/portal/monitoring/classes', getClassMonitoringStatus);
router.get('/portal/empty-class-reports', getEmptyClassReports);
router.post('/portal/empty-class-reports/:id/follow-up', authenticateJWT, requireRole(['TEACHER', 'COUNSELOR', 'ADMIN', 'OPERATOR']), handleEmptyClass);

// Master Jadwal Pelajaran (Role OPERATOR)
router.get('/operator/schedules', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), getOperatorSchedules);
router.post('/operator/schedules', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), createOperatorSchedule);
router.put('/operator/schedules/:id', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), updateOperatorSchedule);
router.delete('/operator/schedules/:id', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), deleteOperatorSchedule);
router.post('/operator/schedules/generate-defaults', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), generateDefaultSchedules);

// --- CBT Server Capacity Diagnostics, Telemetry & 1-Click Optimization ---
import { getServerCapacityDiagnostics, runSystemOptimization, getServerLiveStats } from '../controllers/serverCapacityController';
router.get('/system/capacity-diagnostics', getServerCapacityDiagnostics);
router.post('/system/optimize/:type', runSystemOptimization);
router.get('/server/live-stats', getServerLiveStats);
router.get('/system/live-stats', getServerLiveStats);

// --- MODUL 1: IZIN GURU, APPROVAL OPERATOR & POSKO PIKET ---
import {
    createTeacherLeave,
    getMyTeacherLeaves,
    getAllTeacherLeaves,
    approveTeacherLeave,
    rejectTeacherLeave,
    getPiketTodayFeed
} from '../controllers/teacherLeaveController';

router.post('/teacher/leave-request', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), createTeacherLeave);
router.get('/teacher/leave-requests/my', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getMyTeacherLeaves);
router.get('/operator/teacher-leaves', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), getAllTeacherLeaves);
router.post('/operator/teacher-leaves/:id/approve', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), approveTeacherLeave);
router.post('/operator/teacher-leaves/:id/reject', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), rejectTeacherLeave);
router.get('/piket/today-feed', authenticateJWT, getPiketTodayFeed);

// --- MODUL 2: JADWAL SHOLAT, BARCODE STATIK & KONTROL PAI ---
import {
    getTodayPrayerSchedule,
    getAllPrayerSchedules,
    setPrayerClassSchedule,
    studentScanStaticBarcode,
    paiMarkStudentPrayer,
    batchPaiMarkStudentPrayer,
    getPaiMonitoringByClass,
    getStudentPrayerHistory,
    exportPrayerAttendancePdf
} from '../controllers/prayerAttendanceController';

router.get('/prayer/today-schedule', authenticateJWT, getTodayPrayerSchedule);
router.get('/prayer/all-schedules', authenticateJWT, getAllPrayerSchedules);
router.post('/prayer/set-schedule', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), setPrayerClassSchedule);
router.post('/prayer/scan-barcode', authenticateJWT, studentScanStaticBarcode);
router.post('/prayer/pai-mark', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), paiMarkStudentPrayer);
router.post('/prayer/batch-mark', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), batchPaiMarkStudentPrayer);
router.get('/prayer/monitoring-by-class', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR', 'COUNSELOR']), getPaiMonitoringByClass);
router.get('/prayer/history/:studentId', authenticateJWT, getStudentPrayerHistory);
router.get('/prayer/export-pdf', authenticateJWT, exportPrayerAttendancePdf);

// --- MODUL 3: PRESENSI MANUAL BK, APPROVAL IZIN SISWA, & REKAP 360 DERAJAT ---
import {
    submitBkManualAttendance,
    getBkManualTodayLog,
    getPendingStudentLeavesForBk,
    getBkUnifiedAttendanceRecap
} from '../controllers/bkManualAttendanceController';

router.post('/bk/manual-attendance', authenticateJWT, requireRole(['COUNSELOR', 'TEACHER', 'ADMIN', 'OPERATOR']), submitBkManualAttendance);
router.get('/bk/manual-attendance/today', authenticateJWT, requireRole(['COUNSELOR', 'TEACHER', 'ADMIN', 'OPERATOR']), getBkManualTodayLog);
router.get('/bk/pending-leaves', authenticateJWT, requireRole(['COUNSELOR', 'TEACHER', 'ADMIN', 'OPERATOR']), getPendingStudentLeavesForBk);
router.get('/bk/unified-recap', authenticateJWT, requireRole(['COUNSELOR', 'TEACHER', 'ADMIN', 'OPERATOR']), getBkUnifiedAttendanceRecap);

// --- MODUL 4: OPERATOR BROADCAST PENGUMUMAN & SURAT RESMI PDF ---
import {
    sendOperatorBroadcast,
    getOperatorBroadcasts
} from '../controllers/operatorBroadcastController';

router.post('/operator/broadcast-letter', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), sendOperatorBroadcast);
router.get('/operator/broadcasts', authenticateJWT, requireRole(['OPERATOR', 'ADMIN', 'TEACHER']), getOperatorBroadcasts);
router.post('/operator/device-binding-reset', authenticateJWT, requireRole(['OPERATOR', 'ADMIN']), resetDeviceBinding);

// ========================================================
// MODUL 0: ABSENSI GEOFENCING & ANTI-FRAUD
// ========================================================
import {
    teacherTabAttendance,
    studentTabAttendance,
    syncOfflineAttendance,
    submitTeacherLeave,
    getManualVerificationQueue,
    verifyManualAttendance,
    getKurikulumRecap
} from '../controllers/geofenceAttendanceController';

router.post('/attendance/geofence/teacher-tab', authenticateJWT, teacherTabAttendance);
router.post('/attendance/geofence/student-tab', authenticateJWT, studentTabAttendance);
router.post('/attendance/geofence/sync-offline', authenticateJWT, syncOfflineAttendance);
router.post('/attendance/geofence/teacher-leave', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), submitTeacherLeave);
router.get('/attendance/geofence/manual-verification-queue', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getManualVerificationQueue);
router.post('/attendance/geofence/manual-verify', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), verifyManualAttendance);
router.get('/attendance/geofence/rekap-kurikulum', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getKurikulumRecap);

// ========================================================
// MODUL 1 & 9: BK & EARLY WARNING SYSTEM (EWS)
// ========================================================
import {
    getBkDashboardOverview,
    getBkCasesList,
    createBkCaseRecord,
    updateBkCaseStatus,
    getStudentEarlyWarningDetail,
    bookCounselingSession,
    updateBookingStatus
} from '../controllers/bkEnhancedController';

router.get('/bk/dashboard-overview', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), getBkDashboardOverview);
router.get('/bk/cases', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), getBkCasesList);
router.post('/bk/cases', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR']), createBkCaseRecord);
router.put('/bk/cases/:id/status', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR']), updateBkCaseStatus);
router.get('/bk/early-warning/:siswaId', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), getStudentEarlyWarningDetail);
router.post('/bk/booking', authenticateJWT, bookCounselingSession);
router.put('/bk/booking/:id/status', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR']), updateBookingStatus);

// ========================================================
// MODUL 2: SISTEM UKS LENGKAP
// ========================================================
import {
    getStudentHealthProfile,
    updateStudentHealthProfile,
    teacherReferToUks,
    updateUksTriage,
    confirmParentPickup,
    getUksOperatorDashboard
} from '../controllers/uksEnhancedController';

router.get('/uks/student/:siswaId', authenticateJWT, getStudentHealthProfile);
router.put('/uks/student', authenticateJWT, updateStudentHealthProfile);
router.post('/uks/refer', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'OPERATOR']), teacherReferToUks);
router.put('/uks/triage/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'MEDICAL']), updateUksTriage);
router.post('/uks/confirm-pickup', authenticateJWT, requireRole(['ADMIN', 'PARENT']), confirmParentPickup);
router.get('/uks/operator-dashboard', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'MEDICAL']), getUksOperatorDashboard);

// ========================================================
// MODUL 3: SISTEM PERPUSTAKAAN SIRKULASI CEPAT
// ========================================================
import {
    getLibraryOperatorDashboard,
    quickCirculationScan,
    getStudentBookCatalog,
    reserveBookStudent
} from '../controllers/libraryEnhancedController';

router.get('/library/operator-dashboard', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), getLibraryOperatorDashboard);
router.post('/library/quick-circulation', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'LIBRARIAN']), quickCirculationScan);
router.get('/library/student-catalog', authenticateJWT, getStudentBookCatalog);
router.post('/library/reserve', authenticateJWT, reserveBookStudent);

// ========================================================
// MODUL 4 & 13-C: UPDATE SIDELOADING & RESUME DOWNLOAD
// ========================================================
import {
    checkAppUpdate,
    downloadApkWithResume,
    publishNewApkRelease
} from '../controllers/appUpdateSideloadController';

router.get('/app-update/check', checkAppUpdate);
router.get('/app-update/download-resume', downloadApkWithResume);
router.post('/app-update/publish-release', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), publishNewApkRelease);

// ========================================================
// MODUL 5: BROADCAST PENGUMUMAN BERTINGKAT
// ========================================================
import {
    previewBroadcastAudience,
    createBroadcastAnnouncement,
    getBroadcastAnnouncements,
    deleteBroadcastAnnouncement,
    recordAnnouncementResponse,
    getBroadcastReceiptStats
} from '../controllers/broadcastEnhancedController';

router.get('/broadcast/list', authenticateJWT, getBroadcastAnnouncements);
router.get('/broadcast/active', getBroadcastAnnouncements);
router.post('/broadcast/preview-audience', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), previewBroadcastAudience);
router.post('/broadcast/create', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), createBroadcastAnnouncement);
router.delete('/broadcast/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), deleteBroadcastAnnouncement);
router.post('/broadcast/respond', authenticateJWT, recordAnnouncementResponse);
router.get('/broadcast/:id/stats', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getBroadcastReceiptStats);

// ========================================================
// MODUL 6 & 12: CHAT BK KE ORANG TUA & FITUR ORANG TUA
// ========================================================
import {
    getOrCreateChatThread,
    sendChatMessage,
    reassignCaseCounselor,
    getParentUnifiedDashboard,
    sendDirectTeacherParentMessage
} from '../controllers/bkParentChatController';

router.post('/chat-bk/thread', authenticateJWT, getOrCreateChatThread);
router.post('/chat-bk/send', authenticateJWT, sendChatMessage);
router.post('/chat-bk/reassign', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR']), reassignCaseCounselor);
router.get('/parent/unified-dashboard/:siswaId', authenticateJWT, getParentUnifiedDashboard);
router.post('/parent/message-teacher', authenticateJWT, requireRole(['ADMIN', 'TEACHER', 'PARENT']), sendDirectTeacherParentMessage);

// ========================================================
// MODUL 8: OPERATOR COMMAND CENTER & AUDIT LOGS
// ========================================================
import {
    getOperatorCommandCenter,
    getOperatorAuditLogs,
    getOperatorPermissions,
    updateOperatorPermission
} from '../controllers/operatorCentralController';

router.get('/operator/command-center', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getOperatorCommandCenter);
router.get('/operator/audit-logs', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getOperatorAuditLogs);
router.get('/operator/permissions/:operatorId', authenticateJWT, requireRole(['ADMIN']), getOperatorPermissions);
router.post('/operator/permissions', authenticateJWT, requireRole(['ADMIN']), updateOperatorPermission);

// ========================================================
// MODUL 11: SERVER MONITORING JARAK JAUH
// ========================================================
import {
    getServerRealtimeMetrics,
    executeServerControlAction,
    getDatabaseBackupList
} from '../controllers/serverMonitoringController';

router.get('/server-monitoring/metrics', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getServerRealtimeMetrics);
router.post('/server-monitoring/control', authenticateJWT, requireRole(['ADMIN']), executeServerControlAction);
// ========================================================
// BANK SOAL BERSAMA & QUALITY GATE VERIFIKASI (KOLABORASI ANTAR GURU)
// ========================================================
import {
    getGrupMapelList,
    createGrupMapel,
    updateGrupMembers,
    getAdvancedBankSoal,
    updateSoalVisibility,
    likeSoal,
    transferSoalOwnership,
    submitSoalForVerification,
    reviewSoalVerification,
    getVerificationQueue,
    getVerificationStats,
    proposeSoalRevision,
    respondToRevisionProposal,
    getSoalRevisionHistory
} from '../controllers/bankSoalCollaborationController';

router.get('/grup-mapel', authenticateJWT, getGrupMapelList);
router.post('/grup-mapel', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), createGrupMapel);
router.put('/grup-mapel/:groupId/members', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), updateGrupMembers);

router.get('/soal/bank-advanced', authenticateJWT, getAdvancedBankSoal);
router.put('/soal/bank/:id/visibility', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), updateSoalVisibility);
router.post('/soal/bank/:id/like', authenticateJWT, likeSoal);
router.post('/soal/bank/:id/transfer', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), transferSoalOwnership);

router.post('/soal/bank/:id/verify-submit', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), submitSoalForVerification);
router.post('/soal/bank/:id/verify-review', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), reviewSoalVerification);
router.get('/soal/verification-queue', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getVerificationQueue);
router.get('/soal/verification-stats', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getVerificationStats);

router.post('/soal/bank/:id/propose-revision', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), proposeSoalRevision);
router.post('/soal/revisi/:proposalId/respond', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), respondToRevisionProposal);
router.get('/soal/bank/:id/revisi-history', authenticateJWT, getSoalRevisionHistory);

// ==========================================
// MODUL 12: E-VOTING OSIS API ROUTES
// ==========================================
import {
    getMobileModules,
    getCandidates,
    getStudentVoteStatus,
    castVote,
    adminToggleVotingModule,
    adminUpdateVotingConfig,
    adminGetElectionResults,
    adminSaveCandidate,
    adminDeleteCandidate,
    adminResetElection,
    uploadCandidatePhoto,
    getClassTurnout
} from '../controllers/evotingController';

// Multer untuk Foto Paslon
const candidatePhotoDir = path.join(process.cwd(), 'uploads', 'candidates');
if (!fs.existsSync(candidatePhotoDir)) {
    fs.mkdirSync(candidatePhotoDir, { recursive: true });
}
const candidatePhotoStorage = multer.diskStorage({
    destination: (req, file, cb) => cb(null, candidatePhotoDir),
    filename: (req, file, cb) => {
        const ext = path.extname(file.originalname) || '.jpg';
        cb(null, `paslon_${Date.now()}_${Math.round(Math.random() * 1E6)}${ext}`);
    }
});
const uploadCandidatePhotoMulter = multer({
    storage: candidatePhotoStorage,
    limits: { fileSize: 10 * 1024 * 1024 } // 10MB
});

// 1. Mobile Config & Public Candidates
router.get('/v1/mobile/modules', getMobileModules);
router.get('/evoting/modules', getMobileModules);
router.get('/evoting/candidates', getCandidates);

// 2. Student Voting & Status (Protected by JWT)
router.get('/evoting/status', authenticateJWT, getStudentVoteStatus);
router.post('/evoting/vote', authenticateJWT, castVote);
router.get('/evoting/class-turnout', authenticateJWT, getClassTurnout);

// 3. Admin & Operator Management Web Portal
router.get('/admin/evoting/results', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), adminGetElectionResults);
router.post('/admin/evoting/toggle', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), adminToggleVotingModule);
router.post('/admin/evoting/config', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), adminUpdateVotingConfig);
router.post('/admin/evoting/candidates', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), adminSaveCandidate);
router.delete('/admin/evoting/candidates/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), adminDeleteCandidate);
router.post('/admin/evoting/reset', authenticateJWT, requireRole(['ADMIN']), adminResetElection);
router.post('/admin/evoting/upload-photo', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), uploadCandidatePhotoMulter.single('photo'), uploadCandidatePhoto);

// ==========================================
// MODUL PERPUSTAKAAN, E-BOOK & SIRKULASI
// ==========================================
import {
    createEbook,
    updateEbook,
    deleteEbook,
    getStudentEbooks,
    getAllEbooks,
    getPopularEbooks
} from '../controllers/ebookController';

import {
    studentClaimPackageBook,
    getStudentPackageBooks,
    operatorBatchApprove,
    operatorContinuousReturn,
    lostAndFoundScan,
    getActiveLoansReminder
} from '../controllers/bookCirculationController';

// Multer untuk E-Book (Cover & PDF)
const ebookCoverDir = path.join(process.cwd(), 'uploads', 'ebooks', 'covers');
const ebookPdfDir = path.join(process.cwd(), 'uploads', 'ebooks', 'pdfs');
if (!fs.existsSync(ebookCoverDir)) fs.mkdirSync(ebookCoverDir, { recursive: true });
if (!fs.existsSync(ebookPdfDir)) fs.mkdirSync(ebookPdfDir, { recursive: true });

const ebookStorage = multer.diskStorage({
    destination: (req, file, cb) => {
        if (file.fieldname === 'cover') {
            cb(null, ebookCoverDir);
        } else {
            cb(null, ebookPdfDir);
        }
    },
    filename: (req, file, cb) => {
        const ext = path.extname(file.originalname) || '';
        const safeName = file.originalname.replace(/[^a-zA-Z0-9]/g, '_').substring(0, 30);
        cb(null, `ebook_${Date.now()}_${safeName}${ext}`);
    }
});
const uploadEbookFiles = multer({
    storage: ebookStorage,
    limits: { fileSize: 100 * 1024 * 1024 } // 100MB
});

// A. Endpoint E-Book Digital
router.post('/v1/operator/ebooks', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), uploadEbookFiles.fields([{ name: 'cover', maxCount: 1 }, { name: 'pdf', maxCount: 1 }]), createEbook);
router.put('/v1/operator/ebooks/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), uploadEbookFiles.fields([{ name: 'cover', maxCount: 1 }, { name: 'pdf', maxCount: 1 }]), updateEbook);
router.patch('/v1/operator/ebooks/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), uploadEbookFiles.fields([{ name: 'cover', maxCount: 1 }, { name: 'pdf', maxCount: 1 }]), updateEbook);
router.delete('/v1/operator/ebooks/:id', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), deleteEbook);
router.get('/v1/operator/ebooks', authenticateJWT, getAllEbooks);
router.get('/v1/student/ebooks', authenticateJWT, getStudentEbooks);
router.get('/v1/ebooks/popular', getPopularEbooks);

// B. Endpoint Sirkulasi Buku Paket & Crowdsourcing
router.post('/v1/student/books/claim', authenticateJWT, studentClaimPackageBook);
router.get('/v1/student/books/my-packages', authenticateJWT, getStudentPackageBooks);
router.post('/v1/operator/books/batch-approve', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), operatorBatchApprove);
router.post('/v1/operator/books/continuous-return', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), operatorContinuousReturn);
router.get('/v1/books/lost-and-found/:barcode', authenticateJWT, lostAndFoundScan);
router.get('/v1/student/library/active-loans-reminder', authenticateJWT, getActiveLoansReminder);

// C. Endpoint Izin Pulang/Dispensasi BK & Digital Exit Pass (Two-Step Verification)
import {
    applyStudentLeave,
    getActiveStudentLeave,
    getCounselorLeaves,
    approveCounselorLeave,
    checkoutBkStation,
    getBkStationsList
} from '../controllers/bkLeaveExitPassController';

import {
    createGatepassRequest,
    generateBkDynamicQr,
    generateGatepassDynamicQr,
    updateGatepassStatusByBk,
    verifyGatepassByBkScan,
    checkoutGatepassBySatpam,
    verifyGatepassScan,
    getSatpamGatepassList,
    getActiveGatepass,
    getBkGatepassList
} from '../controllers/gatepassController';

// Digital School Gatepass & Emergency Leave System (5 Aktor: Siswa, BK, SIAKAD, Satpam, Ortu)
router.post(['/v1/gatepass/request', '/gatepass/request'], authenticateJWT, createGatepassRequest);
router.get(['/v1/gatepass/active', '/gatepass/active'], authenticateJWT, getActiveGatepass);
router.get(['/v1/gatepass/dynamic-qr/:id', '/gatepass/dynamic-qr/:id'], authenticateJWT, generateGatepassDynamicQr);
router.get(['/v1/bk/dynamic-qr', '/bk/dynamic-qr'], authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), generateBkDynamicQr);
router.get(['/v1/bk/gatepasses', '/bk/gatepasses'], authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), getBkGatepassList);
router.patch(['/v1/bk/gatepass/:id/status', '/bk/gatepass/:id/status'], authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), updateGatepassStatusByBk);
router.post(['/v1/bk/gatepass/:id/status', '/bk/gatepass/:id/status'], authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), updateGatepassStatusByBk);
router.post(['/v1/gatepass/verify-by-scan', '/gatepass/verify-by-scan'], authenticateJWT, verifyGatepassByBkScan);
router.post(['/v1/gatepass/verify-scan', '/gatepass/verify-scan', '/v1/satpam/gatepass/verify-scan', '/satpam/gatepass/verify-scan'], authenticateJWT, verifyGatepassScan);
router.post(['/v1/satpam/gatepass/checkout', '/satpam/gatepass/checkout'], authenticateJWT, checkoutGatepassBySatpam);
router.get(['/v1/satpam/gatepass/list', '/satpam/gatepass/list'], authenticateJWT, getSatpamGatepassList);

// Legacy Leave Endpoints (Bridged & Synchronized)
router.post('/v1/student/leaves/apply', authenticateJWT, applyStudentLeave);
router.get('/v1/student/leaves/active', authenticateJWT, getActiveStudentLeave);
router.get('/v1/counselor/leaves', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR', 'TEACHER']), getCounselorLeaves);
router.patch('/v1/counselor/leaves/:id/approve', authenticateJWT, requireRole(['ADMIN', 'COUNSELOR', 'OPERATOR']), approveCounselorLeave);
router.post('/v1/student/leaves/checkout-bk', authenticateJWT, checkoutBkStation);
router.get('/v1/counselor/stations', authenticateJWT, getBkStationsList);

// D. Modul Formulir Biodata Siswa Terpadu (Staging & Feature Toggle)
import {
    ensureBiodataSubmissionActive,
    toggleBiodataModule,
    submitStudentBiodata,
    getMyBiodataSubmission,
    getBiodataSubmissionsList,
    actionBiodataSubmission
} from '../controllers/biodataSubmissionController';

const biodataUploadStorage = multer.diskStorage({
    destination: (req, file, cb) => {
        let dest = path.join(__dirname, '../../uploads/biodata/kk');
        if (file.fieldname === 'akta') {
            dest = path.join(__dirname, '../../uploads/biodata/akta');
        } else if (file.fieldname === 'bantuan') {
            dest = path.join(__dirname, '../../uploads/biodata/bantuan');
        }
        if (!fs.existsSync(dest)) {
            fs.mkdirSync(dest, { recursive: true });
        }
        cb(null, dest);
    },
    filename: (req, file, cb) => {
        const ext = path.extname(file.originalname) || '.jpg';
        const cleanName = file.originalname.replace(/[^a-zA-Z0-9]/g, '_').substring(0, 20);
        cb(null, `${file.fieldname}_${Date.now()}_${cleanName}${ext}`);
    }
});

const uploadBiodataFiles = multer({
    storage: biodataUploadStorage,
    limits: { fileSize: 5 * 1024 * 1024 } // 5MB
});

router.patch('/v1/admin/modules/biodata/toggle', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), toggleBiodataModule);
router.post('/v1/mobile/biodata/submit', authenticateJWT, ensureBiodataSubmissionActive, uploadBiodataFiles.fields([{ name: 'kk', maxCount: 1 }, { name: 'akta', maxCount: 1 }, { name: 'bantuan', maxCount: 1 }]), submitStudentBiodata);
router.get('/v1/mobile/biodata/my-submission', authenticateJWT, getMyBiodataSubmission);
router.get('/v1/admin/biodata/submissions', authenticateJWT, requireRole(['ADMIN', 'OPERATOR', 'TEACHER']), getBiodataSubmissionsList);
router.post('/v1/admin/biodata/:id/action', authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), actionBiodataSubmission);

// E. Modul Presensi Sesi Mengajar Berbasis Estafet Kelas Fisik (Room Handover NFC/QR) & Presensi Matpel
import {
    checkInTeachingSession,
    checkOutTeachingSession,
    getCurrentTeacherSession,
    updateStudentAttendanceByTeacher,
    getActiveSessionForStudent,
    studentSelfAttendSession,
    getPhysicalRoomsList
} from '../controllers/teacherRoomHandoverController';

router.post('/v1/teacher/session/checkin', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), checkInTeachingSession);
router.post('/v1/teacher/session/checkout', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), checkOutTeachingSession);
router.get('/v1/teacher/session/current', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), getCurrentTeacherSession);
router.post('/v1/teacher/session/:sessionId/attendances/update', authenticateJWT, requireRole(['TEACHER', 'ADMIN', 'OPERATOR']), updateStudentAttendanceByTeacher);
router.get('/v1/teacher/rooms', getPhysicalRoomsList);
router.get('/v1/teacher/session/rooms', getPhysicalRoomsList);

// Presensi Mata Pelajaran di Sisi Siswa (Aktif setelah Guru Tap di Kelas)
router.get('/v1/student/active-teaching-session', authenticateJWT, getActiveSessionForStudent);
router.post('/v1/student/session/attend', authenticateJWT, studentSelfAttendSession);

// --- MODUL LOG ERROR & CRASH APK (PORTAL & CLIENT REPORTER) ---
import {
    recordClientLog,
    getClientLogs,
    resolveClientLog,
    deleteClientLog,
    cleanupClientLogs
} from '../controllers/appClientLogController';

router.post(['/app/client-logs', '/v1/app/client-logs'], recordClientLog);
router.get(['/app/client-logs', '/v1/app/client-logs'], authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), getClientLogs);
router.put(['/app/client-logs/:id/resolve', '/v1/app/client-logs/:id/resolve'], authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), resolveClientLog);
router.delete(['/app/client-logs/:id', '/v1/app/client-logs/:id'], authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), deleteClientLog);
router.post(['/app/client-logs/cleanup', '/v1/app/client-logs/cleanup'], authenticateJWT, requireRole(['ADMIN', 'OPERATOR']), cleanupClientLogs);

// --- ALIAS JADWAL PIKET SISWA / PENGURUS KELAS ---
router.get(['/student/piket-schedule', '/v1/student/piket-schedule'], authenticateJWT, getWeeklyPiketSchedules);
router.get(['/student/piket-today', '/v1/student/piket-today'], getTodayOnDutyTeachers);

export default router;



