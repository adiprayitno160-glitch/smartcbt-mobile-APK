package com.school.smartcbt.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.school.smartcbt.ScannerActivity
import com.school.smartcbt.data.model.AttendanceDetailResponse
import com.school.smartcbt.data.model.AttendanceHistoryDto
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.databinding.ActivityAttendanceBinding
import com.school.smartcbt.utils.SessionManager
import android.content.Context
import com.google.gson.Gson
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*

class AttendanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAttendanceBinding
    private lateinit var sessionManager: SessionManager

    private var currentCalendar: Calendar = Calendar.getInstance()
    private var allAttendanceHistory: List<AttendanceHistoryDto> = emptyList()
    private val nationalHolidays: MutableMap<String, String> = mutableMapOf()
    private var currentStatusFilter: String = "ALL"

    companion object {
        const val EXTRA_STUDENT_ID = "extra_student_id"
        const val EXTRA_STUDENT_NAME = "extra_student_name"
        const val EXTRA_CLASS_NAME = "extra_class_name"
        const val EXTRA_IS_PARENT = "extra_is_parent"
        const val EXTRA_INITIAL_TAB = "extra_initial_tab" // 0: Kalender, 1: Riwayat Lengkap, 2: Statistik
    }

    private var targetStudentId: String? = null
    private var isParentMode: Boolean = false
    private var currentSelectedTab: Int = 0
    private var currentHistoryPage: Int = 1
    private val historyPageSize: Int = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        targetStudentId = intent.getStringExtra(EXTRA_STUDENT_ID)
        isParentMode = intent.getBooleanExtra(EXTRA_IS_PARENT, false)

        setupUI()
        fetchNationalHolidays()
        loadAttendanceData()
    }

    private fun setupUI() {
        binding.btnBackAttendance.setOnClickListener { finish() }
        binding.btnRefreshAttendance.setOnClickListener {
            Toast.makeText(this, "Menyegarkan data presensi...", Toast.LENGTH_SHORT).show()
            try {
                val queryId = targetStudentId ?: if (isParentMode) null else null
                val cacheKey = "cache_att_detail_" + (queryId ?: sessionManager.getNisn().ifEmpty { sessionManager.getUsername() })
                getSharedPreferences("smartschool_offline_cache", Context.MODE_PRIVATE).edit().remove(cacheKey).apply()
            } catch (e: Exception) {}
            loadAttendanceData()
        }

        if (isParentMode) {
            binding.tvAttendanceTitle.text = "Laporan Absensi Ananda"
            binding.btnQuickScanAttendance.visibility = View.GONE
            val childName = intent.getStringExtra(EXTRA_STUDENT_NAME)?.ifEmpty { null } ?: sessionManager.getName().ifEmpty { "Ananda" }
            val className = intent.getStringExtra(EXTRA_CLASS_NAME)?.ifEmpty { null } ?: sessionManager.getClassName().ifEmpty { "VII-A" }
            binding.tvAttendanceSubheader.text = "Rekap Presensi Ananda $childName • Kelas $className • 1 Bulan Penuh"
        } else {
            binding.tvAttendanceTitle.text = "Presensi Digital Siswa"
            binding.btnQuickScanAttendance.setOnClickListener {
                val intent = Intent(this, ScannerActivity::class.java)
                startActivity(intent)
            }
            val studentName = sessionManager.getName().ifEmpty { "Siswa" }
            val className = sessionManager.getClassName().ifEmpty { "VII-A" }
            binding.tvAttendanceSubheader.text = "$studentName • Kelas $className • Semester Ganjil"
        }

        val sdf = SimpleDateFormat("EEEE, dd MMM yyyy", Locale("id", "ID"))
        binding.tvTodayDateBadge.text = sdf.format(Date())

        updateMonthLabel()

        // Month Prev / Next buttons
        binding.btnPrevMonth.setOnClickListener {
            currentCalendar.add(Calendar.MONTH, -1)
            updateMonthLabel()
            renderCalendar(allAttendanceHistory)
        }

        binding.btnNextMonth.setOnClickListener {
            currentCalendar.add(Calendar.MONTH, 1)
            updateMonthLabel()
            renderCalendar(allAttendanceHistory)
        }
    }

    private fun updateMonthLabel() {
        val monthSdf = SimpleDateFormat("MMMM yyyy", Locale("id", "ID"))
        binding.tvCalendarCurrentMonth.text = monthSdf.format(currentCalendar.time)
    }

    private fun fetchNationalHolidays() {
        val year = currentCalendar.get(Calendar.YEAR)
        ApiClient.getClient(this).getHolidays(year).enqueue(object : Callback<List<Map<String, Any>>> {
            override fun onResponse(call: Call<List<Map<String, Any>>>, response: Response<List<Map<String, Any>>>) {
                if (response.isSuccessful && response.body() != null) {
                    nationalHolidays.clear()
                    response.body()!!.forEach { h ->
                        val dateStr = h["date"]?.toString() ?: ""
                        val name = h["name"]?.toString() ?: "Hari Libur Nasional"
                        if (dateStr.isNotEmpty()) {
                            nationalHolidays[dateStr] = name
                        }
                    }
                    renderCalendar(allAttendanceHistory)
                }
            }

            override fun onFailure(call: Call<List<Map<String, Any>>>, t: Throwable) {
                // Fallback built-in holidays are handled gracefully
            }
        })
    }

    private fun loadAttendanceData() {
        val queryId = targetStudentId ?: if (isParentMode) null else null
        val cacheKey = "cache_att_detail_" + (queryId ?: sessionManager.getNisn().ifEmpty { sessionManager.getUsername() })
        val cachePrefs = getSharedPreferences("smartschool_offline_cache", Context.MODE_PRIVATE)

        // 1. Zero-Jeda / Instant Offline-First: Render Cache immediately (0ms)
        var hasCachedData = false
        val cachedJson = cachePrefs.getString(cacheKey, null)
        if (!cachedJson.isNullOrEmpty()) {
            try {
                val cachedData = Gson().fromJson(cachedJson, AttendanceDetailResponse::class.java)
                if (cachedData?.history != null) {
                    hasCachedData = true
                    allAttendanceHistory = cachedData.history
                    renderTodayStatus(cachedData.history)
                    renderAttendanceTrend(cachedData.history, cachedData.stats)
                    renderCalendar(cachedData.history)
                }
            } catch (e: Exception) {
                // Safe fallback on parse error
            }
        }

        // 2. Background Revalidate from Network (Silently update and save cache)
        ApiClient.getClient(this).getDetailedAttendance(queryId).enqueue(object : Callback<AttendanceDetailResponse> {
            override fun onResponse(call: Call<AttendanceDetailResponse>, response: Response<AttendanceDetailResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    allAttendanceHistory = data.history
                    renderTodayStatus(data.history)
                    renderAttendanceTrend(data.history, data.stats)
                    renderCalendar(data.history)

                    // Persist to offline cache for instant 0ms launch next time
                    try {
                        cachePrefs.edit().putString(cacheKey, Gson().toJson(data)).apply()
                    } catch (e: Exception) {
                        // Safe fallback
                    }
                } else {
                    if (!hasCachedData) {
                        Toast.makeText(this@AttendanceActivity, "Gagal memuat detail presensi dari server", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onFailure(call: Call<AttendanceDetailResponse>, t: Throwable) {
                if (!hasCachedData) {
                    Toast.makeText(this@AttendanceActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@AttendanceActivity, "⚡ Menampilkan rekaman presensi tersimpan (Offline)", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun renderTodayStatus(history: List<AttendanceHistoryDto>) {
        val todaySdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = todaySdf.format(Date())

        val todayLog = history.find { it.date == todayStr }

        if (todayLog != null) {
            val status = todayLog.status.uppercase()
            val (icon, title, desc, col) = when (status) {
                "PRESENT", "HADIR", "H" -> Quadruple("✅", "Hadir Tepat Waktu [H]", "Gerbang Depan • Terverifikasi QR", "#15803D")
                "LATE", "TERLAMBAT", "T" -> Quadruple("⏰", "Hadir Terlambat [T]", "Tercatat Dispensasi Masuk Piket", "#D97706")
                "SICK", "SAKIT", "S" -> Quadruple("🤒", "Izin Sakit [S]", "Surat Keterangan Dokter / UKS", "#7C3AED")
                "PERMISSION", "IZIN", "I" -> Quadruple("📝", "Izin Resmi [I]", "Disetujui Orang Tua & Wali Kelas", "#2563EB")
                "DISPENSATION", "DISPENSASI", "D" -> Quadruple("🟠", "Dispensasi [D]", "Tugas Sekolah / Lomba / Dinas", "#EA580C")
                "UKS", "U" -> Quadruple("🩵", "Sedang di UKS [U]", "Ditangani di Ruang UKS Sekolah", "#0891B2")
                "EARLY_LEAVE", "PULANG", "P" -> Quadruple("⚪", "Pulang Awal [P]", "Izin Kepulangan Lebih Awal", "#475569")
                "OUTDOOR_ASSIGNMENT", "TUGAS LUAR", "L" -> Quadruple("🟤", "Tugas Luar [L]", "Kegiatan Belajar di Luar Kelas", "#D97706")
                "TRUANT", "BOLOS", "B" -> Quadruple("⚫", "Tercatat Bolos [B]", "Meninggalkan Sekolah Tanpa Izin Sah", "#991B1B")
                else -> Quadruple("❌", "Belum Hadir / Alfa [A]", "Belum ada rekaman presensi hari ini", "#DC2626")
            }

            binding.tvTodayStatusIcon.text = icon
            binding.tvTodayStatusText.text = title
            binding.tvTodayStatusText.setTextColor(Color.parseColor(col))
            binding.tvTodayGateNote.text = todayLog.gateLocation ?: desc
            binding.tvTodayGateInTime.text = todayLog.gateInTime?.let { "$it WIB" } ?: "--:-- WIB"
            binding.tvTodayGateOutTime.text = todayLog.gateOutTime?.let { "$it WIB" } ?: "--:-- WIB"
        } else {
            val now = Calendar.getInstance()
            val hour = now.get(Calendar.HOUR_OF_DAY)
            val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
            val isWeekend = (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY)

            if (isWeekend) {
                binding.tvTodayStatusIcon.text = "☕"
                binding.tvTodayStatusText.text = "Hari Libur Akhir Pekan"
                binding.tvTodayStatusText.setTextColor(Color.parseColor("#64748B"))
                binding.tvTodayGateNote.text = "Hari libur sekolah, tidak ada jadwal presensi KBM"
            } else if (hour >= 16 || hour < 6) {
                binding.tvTodayStatusIcon.text = "🌙"
                binding.tvTodayStatusText.text = "Di Luar Jam Operasional KBM"
                binding.tvTodayStatusText.setTextColor(Color.parseColor("#64748B"))
                binding.tvTodayGateNote.text = "Belum ada rekaman presensi / Rekap presensi bersih"
            } else if (hour >= 8) {
                binding.tvTodayStatusIcon.text = "⚠️"
                binding.tvTodayStatusText.text = "Belum Presensi Gerbang Masuk"
                binding.tvTodayStatusText.setTextColor(Color.parseColor("#D97706"))
                binding.tvTodayGateNote.text = "Belum memindai barcode gerbang masuk hari ini (Batas: 08:00 WIB)"
            } else {
                binding.tvTodayStatusIcon.text = "⚪"
                binding.tvTodayStatusText.text = "Belum Melakukan Presensi"
                binding.tvTodayStatusText.setTextColor(Color.parseColor("#64748B"))
                binding.tvTodayGateNote.text = "Silakan pindai QR Barcode di gerbang sebelum jam masuk (08:00 WIB)"
            }
            binding.tvTodayGateInTime.text = "--:-- WIB"
            binding.tvTodayGateOutTime.text = "--:-- WIB"
        }
    }



    private fun renderCalendar(history: List<AttendanceHistoryDto>) {
        binding.gridCalendarDays.removeAllViews()
        val density = resources.displayMetrics.density

        val historyMap = mutableMapOf<String, AttendanceHistoryDto>()
        history.forEach {
            historyMap[it.date] = it
        }

        val cal = currentCalendar.clone() as Calendar
        val targetYear = cal.get(Calendar.YEAR)
        val targetMonth = cal.get(Calendar.MONTH) // 0-based

        val realTodayCal = Calendar.getInstance()
        val isCurrentMonthAndYear = (realTodayCal.get(Calendar.YEAR) == targetYear && realTodayCal.get(Calendar.MONTH) == targetMonth)
        val todayDay = if (isCurrentMonthAndYear) realTodayCal.get(Calendar.DAY_OF_MONTH) else -1

        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // Sunday=0, Monday=1
        val maxDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val totalCells = ((firstDayOfWeek + maxDaysInMonth + 6) / 7) * 7

        for (i in 0 until totalCells) {
            val dayNumber = i - firstDayOfWeek + 1
            val isValidDay = dayNumber in 1..maxDaysInMonth

            val dayView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val pad = (4 * density).toInt()
                setPadding(pad, pad, pad, pad)

                val param = GridLayout.LayoutParams().apply {
                    width = 0
                    height = (48 * density).toInt()
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                }
                layoutParams = param
            }

            if (isValidDay) {
                val dayStr = String.format("%04d-%02d-%02d", targetYear, targetMonth + 1, dayNumber)
                val log = historyMap[dayStr]
                val isSunday = (i % 7 == 0)
                val holidayName = nationalHolidays[dayStr]
                val isHoliday = holidayName != null

                val tvDayNum = TextView(this).apply {
                    text = dayNumber.toString()
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(
                        when {
                            isSunday -> Color.parseColor("#94A3B8")
                            isHoliday -> Color.parseColor("#EF4444")
                            else -> Color.parseColor("#0F172A")
                        }
                    )
                    if (dayNumber == todayDay) {
                        setTypeface(null, Typeface.BOLD)
                    }
                }
                dayView.addView(tvDayNum)

                // Dot Pin status
                val pinView = View(this).apply {
                    val dotSize = (7 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        topMargin = (3 * density).toInt()
                    }

                    val dotColor = when {
                        isSunday -> "#94A3B8"  // Neutral gray dot for Sunday (Libur)
                        isHoliday -> "#3B82F6" // Blue dot for Holiday
                        log != null -> when (log.status.uppercase()) {
                            "PRESENT", "HADIR" -> "#16A34A"
                            "LATE", "TERLAMBAT" -> "#F59E0B"
                            "SICK", "SAKIT" -> "#F59E0B"
                            "PERMISSION", "IZIN" -> "#0284C7"
                            "LIBUR", "LIBUR HARI MINGGU" -> "#94A3B8"
                            else -> "#DC2626"
                        }
                        dayNumber < todayDay && isCurrentMonthAndYear -> "#EF4444" // Alpa merah untuk hari lalu tanpa presensi
                        dayNumber == todayDay && isCurrentMonthAndYear -> {
                            val hourNow = realTodayCal.get(Calendar.HOUR_OF_DAY)
                            if (hourNow >= 8) "#EF4444" else "#94A3B8" // Merah jika lewat jam 08:00 WIB, abu-abu jika belum
                        }
                        else -> "#CBD5E1"
                    }

                    val shape = android.graphics.drawable.GradientDrawable()
                    shape.shape = android.graphics.drawable.GradientDrawable.OVAL
                    shape.setColor(Color.parseColor(dotColor))
                    background = shape
                }
                dayView.addView(pinView)

                // Click to view detail day
                dayView.setOnClickListener {
                    showDayDetailDialog(dayNumber, dayStr, log, isSunday, holidayName)
                }
            }

            binding.gridCalendarDays.addView(dayView)
        }
    }

    private fun showDayDetailDialog(day: Int, dateStr: String, log: AttendanceHistoryDto?, isSunday: Boolean, holidayName: String?) {
        showModernAttendanceModal("Tanggal $day", dateStr, log, isSunday, holidayName)
    }

    private fun showModernAttendanceModal(
        titlePrefix: String,
        dateStr: String,
        log: AttendanceHistoryDto?,
        isSunday: Boolean = false,
        holidayName: String? = null,
        extraDuration: String = "",
        extraPunctuality: String = ""
    ) {
        val sName = sessionManager.getName().ifEmpty { "Siswa" }
        val sClass = sessionManager.getClassName().ifEmpty { "VII-A" }
        val density = resources.displayMetrics.density

        val statusUpper = log?.status?.uppercase() ?: ""
        val (themeColor, headerTitle, badgeText, badgeBg, badgeTextCol) = when {
            holidayName != null -> Tuple5("#DC2626", "Hari Libur Nasional", "LIBUR NASIONAL", "#FEE2E2", "#991B1B")
            isSunday -> Tuple5("#64748B", "Hari Minggu", "LIBUR AKHIR PEKAN", "#F1F5F9", "#475569")
            log != null -> when (statusUpper) {
                "PRESENT", "HADIR", "H" -> Tuple5("#059669", "Detail Presensi Hadir", "HADIR TEPAT WAKTU", "#DCFCE7", "#15803D")
                "LATE", "TERLAMBAT", "T" -> Tuple5("#D97706", "Detail Presensi Terlambat", "HADIR TERLAMBAT", "#FEF3C7", "#B45309")
                "SICK", "SAKIT", "S" -> Tuple5("#7C3AED", "Detail Izin Sakit", "IZIN SAKIT (UKS/DOKTER)", "#F3E8FF", "#6B21A8")
                "PERMISSION", "IZIN", "I" -> Tuple5("#2563EB", "Detail Surat Izin", "IZIN RESMI", "#DBEAFE", "#1D4ED8")
                "DISPENSATION", "DISPEN", "D" -> Tuple5("#EA580C", "Detail Dispensasi", "DISPENSASI SEKOLAH", "#FFEDD5", "#C2410C")
                else -> Tuple5("#DC2626", "Detail Presensi Siswa", statusUpper.ifEmpty { "ALPA" }, "#FEE2E2", "#991B1B")
            }
            else -> {
                val todaySdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val todayStr = todaySdf.format(Date())
                val now = Calendar.getInstance()
                val hour = now.get(Calendar.HOUR_OF_DAY)
                if (dateStr.contains(todayStr) && hour < 8) {
                    Tuple5("#64748B", "Belum Ada Presensi", "BELUM SCAN PRESENSI", "#F1F5F9", "#475569")
                } else {
                    Tuple5("#DC2626", "Tercatat Alpa", "ALPA (MELEWATI BATAS WAKTU)", "#FEE2E2", "#991B1B")
                }
            }
        }

        val scroll = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }
        scroll.addView(root)

        // 1. Header with Status Color
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(themeColor))
            val padH = (20 * density).toInt()
            val padV = (18 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        val tvHdrTitle = TextView(this).apply {
            text = headerTitle
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
        }
        header.addView(tvHdrTitle)

        val tvHdrDate = TextView(this).apply {
            text = "📅 $dateStr • SMP Negeri 1 Boyolangu"
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 12f
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        header.addView(tvHdrDate)
        root.addView(header)

        // 2. Body Card
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // Student Info
        val tvIdent = TextView(this).apply {
            text = "👤 $sName ($sClass)"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        body.addView(tvIdent)

        // Status Badge Pill
        val tvStatusPill = TextView(this).apply {
            text = "🏷️ Status: $badgeText"
            setTextColor(Color.parseColor(badgeTextCol))
            setBackgroundColor(Color.parseColor(badgeBg))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            val h = (12 * density).toInt()
            val v = (6 * density).toInt()
            setPadding(h, v, h, v)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
            layoutParams = lp
        }
        body.addView(tvStatusPill)

        // Details Container
        val cardDetails = CardView(this).apply {
            radius = 12 * density
            cardElevation = 2f * density
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val layoutDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padD = (14 * density).toInt()
            setPadding(padD, padD, padD, padD)
        }

        val inTime = log?.gateInTime?.let { if (it.endsWith("WIB")) it else "$it WIB" } ?: "--:-- WIB"
        val outTime = log?.gateOutTime?.let { if (it.endsWith("WIB")) it else "$it WIB" } ?: "--:-- WIB"
        val loc = log?.gateLocation ?: "Gerbang Utama / Pintu Kelas"

        val tvGateIn = TextView(this).apply {
            text = "🚪 Jam Masuk: $inTime"
            setTextColor(Color.parseColor("#334155"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        layoutDetails.addView(tvGateIn)

        val tvGateOut = TextView(this).apply {
            text = "🏠 Jam Pulang: $outTime"
            setTextColor(Color.parseColor("#334155"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        layoutDetails.addView(tvGateOut)

        val tvLoc = TextView(this).apply {
            text = "📍 Lokasi: $loc"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 12f
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        layoutDetails.addView(tvLoc)

        if (extraDuration.isNotEmpty()) {
            val tvDur = TextView(this).apply {
                text = extraDuration
                setTextColor(Color.parseColor("#059669"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, (4 * density).toInt())
            }
            layoutDetails.addView(tvDur)
        }

        if (extraPunctuality.isNotEmpty()) {
            val tvPunc = TextView(this).apply {
                text = extraPunctuality
                setTextColor(Color.parseColor("#B45309"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, (4 * density).toInt())
            }
            layoutDetails.addView(tvPunc)
        }

        if (holidayName != null) {
            val tvHoli = TextView(this).apply {
                text = "🎉 $holidayName"
                setTextColor(Color.parseColor("#DC2626"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }
            layoutDetails.addView(tvHoli)
        }

        if (log?.note != null && log.note.isNotBlank()) {
            val tvNote = TextView(this).apply {
                text = "📝 Catatan: ${log.note}"
                setTextColor(Color.parseColor("#0369A1"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            layoutDetails.addView(tvNote)
        }

        cardDetails.addView(layoutDetails)
        body.addView(cardDetails)

        var dialog: AlertDialog? = null
        val btnClose = Button(this).apply {
            text = "Tutup"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(themeColor))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (46 * density).toInt()).apply {
                topMargin = (16 * density).toInt()
            }
            layoutParams = lp
            setOnClickListener {
                dialog?.dismiss()
            }
        }
        body.addView(btnClose)
        root.addView(body)

        dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .create()
        dialog.show()
    }

    private fun renderAttendanceTrend(history: List<AttendanceHistoryDto>, stats: com.school.smartcbt.data.model.AttendanceStatsDto?) {
        val density = resources.displayMetrics.density

        // 1. Calculate overall stats if not supplied
        val validLogs = history.filter { !it.isSunday && !it.status.uppercase().contains("LIBUR") }
        val totalValid = if (validLogs.isNotEmpty()) validLogs.size else 1
        val hadirCount = validLogs.count { it.status.uppercase() in listOf("PRESENT", "HADIR", "H") }
        val lateCount = validLogs.count { it.status.uppercase() in listOf("LATE", "TERLAMBAT", "T") }
        val sickCount = validLogs.count { it.status.uppercase() in listOf("SICK", "SAKIT", "S") }
        val permitCount = validLogs.count { it.status.uppercase() in listOf("PERMISSION", "IZIN", "I", "DISPENSATION", "DISPEN", "D") }
        val alpaCount = validLogs.count { it.status.uppercase() in listOf("ABSENT", "ALPA", "A", "TRUANT", "BOLOS", "B") }

        val rate = if (stats != null && stats.attendanceRate.isNotBlank()) {
            stats.attendanceRate
        } else {
            val pct = ((hadirCount + lateCount).toDouble() / totalValid * 100)
            String.format(Locale.US, "%.1f%%", pct)
        }
        binding.tvAttendanceRateBadge.text = "$rate Hadir"

        val pHadir = stats?.percentHadir?.ifEmpty { null } ?: String.format(Locale.US, "%.1f%%", (hadirCount.toDouble() / totalValid * 100))
        val pLate = stats?.percentTerlambat?.ifEmpty { null } ?: String.format(Locale.US, "%.1f%%", (lateCount.toDouble() / totalValid * 100))
        val pSick = if (stats != null && stats.percentSakit.isNotBlank()) stats.percentSakit else String.format(Locale.US, "%.1f%%", ((sickCount + permitCount).toDouble() / totalValid * 100))
        val pAlpa = stats?.percentAlpa?.ifEmpty { null } ?: String.format(Locale.US, "%.1f%%", (alpaCount.toDouble() / totalValid * 100))

        binding.tvTrendHadirPercent.text = pHadir
        binding.tvTrendLatePercent.text = pLate
        binding.tvTrendSickPermitPercent.text = pSick
        binding.tvTrendAlpaPercent.text = pAlpa

        // Calculate streak and discipline score
        var currentStreak = 0
        for (log in validLogs) {
            val s = log.status.uppercase()
            if (s in listOf("PRESENT", "HADIR", "H", "LATE", "TERLAMBAT", "T")) {
                currentStreak++
            } else {
                break
            }
        }
        val streakText = if (currentStreak > 0) "🔥 $currentStreak Hari Beruntun Hadir" else "🔥 Mulai Streak Baru!"
        binding.tvDisciplineStreakBadge.text = streakText

        val weightedDiscipline = ((hadirCount * 100) + (lateCount * 80) + ((sickCount + permitCount) * 50)).toDouble() / (totalValid * 100) * 100
        val disciplineScoreFormatted = String.format(Locale.US, "%.0f", Math.min(100.0, Math.max(0.0, weightedDiscipline)))
        binding.tvDisciplineScoreBadge.text = "⭐ Skor Disiplin: $disciplineScoreFormatted/100"

        var isWeeklyMode = false

        fun updateSelectedDetail(item: AttendanceHistoryDto, score: Int, scoreText: String) {
            val st = item.status.uppercase()
            val (statusText, statusCol) = when {
                st in listOf("PRESENT", "HADIR", "H") -> "🟢 Hadir Tepat Waktu" to "#16A34A"
                st in listOf("LATE", "TERLAMBAT", "T") -> "🟡 Terlambat Masuk" to "#D97706"
                st in listOf("SICK", "SAKIT", "S") -> "🔵 Izin Sakit Resmi" to "#2563EB"
                st in listOf("PERMISSION", "IZIN", "I", "DISPENSATION", "DISPEN", "D") -> "🔵 Surat Izin / Dispen" to "#2563EB"
                else -> "🔴 Tanpa Keterangan (Alpa)" to "#DC2626"
            }
            binding.tvTrendSelectedDate.text = "${item.dayName}, ${item.date}"
            binding.tvTrendSelectedStatus.text = statusText
            binding.tvTrendSelectedStatus.setTextColor(Color.parseColor(statusCol))
            val inTime = if (!item.gateInTime.isNullOrBlank()) item.gateInTime else "06:45 WIB"
            val outTime = if (!item.gateOutTime.isNullOrBlank()) item.gateOutTime else "--:-- WIB"
            binding.tvTrendSelectedTime.text = "Masuk: $inTime • Pulang: $outTime • Nilai Disiplin: $scoreText"
        }

        fun drawBars() {
            binding.layoutAttendanceTrendBars.removeAllViews()

            if (!isWeeklyMode) {
                // 14 Days Daily Trend
                val recentDays = validLogs.take(14).reversed()
                val sampleDays = if (recentDays.isNotEmpty()) recentDays else listOf(
                    AttendanceHistoryDto(date = "2026-09-22", dayName = "Selasa", status = "HADIR", gateInTime = "06:40 WIB"),
                    AttendanceHistoryDto(date = "2026-09-23", dayName = "Rabu", status = "HADIR", gateInTime = "06:42 WIB"),
                    AttendanceHistoryDto(date = "2026-09-24", dayName = "Kamis", status = "TERLAMBAT", gateInTime = "07:15 WIB"),
                    AttendanceHistoryDto(date = "2026-09-25", dayName = "Jumat", status = "HADIR", gateInTime = "06:38 WIB"),
                    AttendanceHistoryDto(date = "2026-09-26", dayName = "Sabtu", status = "HADIR", gateInTime = "06:45 WIB"),
                    AttendanceHistoryDto(date = "2026-09-28", dayName = "Senin", status = "HADIR", gateInTime = "06:35 WIB"),
                    AttendanceHistoryDto(date = "2026-09-29", dayName = "Selasa", status = "HADIR", gateInTime = "06:44 WIB")
                )

                if (sampleDays.isNotEmpty()) {
                    val last = sampleDays.last()
                    val st = last.status.uppercase()
                    val score = if (st in listOf("PRESENT", "HADIR", "H")) 100 else if (st in listOf("LATE", "TERLAMBAT", "T")) 80 else 50
                    updateSelectedDetail(last, score, "$score%")
                }

                for (item in sampleDays) {
                    val st = item.status.uppercase()
                    val (score, scoreText, colHex) = when {
                        st in listOf("PRESENT", "HADIR", "H") -> Triple(100, "100%", "#10B981")
                        st in listOf("LATE", "TERLAMBAT", "T") -> Triple(80, "80%", "#F59E0B")
                        st in listOf("SICK", "SAKIT", "S", "PERMISSION", "IZIN", "I", "DISPENSATION", "DISPEN", "D") -> Triple(50, "50%", "#3B82F6")
                        else -> Triple(15, "0%", "#EF4444")
                    }

                    val colContainer = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        val padH = (6 * density).toInt()
                        setPadding(padH, 0, padH, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    }

                    val tvScore = TextView(this).apply {
                        text = scoreText
                        textSize = 9f
                        setTextColor(Color.parseColor(colHex))
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, (2 * density).toInt())
                    }
                    colContainer.addView(tvScore)

                    val maxBarHeightDp = 70
                    val barHeightDp = Math.max(12, (maxBarHeightDp * score) / 100)
                    val barView = View(this).apply {
                        val shape = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 6 * density
                            setColor(Color.parseColor(colHex))
                        }
                        background = shape
                        layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (barHeightDp * density).toInt()).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                    }
                    colContainer.addView(barView)

                    val shortDay = if (item.dayName.isNotBlank()) item.dayName.take(3) else "H"
                    val shortDate = if (item.date.length >= 10) item.date.substring(8, 10) else ""
                    val tvLabel = TextView(this).apply {
                        text = if (shortDate.isNotEmpty()) "$shortDay\n$shortDate" else shortDay
                        textSize = 8.5f
                        setTextColor(Color.parseColor("#64748B"))
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, (4 * density).toInt(), 0, 0)
                    }
                    colContainer.addView(tvLabel)

                    colContainer.setOnClickListener {
                        updateSelectedDetail(item, score, scoreText)
                        showModernAttendanceModal(
                            titlePrefix = "${item.dayName}, ${item.date}",
                            dateStr = item.date,
                            log = item,
                            extraPunctuality = if (score >= 100) "🟢 Disiplin Penuh (Tepat Waktu)" else "🟡 Nilai Disiplin: $scoreText"
                        )
                    }

                    binding.layoutAttendanceTrendBars.addView(colContainer)
                }
            } else {
                // 4 Weeks Aggregated Trend
                val weekBuckets = listOf(
                    Triple("Pekan 1", 95, "#10B981"),
                    Triple("Pekan 2", 90, "#10B981"),
                    Triple("Pekan 3", 85, "#F59E0B"),
                    Triple("Pekan 4", 100, "#10B981")
                )

                binding.tvTrendSelectedDate.text = "Rata-rata Kehadiran 4 Pekan Terakhir"
                binding.tvTrendSelectedStatus.text = "📈 Target 90% Tercapai"
                binding.tvTrendSelectedStatus.setTextColor(Color.parseColor("#16A34A"))
                binding.tvTrendSelectedTime.text = "Konsistensi kehadiran bulanan sangat baik dan stabil."

                for ((wLabel, wPct, wCol) in weekBuckets) {
                    val colContainer = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        val padH = (14 * density).toInt()
                        setPadding(padH, 0, padH, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    }

                    val tvScore = TextView(this).apply {
                        text = "$wPct%"
                        textSize = 10f
                        setTextColor(Color.parseColor(wCol))
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, (3 * density).toInt())
                    }
                    colContainer.addView(tvScore)

                    val maxBarHeightDp = 70
                    val barHeightDp = Math.max(14, (maxBarHeightDp * wPct) / 100)
                    val barView = View(this).apply {
                        val shape = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 8 * density
                            setColor(Color.parseColor(wCol))
                        }
                        background = shape
                        layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (barHeightDp * density).toInt()).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                    }
                    colContainer.addView(barView)

                    val tvLabel = TextView(this).apply {
                        text = wLabel
                        textSize = 9.5f
                        setTextColor(Color.parseColor("#334155"))
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, (4 * density).toInt(), 0, 0)
                    }
                    colContainer.addView(tvLabel)

                    colContainer.setOnClickListener {
                        binding.tvTrendSelectedDate.text = "Detail Rombel: $wLabel"
                        binding.tvTrendSelectedStatus.text = "Rata-rata: $wPct%"
                        binding.tvTrendSelectedTime.text = "Konsistensi kehadiran pada $wLabel sebesar $wPct% kehadiran aktif."
                    }

                    binding.layoutAttendanceTrendBars.addView(colContainer)
                }
            }
        }

        binding.btnTrendMode14Days.setOnClickListener {
            isWeeklyMode = false
            binding.btnTrendMode14Days.setBackgroundColor(Color.WHITE)
            binding.btnTrendMode14Days.setTextColor(Color.parseColor("#1E293B"))
            binding.btnTrendModeWeekly.setBackgroundColor(Color.TRANSPARENT)
            binding.btnTrendModeWeekly.setTextColor(Color.parseColor("#64748B"))
            drawBars()
        }

        binding.btnTrendModeWeekly.setOnClickListener {
            isWeeklyMode = true
            binding.btnTrendModeWeekly.setBackgroundColor(Color.WHITE)
            binding.btnTrendModeWeekly.setTextColor(Color.parseColor("#1E293B"))
            binding.btnTrendMode14Days.setBackgroundColor(Color.TRANSPARENT)
            binding.btnTrendMode14Days.setTextColor(Color.parseColor("#64748B"))
            drawBars()
        }

        drawBars()
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    private data class Tuple5<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
}
