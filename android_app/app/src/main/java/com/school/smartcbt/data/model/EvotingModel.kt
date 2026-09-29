package com.school.smartcbt.data.model

import com.google.gson.annotations.SerializedName

/**
 * Model Data untuk Feature Toggle Modul e-Voting & Portal SIAKAD
 */
data class EvotingModuleConfigResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: EvotingModuleData?
)

data class EvotingModuleData(
    @SerializedName("modules") val modules: Map<String, ModuleItemDto>?
)

data class ModuleItemDto(
    @SerializedName("name") val name: String?,
    @SerializedName("is_active") val isActive: Boolean = false,
    @SerializedName("is_open_now") val isOpenNow: Boolean = false,
    @SerializedName("window_status") val windowStatus: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("deadline") val deadline: String? = null,
    @SerializedName("message") val message: String? = null
)

/**
 * Model Data Kandidat Pasangan Calon Ketua & Wakil Ketua OSIS
 */
data class CandidateDto(
    @SerializedName("id") val id: String,
    @SerializedName("candidateNumber") val candidateNumber: Int,
    @SerializedName("chairmanName") val chairmanName: String,
    @SerializedName("viceChairmanName") val viceChairmanName: String,
    @SerializedName("vision") val vision: String,
    @SerializedName("mission") val mission: List<String>?,
    @SerializedName("photoUrl") val photoUrl: String?
)

data class CandidateListResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: List<CandidateDto>?
)

/**
 * Model Pengecekan Status Hak Suara Siswa
 */
data class VoteStatusResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: VoteStatusData?
)

data class VoteStatusData(
    @SerializedName("student_id") val studentId: String?,
    @SerializedName("has_voted") val hasVoted: Boolean,
    @SerializedName("voted_at") val votedAt: String?
)

/**
 * Model Transaksi Submit Pencoblosan Suara
 */
data class VoteRequest(
    @SerializedName("candidateId") val candidateId: String
)

data class VoteResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("error_code") val errorCode: String?,
    @SerializedName("data") val data: VoteReceiptData?
)

data class VoteReceiptData(
    @SerializedName("receipt_token") val receiptToken: String?,
    @SerializedName("voted_at") val votedAt: String?
)

/**
 * Model Data Monitoring DPT & Partisipasi Kelas untuk Pengurus Kelas
 */
data class ClassTurnoutResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: ClassTurnoutData?
)

data class ClassTurnoutData(
    @SerializedName("className") val className: String,
    @SerializedName("totalDpt") val totalDpt: Int,
    @SerializedName("votedCount") val votedCount: Int,
    @SerializedName("unvotedCount") val unvotedCount: Int,
    @SerializedName("turnoutPercentage") val turnoutPercentage: Float,
    @SerializedName("students") val students: List<ClassmateVoteItemDto>?
)

data class ClassmateVoteItemDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("nisn") val nisn: String,
    @SerializedName("hasVoted") val hasVoted: Boolean,
    @SerializedName("votedAt") val votedAt: String?
)

