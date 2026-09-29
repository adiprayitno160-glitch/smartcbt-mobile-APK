package com.school.smartcbt.data.model

import com.google.gson.annotations.SerializedName

/**
 * Model Data untuk Rak Buku Paket Fisik (Crowdsourcing Virtual Bookshelf)
 */
data class PackageBooksResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: PackageBooksData?
)

data class PackageBooksData(
    @SerializedName("studentGrade") val studentGrade: String?,
    @SerializedName("className") val className: String?,
    @SerializedName("stats") val stats: PackageBooksStats?,
    @SerializedName("slots") val slots: List<PackageBookSlotDto>?
)

data class PackageBooksStats(
    @SerializedName("totalSlots") val totalSlots: Int,
    @SerializedName("verifiedCount") val verifiedCount: Int,
    @SerializedName("pendingCount") val pendingCount: Int,
    @SerializedName("unassignedCount") val unassignedCount: Int,
    @SerializedName("progressPercentage") val progressPercentage: Int
)

data class PackageBookSlotDto(
    @SerializedName("id") val id: String,
    @SerializedName("judul") val judul: String,
    @SerializedName("mataPelajaran") val mataPelajaran: String,
    @SerializedName("tingkatKelas") val tingkatKelas: String,
    @SerializedName("kurikulum") val kurikulum: String,
    @SerializedName("coverUrl") val coverUrl: String?,
    @SerializedName("filePdfUrl") val filePdfUrl: String?,
    @SerializedName("visualStatus") val visualStatus: String, // BELUM_SCAN, PENDING_APPROVAL, TERVERIFIKASI
    @SerializedName("statusLabel") val statusLabel: String,
    @SerializedName("statusCode") val statusCode: String,
    @SerializedName("barcodeScanned") val barcodeScanned: String?,
    @SerializedName("claimedAt") val claimedAt: String?,
    @SerializedName("approvedAt") val approvedAt: String?
)

data class ClaimPackageBookRequest(
    @SerializedName("barcodeCode") val barcodeCode: String,
    @SerializedName("packageBookId") val packageBookId: String?
)

/**
 * Model Data Koleksi E-Book Digital & Modul Ajar
 */
data class StudentEbooksResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("studentGrade") val studentGrade: String?,
    @SerializedName("count") val count: Int,
    @SerializedName("data") val data: List<EbookDto>?
)

data class PopularEbooksResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: List<EbookDto>?
)

data class EbookDto(
    @SerializedName("id") val id: String,
    @SerializedName("judul") val judul: String,
    @SerializedName("pengarang") val pengarang: String,
    @SerializedName("mataPelajaran") val mataPelajaran: String,
    @SerializedName("targetKelas") val targetKelas: String,
    @SerializedName("coverUrl") val coverUrl: String?,
    @SerializedName("filePdfUrl") val filePdfUrl: String,
    @SerializedName("fileSizeMb") val fileSizeMb: Float,
    @SerializedName("viewCount") val viewCount: Int
)

/**
 * Model Data Pengingat Pinjaman Aktif
 */
data class ActiveLoansReminderResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("hasReminder") val hasReminder: Boolean,
    @SerializedName("activeCount") val activeCount: Int,
    @SerializedName("isOverdue") val isOverdue: Boolean,
    @SerializedName("daysLeft") val daysLeft: Int,
    @SerializedName("reminderText") val reminderText: String?,
    @SerializedName("earliestDueDate") val earliestDueDate: String?,
    @SerializedName("bookTitle") val bookTitle: String?
)

/**
 * Model Data Pengembalian Kontinu & Deteksi Buku Tertukar
 */
data class ContinuousReturnRequest(
    @SerializedName("barcodeCode") val barcodeCode: String,
    @SerializedName("currentStudentId") val currentStudentId: String?
)

data class ContinuousReturnResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("matched") val matched: Boolean,
    @SerializedName("status") val status: String,
    @SerializedName("soundAlert") val soundAlert: String?,
    @SerializedName("bookTitle") val bookTitle: String?,
    @SerializedName("barcode") val barcode: String?,
    @SerializedName("studentName") val studentName: String?,
    @SerializedName("className") val className: String?,
    @SerializedName("remainingBooks") val remainingBooks: Int?,
    @SerializedName("bebasPustaka") val bebasPustaka: Boolean?,
    @SerializedName("message") val message: String,
    @SerializedName("actualBorrower") val actualBorrower: ActualBorrowerDto?
)

data class ActualBorrowerDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("className") val className: String,
    @SerializedName("nisn") val nisn: String?
)

/**
 * Model Data Lost and Found
 */
data class LostAndFoundResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("found") val found: Boolean,
    @SerializedName("bookTitle") val bookTitle: String?,
    @SerializedName("mataPelajaran") val mataPelajaran: String?,
    @SerializedName("barcode") val barcode: String?,
    @SerializedName("owner") val owner: LostAndFoundOwnerDto?,
    @SerializedName("message") val message: String
)

data class LostAndFoundOwnerDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("className") val className: String,
    @SerializedName("nisn") val nisn: String?,
    @SerializedName("photoUrl") val photoUrl: String?
)

data class BatchApproveRequest(
    @SerializedName("className") val className: String,
    @SerializedName("academicYear") val academicYear: String? = "2026/2027"
)
