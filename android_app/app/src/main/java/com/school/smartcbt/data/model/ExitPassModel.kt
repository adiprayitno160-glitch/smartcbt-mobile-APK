package com.school.smartcbt.data.model

import com.google.gson.annotations.SerializedName

// ==========================================
// MODEL DATA DIGITAL SCHOOL GATEPASS & EMERGENCY LEAVE
// ==========================================

data class StudentLeaveApplyRequest(
    @SerializedName("leaveType") val leaveType: String,
    @SerializedName("reason") val reason: String,
    @SerializedName("eventName") val eventName: String? = null,
    @SerializedName("pickupPerson") val pickupPerson: String? = null,
    @SerializedName("leaveCategory") val leaveCategory: String? = null,
    @SerializedName("pickupBy") val pickupBy: String? = null
)

data class GatepassApplyRequest(
    @SerializedName("leaveCategory") val leaveCategory: String, // sick, urgent_family, dispensation
    @SerializedName("reason") val reason: String,
    @SerializedName("pickupBy") val pickupBy: String = "Mandiri",
    @SerializedName("eventName") val eventName: String? = null,
    @SerializedName("academicYear") val academicYear: String = "2026/2027"
)

data class StudentLeaveResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: StudentLeaveData?
)

data class StudentLeaveActiveResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("hasActiveLeave") val hasActiveLeave: Boolean,
    @SerializedName("isScanLocked") val isScanLocked: Boolean,
    @SerializedName("isReadyForScan") val isReadyForScan: Boolean,
    @SerializedName("isCheckedOut") val isCheckedOut: Boolean,
    @SerializedName("data") val data: StudentLeaveData?
)

data class StudentLeaveData(
    @SerializedName("id") val id: String,
    @SerializedName("leaveType") val leaveType: String?,
    @SerializedName("leaveCategory") val leaveCategory: String?,
    @SerializedName("categoryLabel") val categoryLabel: String?,
    @SerializedName("reason") val reason: String,
    @SerializedName("eventName") val eventName: String?,
    @SerializedName("pickupPerson") val pickupPerson: String?,
    @SerializedName("pickupBy") val pickupBy: String?,
    @SerializedName("status") val status: String,
    @SerializedName("appliedAt") val appliedAt: String?,
    @SerializedName("approvedAt") val approvedAt: String?,
    @SerializedName("checkedOutAt") val checkedOutAt: String?,
    @SerializedName("validUntil") val validUntil: String?,
    @SerializedName("remainingSeconds") val remainingSeconds: Long?,
    @SerializedName("singleUseCheckoutToken") val singleUseCheckoutToken: String?,
    @SerializedName("qrVerificationPayload") val qrVerificationPayload: String?,
    @SerializedName("statusText") val statusText: String?,
    @SerializedName("counselorNotes") val counselorNotes: String?,
    @SerializedName("stationName") val stationName: String?,
    @SerializedName("approvedByBkName") val approvedByBkName: String?,
    @SerializedName("student") val student: StudentBriefDto?
)

data class CheckoutBkRequest(
    @SerializedName("qrSecretToken") val qrSecretToken: String? = null,
    @SerializedName("dynamicToken") val dynamicToken: String? = null,
    @SerializedName("leaveId") val leaveId: String? = null,
    @SerializedName("gatepassId") val gatepassId: String? = null
)

data class CheckoutBkResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("exitPass") val exitPass: ExitPassData?,
    @SerializedName("data") val data: ExitPassData?
)

data class ExitPassData(
    @SerializedName("id") val id: String?,
    @SerializedName("leaveId") val leaveId: String,
    @SerializedName("studentId") val studentId: String?,
    @SerializedName("studentName") val studentName: String,
    @SerializedName("className") val className: String,
    @SerializedName("nisn") val nisn: String?,
    @SerializedName("profilePicUrl") val profilePicUrl: String?,
    @SerializedName("leaveType") val leaveType: String?,
    @SerializedName("leaveCategory") val leaveCategory: String?,
    @SerializedName("categoryLabel") val categoryLabel: String?,
    @SerializedName("reason") val reason: String,
    @SerializedName("eventName") val eventName: String?,
    @SerializedName("pickupPerson") val pickupPerson: String?,
    @SerializedName("pickupBy") val pickupBy: String?,
    @SerializedName("stationName") val stationName: String?,
    @SerializedName("appliedAt") val appliedAt: String?,
    @SerializedName("approvedAt") val approvedAt: String?,
    @SerializedName("checkedOutAt") val checkedOutAt: String?,
    @SerializedName("validUntil") val validUntil: String?,
    @SerializedName("remainingSeconds") val remainingSeconds: Long?,
    @SerializedName("singleUseCheckoutToken") val singleUseCheckoutToken: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("statusText") val statusText: String?,
    @SerializedName("qrVerificationPayload") val qrVerificationPayload: String?,
    @SerializedName("approvedByBkName") val approvedByBkName: String?
)

// Dynamic Rotating QR Ruang BK
data class BkDynamicQrResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: BkDynamicQrData?
)

data class BkDynamicQrData(
    @SerializedName("tokenPayload") val tokenPayload: String,
    @SerializedName("currentTokenHash") val currentTokenHash: String,
    @SerializedName("roomId") val roomId: String,
    @SerializedName("bkUserId") val bkUserId: String,
    @SerializedName("bkName") val bkName: String,
    @SerializedName("windowIndex") val windowIndex: Long,
    @SerializedName("expiresAt") val expiresAt: String,
    @SerializedName("ttlSeconds") val ttlSeconds: Int,
    @SerializedName("rotationIntervalSeconds") val rotationIntervalSeconds: Int
)

// Satpam Checkout
data class SatpamCheckoutRequest(
    @SerializedName("checkoutToken") val checkoutToken: String? = null,
    @SerializedName("qrPayload") val qrPayload: String? = null
)

data class SatpamCheckoutResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: Any?
)

// BK Gatepass List & Status Update
data class BkGatepassListResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("count") val count: Int,
    @SerializedName("data") val data: List<GatepassItemData>?
)

data class GatepassItemData(
    @SerializedName("id") val id: String,
    @SerializedName("studentId") val studentId: String,
    @SerializedName("leaveCategory") val leaveCategory: String,
    @SerializedName("reason") val reason: String,
    @SerializedName("pickupBy") val pickupBy: String?,
    @SerializedName("status") val status: String,
    @SerializedName("rejectionReason") val rejectionReason: String?,
    @SerializedName("verifiedAt") val verifiedAt: String?,
    @SerializedName("validUntil") val validUntil: String?,
    @SerializedName("exitTimestamp") val exitTimestamp: String?,
    @SerializedName("singleUseCheckoutToken") val singleUseCheckoutToken: String?,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("student") val student: StudentBriefDto?,
    @SerializedName("approvedByBk") val approvedByBk: UserBriefDto?,
    @SerializedName("checkoutSatpam") val checkoutSatpam: UserBriefDto?
)

data class UserBriefDto(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?
)

data class UpdateGatepassStatusRequest(
    @SerializedName("status") val status: String,
    @SerializedName("rejectionReason") val rejectionReason: String? = null,
    @SerializedName("counselorNotes") val counselorNotes: String? = null
)
