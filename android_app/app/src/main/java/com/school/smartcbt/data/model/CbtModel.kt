package com.school.smartcbt.data.model

data class ExamDto(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val isTokenActive: Boolean,
    val token: String?,
    val subject: SubjectDto?
)

data class SubjectDto(
    val id: String,
    val name: String
)

data class QuestionDto(
    val id: String,
    val questionText: String,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    val imageUrl: String?,
    val audioUrl: String?
)

data class SubmitAnswerRequest(
    val examId: String,
    val questionId: String,
    val selectedOption: String
)

data class BasicResponse(
    val success: Boolean? = true,
    val message: String? = null,
    val hasPendingCopyRequests: Boolean? = false,
    val pendingCount: Int? = 0,
    val forceLogout: Boolean? = false,
    val triggerScan: Boolean? = false,
    val latestVersionCode: Int? = null,
    val latestVersionName: String? = null
)

data class HeartbeatResponse(
    val success: Boolean? = true,
    val message: String? = null,
    val hasPendingCopyRequests: Boolean? = false,
    val pendingCount: Int? = 0,
    val forceLogout: Boolean? = false,
    val triggerScan: Boolean? = false,
    val latestVersionCode: Int? = null,
    val latestVersionName: String? = null
)

typealias DeviceHeartbeatResponse = HeartbeatResponse

data class DownloadQuestionsRequest(
    val examId: String? = null,
    val token: String? = null
)

data class BackendQuestionDto(
    val id: String,
    val orderNum: Int? = null,
    val content: String? = null,
    val questionText: String? = null,
    val type: String? = null,
    val optionA: String? = null,
    val optionB: String? = null,
    val optionC: String? = null,
    val optionD: String? = null,
    val correctOption: String? = null,
    val imageUrl: String? = null,
    val audioUrl: String? = null
)

data class DownloadQuestionsResponse(
    val success: Boolean,
    val message: String?,
    val studentExamId: String?,
    val examId: String?,
    val examTitle: String?,
    val durationMinutes: Int?,
    val totalQuestions: Int?,
    val questions: List<BackendQuestionDto>?
)

data class SyncExamRequest(
    val studentExamId: String,
    val answersJson: String,
    val strikeCount: Int? = null,
    val isFinished: Boolean = false
)

data class SyncExamResponse(
    val success: Boolean,
    val message: String?,
    val score: Double?,
    val status: String?
)

data class CheatStrikeRequest(
    val studentExamId: String
)

data class CheatStrikeResponse(
    val message: String?,
    val strikeCount: Int?,
    val status: String?
)

data class ProctorCbtTokensResponse(
    val success: Boolean,
    val proctorName: String?,
    val isRestrictedToSupervisedClasses: Boolean? = null,
    val supervisedClasses: List<String>? = null,
    val availableClasses: List<String>?,
    val availableLevels: List<ProctorLevelDto>?,
    val exams: List<ProctorExamDto>?
)

data class ProctorLevelDto(
    val level: String,
    val name: String
)

data class ProctorExamDto(
    val id: String,
    val title: String,
    val subject: String,
    val durationMinutes: Int,
    val token: String,
    val isTokenActive: Boolean,
    val assignedClasses: String?,
    val level: String?,
    val sessionName: String?,
    val executionDate: String?,
    val startTimeStr: String?,
    val endTimeStr: String?,
    val totalQuestions: Int = 0,
    val totalStudents: Int = 0,
    val masterToken: String? = null,
    val classTokens: Map<String, String>? = null,
    val classProctors: Map<String, String>? = null,
    val remainingSeconds: Long? = null,
    val tokenLifetimeSeconds: Long? = null
)

data class ProctorClassStudentsResponse(
    val success: Boolean,
    val className: String,
    val examId: String,
    val summary: ProctorClassSummaryDto?,
    val students: List<ProctorStudentDto>?
)

data class ProctorClassSummaryDto(
    val totalStudents: Int = 0,
    val totalPresent: Int = 0,
    val totalAbsent: Int = 0,
    val totalLocked: Int = 0,
    val totalFinished: Int = 0
)

data class ProctorStudentDto(
    val studentId: String,
    val name: String,
    val nisn: String?,
    val className: String?,
    val avatar: String?,
    val status: String, // BELUM_MULAI, SEDANG_MENGERJAKAN, SELESAI, TERKUNCI
    val strikeCount: Int = 0,
    val startTime: String?,
    val endTime: String?,
    val score: Double?,
    val studentExamId: String?
)

data class ProctorResetLockRequest(
    val examId: String,
    val studentId: String
)

data class ProctorSubmitBapRequest(
    val examId: String,
    val className: String,
    val roomName: String?,
    val totalRegistered: Int,
    val totalPresent: Int,
    val totalAbsent: Int,
    val absentList: String?,
    val incidentNotes: String?
)

data class VerifyTokenRequest(
    val examId: String,
    val token: String
)

data class VerifyTokenResponse(
    val success: Boolean,
    val valid: Boolean,
    val isTokenRequired: Boolean? = true,
    val message: String?,
    val exam: VerifyTokenExamDto? = null
)

data class VerifyTokenExamDto(
    val id: String,
    val title: String,
    val subject: String,
    val durationMinutes: Int
)

