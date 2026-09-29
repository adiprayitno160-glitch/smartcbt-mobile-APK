package com.school.smartcbt.data.model

data class ConsultationUserDto(
    val id: String? = null,
    val name: String? = null,
    val username: String? = null,
    val className: String? = null
)

data class ConsultationDto(
    val id: String,
    val category: String,
    val subject: String,
    val message: String,
    val replyMessage: String?,
    val replyDate: String?,
    val status: String,
    val isAnonymous: Boolean,
    val createdAt: String,
    val student: ConsultationUserDto? = null,
    val counselor: ConsultationUserDto? = null
)

data class SendReportRequest(
    val category: String,
    val subject: String,
    val message: String,
    val isAnonymous: Boolean,
    val studentId: String? = null
)

data class ReplyBkRequest(
    val replyMessage: String,
    val status: String = "REPLIED"
)

data class BkClassRecapResponse(
    val success: Boolean,
    val allowedClasses: List<String>? = null,
    val selectedClass: String? = null,
    val totalStudents: Int? = null,
    val students: List<BkStudentRecapDto>? = null,
    val message: String? = null
)

data class BkStudentRecapDto(
    val id: String,
    val name: String,
    val username: String? = null,
    val nisn: String? = null,
    val className: String? = null,
    val gender: String? = null,
    val points: Int? = null,
    val parentPhone: String? = null,
    val spLevel: String? = null,
    val stats: BkStudentStatsDto? = null,
    val recentViolations: List<BkDisciplineRecordDto>? = null
)

data class BkStudentStatsDto(
    val hadir: Int = 0,
    val terlambat: Int = 0,
    val sakit: Int = 0,
    val izin: Int = 0,
    val alpa: Int = 0
)

data class BkDisciplineRecordDto(
    val id: String? = null,
    val category: String? = null,
    val description: String? = null,
    val points: Int? = null,
    val createdAt: String? = null
)

data class BkTodayActivityResponse(
    val success: Boolean,
    val lateArrivals: List<BkActivityItemDto>? = null,
    val earlyLeaves: List<BkActivityItemDto>? = null
)

data class BkActivityItemDto(
    val id: String,
    val studentId: String? = null,
    val studentName: String? = null,
    val className: String? = null,
    val nisn: String? = null,
    val scanTime: String? = null,
    val note: String? = null
)

data class CreateDisciplineRequest(
    val studentId: String,
    val type: String = "PELANGGARAN",
    val description: String,
    val points: Int
)

