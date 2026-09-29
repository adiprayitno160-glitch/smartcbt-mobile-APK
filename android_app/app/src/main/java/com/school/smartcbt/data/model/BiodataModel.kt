package com.school.smartcbt.data.model

import com.google.gson.annotations.SerializedName

// ========================================================
// MODEL DATA FORMULIR BIODATA SISWA (STAGING & MULTI-STEP)
// ========================================================

data class MyBiodataResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("isToggleOpen") val isToggleOpen: Boolean,
    @SerializedName("deadline") val deadline: String?,
    @SerializedName("isLocked") val isLocked: Boolean,
    @SerializedName("status") val status: String, // NOT_SUBMITTED, PENDING, APPROVED, REJECTED
    @SerializedName("rejectionReason") val rejectionReason: String?,
    @SerializedName("submission") val submission: BiodataSubmissionDto?,
    @SerializedName("student") val student: StudentBriefDto?
)

data class BiodataSubmissionResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("isLocked") val isLocked: Boolean = false,
    @SerializedName("submission") val submission: BiodataSubmissionDto?
)

data class BiodataSubmissionDto(
    @SerializedName("id") val id: String,
    @SerializedName("studentId") val studentId: String,
    @SerializedName("status") val status: String, // PENDING, APPROVED, REJECTED
    @SerializedName("rejectionReason") val rejectionReason: String?,
    @SerializedName("kkFileUrl") val kkFileUrl: String?,
    @SerializedName("aktaFileUrl") val aktaFileUrl: String?,
    @SerializedName("step1Data") val step1Data: String?,
    @SerializedName("step2Data") val step2Data: String?,
    @SerializedName("step3Data") val step3Data: String?,
    @SerializedName("step4Data") val step4Data: String?,
    @SerializedName("step5Data") val step5Data: String?,
    @SerializedName("submittedAt") val submittedAt: String?,
    @SerializedName("reviewedAt") val reviewedAt: String?,
    @SerializedName("reviewedBy") val reviewedBy: String?
)
