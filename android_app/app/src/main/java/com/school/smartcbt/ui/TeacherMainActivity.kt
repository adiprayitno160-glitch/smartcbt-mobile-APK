package com.school.smartcbt.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.school.smartcbt.R
import com.school.smartcbt.ScannerActivity
import com.school.smartcbt.data.model.*
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.databinding.ActivityTeacherMainBinding
import com.school.smartcbt.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class TeacherMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTeacherMainBinding
    private lateinit var sessionManager: SessionManager

    private val pickTeacherFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val uri = result.data?.data
            if (uri != null) {
                promptUploadTeacherEFileDialog(uri)
            }
        }
    }

    // --- Room Handover & Teaching Session State ---
    private var activeTeachingSession: TeachingSessionDto? = null
    private var nfcAdapter: android.nfc.NfcAdapter? = null
    private val roomScanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val roomCode = result.data?.getStringExtra(com.school.smartcbt.ScannerActivity.EXTRA_SCANNED_ROOM_CODE)
            if (!roomCode.isNullOrBlank()) {
                handleTeacherRoomCheckIn(roomCode = roomCode)
            }
        }
    }

    // --- CBT Proctor Banner State & 15-Minute Dynamic Token Lifecycle ---
    private var bannerActiveExam: ProctorExamDto? = null
    private var bannerActiveClassName: String? = null
    private var bannerRemainingSeconds: Long = 900
    private var bannerCountdownRunnable: Runnable? = null
    private var bannerSyncRunnable: Runnable? = null
    private val bannerHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeacherMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sessionManager = SessionManager(this)

        val teacherName = sessionManager.getName().ifEmpty { "Guru Pengampu" }
        binding.tvTeacherWelcome.text = teacherName

        // Format tanggal hari ini
        try {
            val sdf = java.text.SimpleDateFormat("EEEE, dd MMMM yyyy", java.util.Locale("id", "ID"))
            binding.tvTeacherDateSub.text = "📅 " + sdf.format(java.util.Date()) + " • SMPN 1 Boyolangu"
        } catch (e: Exception) {
            binding.tvTeacherDateSub.text = "📅 Hari Ini • SMP Negeri 1 Boyolangu"
        }

        try {
            nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(this)
        } catch (e: Exception) {
            nfcAdapter = null
        }

        applyTeacherRoleMenuVisibility()
        setupHeaderTeacherData()
        setupListeners()
        loadTeacherClasses()
        loadHomeroomSummary()
        loadTeacherEFiles()
        checkTeacherPiketStatus()
        loadProctorTokenBanner()
        loadTeacherDailyAttendanceStatus()
        loadCurrentTeachingSession()
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
        applyTeacherRoleMenuVisibility()
        setupHeaderTeacherData()
        loadTeacherClasses()
        loadHomeroomSummary()
        loadTeacherEFiles()
        checkTeacherPiketStatus()
        loadProctorTokenBanner()
        loadTeacherDailyAttendanceStatus()
        loadCurrentTeachingSession()
    }

    private fun applyTeacherRoleMenuVisibility() {
        val role = sessionManager.getRole().uppercase().trim()
        val subject = sessionManager.getTeachingSubject().uppercase().trim()
        val tugasTambahan = sessionManager.getTugasTambahan().uppercase().trim()
        val availableRoles = sessionManager.getAvailableRoles().map { it.uppercase().trim() }
        val homeroomClass = sessionManager.getClassName().trim()

        // 1. Guru PAI: HANYA untuk guru yang mengampu mapel PAI / Agama Islam atau bertugas PAI
        val isGuruPai = subject.contains("PAI") || subject.contains("AGAMA") || subject.contains("ISLAM") ||
                tugasTambahan.contains("PAI") || tugasTambahan.contains("AGAMA") ||
                availableRoles.any { it.contains("PAI") || it.contains("AGAMA") } ||
                role.contains("PAI")
        binding.gridTeacherPrayer.visibility = if (isGuruPai) View.VISIBLE else View.GONE

        // 2. Guru BK: HANYA untuk role guru BK / Konselor
        val isGuruBk = role.contains("BK") || role.contains("COUNSELOR") ||
                tugasTambahan.contains("BK") || availableRoles.any { it.contains("BK") || it.contains("COUNSELOR") }
        binding.gridTeacherBk.visibility = if (isGuruBk) View.VISIBLE else View.GONE
        binding.placeholderMenuBk.visibility = View.GONE

        // 3. Operator: HANYA untuk peran Operator / Admin
        val isOperator = role.contains("OPERATOR") || role.contains("ADMIN") ||
                tugasTambahan.contains("OPERATOR") || availableRoles.any { it.contains("OPERATOR") || it.contains("ADMIN") }
        binding.gridTeacherOperator.visibility = if (isOperator) View.VISIBLE else View.GONE

        // 4. Wali Kelas: HANYA untuk guru yang memiliki kelas binaan aktif
        val isWaliKelas = (homeroomClass.isNotEmpty() && homeroomClass != "-" && homeroomClass != "null") ||
                tugasTambahan.contains("WALI") || availableRoles.any { it.contains("WALI") }
        binding.gridTeacherWaliKelas.visibility = if (isWaliKelas) View.VISIBLE else View.GONE
        if (!isWaliKelas) {
            binding.cardHomeroom.visibility = View.GONE
        }

        // 5. Guru Piket: Ditampilkan jika ada tugas piket / aktif piket
        val isGuruPiket = tugasTambahan.contains("PIKET") || availableRoles.any { it.contains("PIKET") } ||
                (lastPiketSummary?.isPiketToday == true)
        binding.gridTeacherPiket.visibility = if (isGuruPiket) View.VISIBLE else View.GONE

        // Header role badge
        if (isGuruBk) {
            binding.tvRoleBadgeHeader.text = "🕊️ Guru BK / Konselor"
        } else if (isGuruPai && isWaliKelas) {
            binding.tvRoleBadgeHeader.text = "🕌 Guru PAI & Wali Kelas"
        } else if (isGuruPai) {
            binding.tvRoleBadgeHeader.text = "🕌 Guru Pembina PAI"
        } else if (isWaliKelas) {
            binding.tvRoleBadgeHeader.text = "⭐ Wali Kelas ($homeroomClass)"
        } else if (isOperator) {
            binding.tvRoleBadgeHeader.text = "💻 Guru & Operator CBT"
        } else {
            binding.tvRoleBadgeHeader.text = "👨‍🏫 Guru Pengampu"
        }
    }

    private fun setupHeaderTeacherData() {
        val subject = sessionManager.getTeachingSubject().ifEmpty { "Mata Pelajaran" }
        binding.tvTeacherHeaderSubject.text = "📖 Mapel: $subject"

        val teachingClasses = sessionManager.getTeachingClasses()
        val homeroomClass = sessionManager.getClassName()
        if (homeroomClass.isNotEmpty() && homeroomClass != "-") {
            binding.tvTeacherHeaderClassesBadge.text = "⭐ Wali Kelas: $homeroomClass"
        } else if (teachingClasses.isNotEmpty()) {
            val count = teachingClasses.split(",").size
            binding.tvTeacherHeaderClassesBadge.text = "🏫 $count Rombel ($teachingClasses)"
        } else {
            binding.tvTeacherHeaderClassesBadge.text = "🏫 Guru Pengampu"
        }

        val serverIp = sessionManager.getServerIp()
        if (serverIp.contains("cbt.smpn1boyolangu")) {
            binding.tvTeacherHeaderServerStatus.text = "🟢 Server VPS"
            binding.tvTeacherHeaderServerStatus.setBackgroundColor(Color.parseColor("#15803D"))
        } else {
            binding.tvTeacherHeaderServerStatus.text = "🟢 Server Lokal"
            binding.tvTeacherHeaderServerStatus.setBackgroundColor(Color.parseColor("#0284C7"))
        }

        // Setup click listener untuk kartu ringkasan cepat
        binding.cardTeacherQuickClasses.setOnClickListener {
            binding.gridTeacherRombel.performClick()
        }
        binding.cardTeacherQuickSchedule.setOnClickListener {
            binding.gridTeacherSchedule.performClick()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bannerCountdownRunnable?.let { bannerHandler.removeCallbacks(it) }
        bannerSyncRunnable?.let { bannerHandler.removeCallbacks(it) }
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun loadProctorTokenBanner() {
        ApiClient.getClient(this).getProctorCbtTokens(null, null).enqueue(object : Callback<ProctorCbtTokensResponse> {
            override fun onResponse(call: Call<ProctorCbtTokensResponse>, response: Response<ProctorCbtTokensResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val exams = body.exams ?: emptyList()
                    val supervisedClasses = body.supervisedClasses ?: emptyList()
                    val userRole = sessionManager.getRole().uppercase()
                    val isStaffAdmin = userRole.contains("ADMIN") || userRole.contains("OPERATOR")

                    // Banner HANYA muncul jika ada ujian yang benar-benar aktif (isTokenActive == true)
                    // DAN relevan dengan kelas yang diawasi oleh guru tersebut
                    val activeExam = exams.firstOrNull { exam ->
                        if (!exam.isTokenActive) return@firstOrNull false

                        if (isStaffAdmin) return@firstOrNull true

                        // Prioritas 1: Jika guru memiliki penugasan kelas pengawasan resmi
                        if (supervisedClasses.isNotEmpty()) {
                            val assigned = exam.assignedClasses ?: ""
                            val assignedList = assigned.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                            val matchesAssigned = assigned.equals("ALL", ignoreCase = true) ||
                                    assigned.equals("SEMUA KELAS", ignoreCase = true) ||
                                    supervisedClasses.any { sc -> assignedList.contains(sc.uppercase()) || assigned.contains(sc, ignoreCase = true) }
                            val matchesClassTokens = exam.classTokens != null && supervisedClasses.any { sc -> exam.classTokens.containsKey(sc) }
                            val matchesClassProctors = exam.classProctors != null && supervisedClasses.any { sc -> exam.classProctors.containsKey(sc) }
                            matchesAssigned || matchesClassTokens || matchesClassProctors
                        } else {
                            // Prioritas 2: Fallback ke rombel kelas yang diajar guru
                            val teacherClasses = sessionManager.getTeachingClasses().split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                            if (teacherClasses.isNotEmpty()) {
                                val assigned = exam.assignedClasses ?: ""
                                val assignedList = assigned.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                                assigned.equals("ALL", ignoreCase = true) ||
                                        assigned.equals("SEMUA KELAS", ignoreCase = true) ||
                                        teacherClasses.any { tc -> assignedList.contains(tc) || assigned.contains(tc, ignoreCase = true) }
                            } else {
                                false
                            }
                        }
                    }

                    if (activeExam != null) {
                        bannerActiveExam = activeExam
                        binding.cardTeacherProctorCbt.visibility = View.VISIBLE
                        binding.tvBannerCbtExamTitle.text = activeExam.title

                        // Tentukan kelas pengawasan yang ditugaskan ke guru ini
                        val targetClass = if (supervisedClasses.isNotEmpty()) {
                            supervisedClasses.first()
                        } else {
                            activeExam.assignedClasses?.split(",")?.firstOrNull()?.trim() ?: "Semua Kelas"
                        }
                        bannerActiveClassName = targetClass

                        val displayToken = activeExam.classTokens?.get(targetClass) ?: activeExam.token
                        binding.tvBannerCbtClassMeta.text = "🎯 Pengawasan: Kelas $targetClass • Mapel: ${activeExam.subject}"
                        binding.tvBannerCbtToken.text = displayToken

                        val sec = activeExam.remainingSeconds ?: 900
                        startBannerCountdown(sec)
                    } else {
                        // Tidak ada ujian aktif di kelas yang diawasi -> sembunyikan banner sepenuhnya
                        binding.cardTeacherProctorCbt.visibility = View.GONE
                        stopBannerCountdown()
                        bannerActiveExam = null
                    }
                } else {
                    binding.cardTeacherProctorCbt.visibility = View.GONE
                    stopBannerCountdown()
                    bannerActiveExam = null
                }
            }

            override fun onFailure(call: Call<ProctorCbtTokensResponse>, t: Throwable) {
                binding.cardTeacherProctorCbt.visibility = View.GONE
                stopBannerCountdown()
                bannerActiveExam = null
            }
        })

        // Auto-sync berkala setiap 15 detik agar sinkron realtime dengan server & monitoring web
        bannerSyncRunnable?.let { bannerHandler.removeCallbacks(it) }
        bannerSyncRunnable = Runnable {
            loadProctorTokenBanner()
        }
        bannerHandler.postDelayed(bannerSyncRunnable!!, 15000)
    }

    private fun startBannerCountdown(initialSeconds: Long) {
        bannerRemainingSeconds = initialSeconds
        bannerCountdownRunnable?.let { bannerHandler.removeCallbacks(it) }

        fun updateText() {
            if (bannerRemainingSeconds <= 0) {
                binding.tvBannerCbtCountdown.text = "⏳ REFRESH"
                binding.tvBannerCbtCountdown.setBackgroundColor(Color.parseColor("#FEE2E2"))
                binding.tvBannerCbtCountdown.setTextColor(Color.parseColor("#DC2626"))
            } else {
                val m = bannerRemainingSeconds / 60
                val s = bannerRemainingSeconds % 60
                binding.tvBannerCbtCountdown.text = "⏳ %02d:%02d".format(m, s)
                if (bannerRemainingSeconds <= 60) {
                    binding.tvBannerCbtCountdown.setBackgroundColor(Color.parseColor("#FEE2E2"))
                    binding.tvBannerCbtCountdown.setTextColor(Color.parseColor("#DC2626"))
                } else {
                    binding.tvBannerCbtCountdown.setBackgroundResource(com.school.smartcbt.R.drawable.bg_btn_amber_rounded)
                    binding.tvBannerCbtCountdown.setTextColor(Color.parseColor("#92400E"))
                }
            }
        }

        updateText()
        bannerCountdownRunnable = object : Runnable {
            override fun run() {
                if (bannerRemainingSeconds > 0) {
                    bannerRemainingSeconds--
                    updateText()
                    bannerHandler.postDelayed(this, 1000)
                } else {
                    updateText()
                    // 15 Menit habis -> otomatis ambil token baru dari server
                    loadProctorTokenBanner()
                }
            }
        }
        bannerHandler.postDelayed(bannerCountdownRunnable!!, 1000)
    }

    private fun stopBannerCountdown() {
        bannerCountdownRunnable?.let { bannerHandler.removeCallbacks(it) }
        bannerCountdownRunnable = null
    }

    // =========================================================================
    // FULLSCREEN DIALOG & SYSTEM HELPERS (MODERN FULL-SCREEN FOR ALL MENUS)
    // =========================================================================
    private fun createFullscreenDialog(): AlertDialog {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen).create()
        dialog.setOnShowListener {
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(Color.parseColor("#F8FAFC")))
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        return dialog
    }

    private fun getMenuHeaderColors(title: String): Pair<String, String> {
        val t = title.lowercase()
        return when {
            t.contains("jurnal") || t.contains("kbm") -> Pair("#4F46E5", "#3730A3") // Indigo
            t.contains("absens") || t.contains("presensi") -> Pair("#059669", "#047857") // Emerald
            t.contains("proktor") || t.contains("cbt") || t.contains("token") -> Pair("#7C3AED", "#5B21B6") // Purple
            t.contains("rombel") || t.contains("siswa") || t.contains("wali") -> Pair("#0284C7", "#0369A1") // Blue Cyan
            t.contains("piket") || t.contains("gerbang") -> Pair("#D97706", "#B45309") // Amber
            t.contains("tugas") || t.contains("pr") || t.contains("nilai") -> Pair("#0D9488", "#0F766E") // Teal
            t.contains("file") || t.contains("modul") -> Pair("#E11D48", "#BE123C") // Rose
            t.contains("bk") || t.contains("konseling") || t.contains("bimbingan") -> Pair("#0891B2", "#0E7490") // Cyan
            t.contains("raport") || t.contains("analisis") -> Pair("#2563EB", "#1D4ED8") // Royal Blue
            t.contains("pengumuman") || t.contains("broadcast") -> Pair("#EA580C", "#C2410C") // Orange
            t.contains("uks") || t.contains("kesehatan") -> Pair("#DC2626", "#991B1B") // Red
            else -> Pair("#064E3B", "#047857") // Default Emerald Slate
        }
    }

    private fun createFullscreenHeader(
        title: String,
        subtitle: String? = null,
        onClose: () -> Unit,
        actionBtnText: String? = null,
        onActionClick: (() -> Unit)? = null
    ): View {
        val statusBarHeight = try {
            val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        } catch (e: Exception) { 0 }

        val t = title.lowercase()
        val (cStart, cEnd) = getMenuHeaderColors(title)
        val colors = when {
            t.contains("estafet") || t.contains("ruang") || t.contains("sesi") ->
                intArrayOf(Color.parseColor("#064E3B"), Color.parseColor("#0D9488"), Color.parseColor("#0284C7"))
            t.contains("mushola") || t.contains("sholat") ->
                intArrayOf(Color.parseColor("#064E3B"), Color.parseColor("#0D9488"), Color.parseColor("#0284C7"))
            t.contains("peran") || t.contains("role") ->
                intArrayOf(Color.parseColor("#1E1B4B"), Color.parseColor("#4F46E5"), Color.parseColor("#06B6D4"))
            t.contains("izin") || t.contains("dispensasi") ->
                intArrayOf(Color.parseColor("#831843"), Color.parseColor("#DB2777"), Color.parseColor("#F472B6"))
            t.contains("piket") ->
                intArrayOf(Color.parseColor("#78350F"), Color.parseColor("#D97706"), Color.parseColor("#F59E0B"))
            t.contains("jurnal") || t.contains("kbm") || t.contains("matpel") ->
                intArrayOf(Color.parseColor("#312E81"), Color.parseColor("#6366F1"), Color.parseColor("#EC4899"))
            t.contains("absens") || t.contains("presensi") ->
                intArrayOf(Color.parseColor("#064E3B"), Color.parseColor("#059669"), Color.parseColor("#10B981"))
            t.contains("proktor") || t.contains("cbt") || t.contains("token") ->
                intArrayOf(Color.parseColor("#4C1D95"), Color.parseColor("#7C3AED"), Color.parseColor("#C084FC"))
            else ->
                intArrayOf(Color.parseColor(cStart), Color.parseColor(cEnd))
        }
        val gradient = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            colors
        )

        val headerRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient
            elevation = 6f * resources.displayMetrics.density
            val topPad = if (statusBarHeight > 0) statusBarHeight + (6 * resources.displayMetrics.density).toInt() else (12 * resources.displayMetrics.density).toInt()
            val bottomPad = (14 * resources.displayMetrics.density).toInt()
            val sidePad = (16 * resources.displayMetrics.density).toInt()
            setPadding(sidePad, topPad, sidePad, bottomPad)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val btnBackBackground = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#33FFFFFF"))
            cornerRadius = 20f * resources.displayMetrics.density
        }

        val btnBack = TextView(this).apply {
            text = "← Kembali"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = btnBackBackground
            val pH = (14 * resources.displayMetrics.density).toInt()
            val pV = (7 * resources.displayMetrics.density).toInt()
            setPadding(pH, pV, pH, pV)
            setOnClickListener { onClose() }
        }
        topRow.addView(btnBack)

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * resources.displayMetrics.density).toInt()
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
        }

        val tvT = TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleBox.addView(tvT)

        if (!subtitle.isNullOrEmpty()) {
            val tvS = TextView(this).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#D1FAE5"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            titleBox.addView(tvS)
        }
        topRow.addView(titleBox)

        if (!actionBtnText.isNullOrEmpty() && onActionClick != null) {
            val btnActionBackground = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 18f * resources.displayMetrics.density
            }
            val btnAction = Button(this).apply {
                text = actionBtnText
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(cStart))
                background = btnActionBackground
                elevation = 2f * resources.displayMetrics.density
                val padH = (14 * resources.displayMetrics.density).toInt()
                setPadding(padH, 0, padH, 0)
                setOnClickListener { onActionClick() }
            }
            topRow.addView(btnAction)
        }

        headerRoot.addView(topRow)
        return headerRoot
    }

    private fun getDeviceLocation(): Triple<Double, Double, Boolean> {
        val schoolLat = -8.125506
        val schoolLng = 111.893526
        var currentLat: Double? = null
        var currentLng: Double? = null
        var isMock = false

        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = locationManager?.getProviders(true) ?: emptyList()
            var bestLoc: Location? = null
            for (provider in providers) {
                val lastLoc = try {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        locationManager?.getLastKnownLocation(provider)
                    } else null
                } catch (e: Exception) {
                    null
                }

                if (lastLoc != null) {
                    // Jangan gunakan lokasi basi tanpa pengecekan: usia < 60 detik dan akurasi < 100 meter
                    val ageMs = System.currentTimeMillis() - lastLoc.time
                    if (ageMs < 60_000 && lastLoc.accuracy < 100f) {
                        if (bestLoc == null || lastLoc.accuracy < bestLoc.accuracy || lastLoc.time > bestLoc.time) {
                            bestLoc = lastLoc
                        }
                    }
                }
            }

            if (bestLoc != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (bestLoc.isMock) isMock = true
                }
                val dist = FloatArray(1)
                Location.distanceBetween(bestLoc.latitude, bestLoc.longitude, schoolLat, schoolLng, dist)
                val distMeters = dist[0]

                // Jika lokasi melompat jauh (>500m) dengan akurasi buruk (>30m), fallback ke koordinat sekolah
                if (distMeters > 500 && bestLoc.accuracy > 30f) {
                    currentLat = schoolLat
                    currentLng = schoolLng
                } else {
                    currentLat = bestLoc.latitude
                    currentLng = bestLoc.longitude
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback default SMPN 1 Boyolangu jika null (-8.125506, 111.893526)
        if (currentLat == null || currentLng == null) {
            currentLat = schoolLat
            currentLng = schoolLng
        }

        return Triple(currentLat, currentLng, isMock)
    }

    // ==================== PRESENSI HARIAN GURU (1-TAP GEOLOCATION) ====================
    private fun loadTeacherDailyAttendanceStatus() {
        binding.btnTeacherGeolocationAction.setOnClickListener {
            handleTeacherGeolocationAttendance()
        }

        ApiClient.getClient(this).getTeacherTodayAttendance().enqueue(object : Callback<TeacherTodayAttendanceResponse> {
            override fun onResponse(call: Call<TeacherTodayAttendanceResponse>, response: Response<TeacherTodayAttendanceResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    binding.tvTeacherInTime.text = data.inTime ?: "--:-- WIB"
                    binding.tvTeacherOutTime.text = data.outTime ?: "--:-- WIB"

                    if (!data.hasIn) {
                        // Belum presensi datang
                        binding.btnTeacherGeolocationAction.isEnabled = true
                        binding.btnTeacherGeolocationAction.text = "📍 1-TAP PRESENSI DATANG (GEOLOCATION)"
                        binding.btnTeacherGeolocationAction.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                        binding.tvTeacherAttendanceStatusText.text = "Belum presensi datang hari ini"
                        binding.tvTeacherGpsBadge.text = "SIAP DATANG"
                        binding.tvTeacherGpsBadge.setBackgroundResource(com.school.smartcbt.R.drawable.bg_badge_emerald)
                    } else if (!data.hasOut) {
                        // Sudah presensi datang, siap presensi pulang
                        binding.btnTeacherGeolocationAction.isEnabled = true
                        binding.btnTeacherGeolocationAction.text = "📍 1-TAP PRESENSI PULANG (GEOLOCATION)"
                        binding.btnTeacherGeolocationAction.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1D4ED8"))
                        binding.tvTeacherAttendanceStatusText.text = "Sudah presensi datang (${data.inTime}) • Siap presensi pulang"
                        binding.tvTeacherGpsBadge.text = "SIAP PULANG"
                        binding.tvTeacherGpsBadge.setBackgroundResource(com.school.smartcbt.R.drawable.bg_badge_emerald)
                    } else {
                        // Sudah lengkap datang & pulang
                        binding.btnTeacherGeolocationAction.isEnabled = false
                        binding.btnTeacherGeolocationAction.text = "✔ PRESENSI HARI INI LENGKAP"
                        binding.btnTeacherGeolocationAction.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#475569"))
                        binding.tvTeacherAttendanceStatusText.text = "Kehadiran hari ini lengkap (Datang: ${data.inTime} | Pulang: ${data.outTime})"
                        binding.tvTeacherGpsBadge.text = "LENGKAP"
                        binding.tvTeacherGpsBadge.setBackgroundResource(com.school.smartcbt.R.drawable.bg_badge_emerald)
                    }
                }
            }

            override fun onFailure(call: Call<TeacherTodayAttendanceResponse>, t: Throwable) {
                // Fallback safe
            }
        })
    }

    private fun handleTeacherGeolocationAttendance() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                2001
            )
            Toast.makeText(this, "Izin GPS diperlukan untuk verifikasi lokasi presensi guru.", Toast.LENGTH_SHORT).show()
            return
        }

        val (lat, lng, isMock) = getDeviceLocation()
        if (isMock) {
            AlertDialog.Builder(this)
                .setTitle("🚨 Fake GPS Terdeteksi")
                .setMessage("Aplikasi mendeteksi penggunaan Fake GPS / Mock Location pada perangkat Anda. Presensi dibatalkan demi integritas sekolah.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Mengerti", null)
                .show()
            return
        }

        binding.btnTeacherGeolocationAction.isEnabled = false
        binding.btnTeacherGeolocationAction.text = "Memverifikasi Lokasi GPS..."

        val req = TeacherGeoAttendanceRequest(
            lat = lat,
            lng = lng,
            isFakeGps = isMock
        )

        ApiClient.getClient(this).teacherGeolocationAttendance(req).enqueue(object : Callback<TeacherGeoAttendanceResponse> {
            override fun onResponse(call: Call<TeacherGeoAttendanceResponse>, response: Response<TeacherGeoAttendanceResponse>) {
                val body = response.body()
                if (response.isSuccessful && body?.success == true) {
                    val time = body.timeStr ?: SimpleDateFormat("HH:mm", Locale("id", "ID")).format(Date()) + " WIB"
                    val isGateOut = body.type == "GATE_OUT"
                    val actTitle = if (isGateOut) "✔ Presensi Pulang Berhasil!" else "✔ Presensi Datang Berhasil!"

                    AlertDialog.Builder(this@TeacherMainActivity)
                        .setTitle(actTitle)
                        .setMessage("${body.message ?: "Presensi berhasil dicatat via Geolocation GPS."}\n\nWaktu: $time\nLokasi: Lingkungan SMPN 1 Boyolangu.")
                        .setIcon(android.R.drawable.ic_dialog_info)
                        .setPositiveButton("Alhamdulillah, Selesai", null)
                        .show()

                    loadTeacherDailyAttendanceStatus()
                } else {
                    val rawErr = response.errorBody()?.string() ?: ""
                    var userMsg = body?.message ?: "Gagal memproses presensi guru"
                    try {
                        val json = org.json.JSONObject(rawErr)
                        if (json.has("message")) {
                            userMsg = json.getString("message")
                        }
                    } catch (e: Exception) {}

                    AlertDialog.Builder(this@TeacherMainActivity)
                        .setTitle("⚠️ Presensi Ditolak")
                        .setMessage(userMsg)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton("Tutup", null)
                        .show()

                    loadTeacherDailyAttendanceStatus()
                }
            }

            override fun onFailure(call: Call<TeacherGeoAttendanceResponse>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
                loadTeacherDailyAttendanceStatus()
            }
        })
    }

    // --- Dokumen & E-File Guru State ---
    private var currentEFileTab = "MY_FILES" // "MY_FILES" or "INCOMING"
    private var currentEFileCategory = "Semua"
    private var teacherMyFiles: List<TeacherEFileDto> = emptyList()
    private var teacherIncomingFiles: List<TeacherEFileDto> = emptyList()
    private var teacherUnreadIncomingCount: Int = 0
    private var activeDocumentsDialogRefresh: (() -> Unit)? = null

    private fun promptUploadTeacherEFileDialog(uri: Uri) {
        var fileName = "dokumen_guru.pdf"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        fileName = cursor.getString(nameIdx)
                    }
                }
            }
        } catch (e: Exception) {}

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
        }

        val tvInfo = TextView(this).apply {
            text = "Unggah berkas ke repositori dokumen mengajar mandiri Anda.\nBerkas terpilih: $fileName"
            setTextColor(Color.parseColor("#475569"))
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }

        val etTitle = EditText(this).apply {
            hint = "Judul Dokumen (contoh: Modul Ajar Matematika Bab 4)"
            setText(fileName.substringBeforeLast("."))
            textSize = 13f
        }

        val tvCat = TextView(this).apply {
            text = "Kategori Dokumen:"
            setTextColor(Color.parseColor("#334155"))
            textSize = 12f
            setPadding(0, 10, 0, 4)
        }

        val spinnerCategory = Spinner(this)
        val categories = arrayOf(
            "MODUL_AJAR - Modul Ajar & Bahan Ajar",
            "RPP_SILABUS - RPP / Perangkat Pembelajaran",
            "MATERI_SISWA - Handout / Materi Siswa",
            "SOAL_LATIHAN - Bank Soal & Kisi-Kisi",
            "SURAT_TUGAS - Surat Tugas & Sertifikasi",
            "PRIBADI - Berkas Administrasi Lainnya"
        )
        spinnerCategory.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)

        val etDesc = EditText(this).apply {
            hint = "Catatan tambahan (opsional)"
            textSize = 13f
        }

        layout.addView(tvInfo)
        layout.addView(etTitle)
        layout.addView(tvCat)
        layout.addView(spinnerCategory)
        layout.addView(etDesc)

        AlertDialog.Builder(this)
            .setTitle("📁 Unggah E-File Guru")
            .setView(layout)
            .setPositiveButton("Unggah Sekarang") { _, _ ->
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(this, "Judul dokumen wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val selectedCategory = spinnerCategory.selectedItem.toString().substringBefore(" -")
                val description = etDesc.text.toString().trim()

                Toast.makeText(this, "Membaca berkas & mengunggah...", Toast.LENGTH_SHORT).show()

                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()

                    if (bytes != null) {
                        val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val req = UploadTeacherEFileRequest(
                            title = title,
                            category = selectedCategory,
                            fileBase64 = base64Str,
                            fileName = fileName,
                            description = if (description.isNotEmpty()) description else null
                        )

                        ApiClient.getClient(this).uploadTeacherEFile(req).enqueue(object : Callback<BasicResponse> {
                            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                                if (response.isSuccessful) {
                                    Toast.makeText(this@TeacherMainActivity, "✅ Berkas berhasil disimpan ke repositori Guru!", Toast.LENGTH_LONG).show()
                                    loadTeacherEFiles()
                                } else {
                                    Toast.makeText(this@TeacherMainActivity, "Gagal mengunggah berkas guru", Toast.LENGTH_SHORT).show()
                                }
                            }

                            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                                Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal memproses berkas: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun loadTeacherEFiles() {
        ApiClient.getClient(this).getTeacherEFiles().enqueue(object : Callback<TeacherEFilesResponse> {
            override fun onResponse(call: Call<TeacherEFilesResponse>, response: Response<TeacherEFilesResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val allList = body.files ?: emptyList()
                    teacherMyFiles = body.myFiles ?: allList.filter { it.isFromAdmin != true }
                    teacherIncomingFiles = body.incomingFiles ?: allList.filter { it.isFromAdmin == true }
                    teacherUnreadIncomingCount = body.unreadIncomingCount ?: teacherIncomingFiles.count { it.isRead != true }
                    activeDocumentsDialogRefresh?.invoke()
                }
            }

            override fun onFailure(call: Call<TeacherEFilesResponse>, t: Throwable) {
                // Ignore silent failure
            }
        })
    }

    private fun showTeacherDocumentsDialog() {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "📁 Dokumen & E-File Guru",
            subtitle = "Pusat berkas modul ajar, RPP, dan surat kedinasan",
            onClose = { dialog.dismiss() },
            actionBtnText = "➕ Unggah File",
            onActionClick = {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                }
                pickTeacherFileLauncher.launch(intent)
            }
        )
        root.addView(header)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(dialogView)

        // Two-Way Tab Selector
        val tabLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
        }

        val tabMyFiles = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = android.view.Gravity.CENTER
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val tabIncoming = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = android.view.Gravity.CENTER
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        tabLayout.addView(tabMyFiles)
        tabLayout.addView(tabIncoming)
        dialogView.addView(tabLayout)

        // Category Chips
        val hScrollView = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
                bottomMargin = 8
            }
            isHorizontalScrollBarEnabled = false
        }
        val chipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        hScrollView.addView(chipsContainer)
        dialogView.addView(hScrollView)

        // Files container
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val filesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(filesContainer)
        dialogView.addView(scrollView)

        fun updateTabsVisual() {
            val activeBg = Color.parseColor("#059669")
            val inactiveBg = Color.parseColor("#E2E8F0")
            if (currentEFileTab == "MY_FILES") {
                tabMyFiles.setBackgroundColor(activeBg)
                tabMyFiles.setTextColor(Color.WHITE)
                tabIncoming.setBackgroundColor(inactiveBg)
                tabIncoming.setTextColor(Color.parseColor("#475569"))
            } else {
                tabMyFiles.setBackgroundColor(inactiveBg)
                tabMyFiles.setTextColor(Color.parseColor("#475569"))
                tabIncoming.setBackgroundColor(activeBg)
                tabIncoming.setTextColor(Color.WHITE)
            }
            tabMyFiles.text = "📤 File Saya (${teacherMyFiles.size})"
            tabIncoming.text = if (teacherUnreadIncomingCount > 0) "📥 Masuk (${teacherIncomingFiles.size}) • 🔴 $teacherUnreadIncomingCount" else "📥 Masuk (${teacherIncomingFiles.size})"
        }

        fun renderFileList() {
            filesContainer.removeAllViews()
            val sourceList = if (currentEFileTab == "MY_FILES") teacherMyFiles else teacherIncomingFiles
            val filtered = if (currentEFileCategory == "Semua") {
                sourceList
            } else {
                sourceList.filter { it.category.contains(currentEFileCategory, ignoreCase = true) }
            }

            if (filtered.isEmpty()) {
                val emptyMsg = if (currentEFileTab == "MY_FILES") {
                    "Belum ada berkas mandiri dalam kategori '$currentEFileCategory'.\nKetuk tombol '➕ Unggah File' di atas untuk menambah RPP/Modul Anda."
                } else {
                    "Belum ada berkas masuk dari Admin/Operator sekolah dalam kategori '$currentEFileCategory'."
                }
                val tvEmpty = TextView(this).apply {
                    text = emptyMsg
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    setPadding(16, 32, 16, 32)
                }
                filesContainer.addView(tvEmpty)
                return
            }

            for (file in filtered) {
                val card = CardView(this).apply {
                    radius = 10f * resources.displayMetrics.density
                    cardElevation = 2f * resources.displayMetrics.density
                    setCardBackgroundColor(Color.parseColor("#F8FAFC"))
                    useCompatPadding = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 4, 0, 6)
                    }
                }

                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 12, 16, 12)
                }

                val topRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val badgeCat = TextView(this).apply {
                    text = file.category
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#065F46"))
                    setBackgroundColor(Color.parseColor("#D1FAE5"))
                    setPadding(8, 2, 8, 2)
                }

                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                }

                val dateStr = file.uploadedAt?.let {
                    if (it.contains("T")) it.substringBefore("T") else it
                } ?: ""
                val tvDate = TextView(this).apply {
                    text = dateStr
                    textSize = 10f
                    setTextColor(Color.parseColor("#94A3B8"))
                }

                topRow.addView(badgeCat)
                topRow.addView(spacer)
                topRow.addView(tvDate)

                val tvDocTitle = TextView(this).apply {
                    text = file.title
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#0F172A"))
                    setPadding(0, 6, 0, 2)
                }

                val tvDesc = if (!file.description.isNullOrEmpty()) {
                    TextView(this).apply {
                        text = file.description
                        textSize = 11f
                        setTextColor(Color.parseColor("#64748B"))
                        setPadding(0, 0, 0, 4)
                    }
                } else null

                val btnRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 6, 0, 0)
                }

                val btnOpen = Button(this).apply {
                    text = "📖 Buka PDF"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#059669"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (36 * resources.displayMetrics.density).toInt()
                    ).apply {
                        marginEnd = 8
                    }
                    setOnClickListener {
                        if (currentEFileTab == "INCOMING" && file.isRead != true) {
                            markTeacherEFileRead(file, silent = true)
                        }
                        openTeacherPdf(file.title, file.fileUrl)
                    }
                }
                btnRow.addView(btnOpen)

                if (currentEFileTab == "MY_FILES") {
                    val btnDelete = Button(this).apply {
                        text = "🗑️ Hapus"
                        textSize = 11f
                        setTextColor(Color.parseColor("#DC2626"))
                        setBackgroundColor(Color.parseColor("#FEE2E2"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            (36 * resources.displayMetrics.density).toInt()
                        )
                        setOnClickListener {
                            confirmDeleteTeacherEFile(file)
                        }
                    }
                    btnRow.addView(btnDelete)
                } else if (file.isRead != true) {
                    val btnMarkRead = Button(this).apply {
                        text = "✅ Tandai Dibaca"
                        textSize = 11f
                        setTextColor(Color.parseColor("#0284C7"))
                        setBackgroundColor(Color.parseColor("#E0F2FE"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            (36 * resources.displayMetrics.density).toInt()
                        )
                        setOnClickListener {
                            markTeacherEFileRead(file, silent = false)
                        }
                    }
                    btnRow.addView(btnMarkRead)
                }

                inner.addView(topRow)
                inner.addView(tvDocTitle)
                if (tvDesc != null) inner.addView(tvDesc)
                inner.addView(btnRow)
                card.addView(inner)
                filesContainer.addView(card)
            }
        }

        fun renderChips() {
            chipsContainer.removeAllViews()
            val categories = listOf("Semua", "Profil", "Mengajar", "Surat", "Lainnya")
            for (cat in categories) {
                val isSelected = currentEFileCategory.equals(cat, ignoreCase = true)
                val chipTv = TextView(this).apply {
                    text = cat
                    textSize = 11f
                    setPadding(18, 8, 18, 8)
                    setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#475569"))
                    setBackgroundColor(if (isSelected) Color.parseColor("#059669") else Color.parseColor("#F1F5F9"))
                    setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = 8
                    }
                    setOnClickListener {
                        currentEFileCategory = cat
                        renderChips()
                        renderFileList()
                    }
                }
                chipsContainer.addView(chipTv)
            }
        }

        tabMyFiles.setOnClickListener {
            currentEFileTab = "MY_FILES"
            updateTabsVisual()
            renderFileList()
        }

        tabIncoming.setOnClickListener {
            currentEFileTab = "INCOMING"
            updateTabsVisual()
            renderFileList()
        }

        updateTabsVisual()
        renderChips()
        renderFileList()

        activeDocumentsDialogRefresh = {
            updateTabsVisual()
            renderFileList()
        }

        dialog.setView(root)
        dialog.setOnDismissListener {
            activeDocumentsDialogRefresh = null
        }
        dialog.show()
    }

    private fun markTeacherEFileRead(file: TeacherEFileDto, silent: Boolean = false) {
        ApiClient.getClient(this).markTeacherEFileAsRead(file.id).enqueue(object : Callback<BasicResponse> {
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                if (response.isSuccessful) {
                    if (!silent) {
                        Toast.makeText(this@TeacherMainActivity, "Dokumen ditandai telah dibaca", Toast.LENGTH_SHORT).show()
                    }
                    loadTeacherEFiles()
                }
            }

            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                // Ignore silent failure
            }
        })
    }

    private fun openTeacherPdf(title: String, fileUrl: String) {
        val fullUrl = if (fileUrl.startsWith("http")) fileUrl else ApiClient.getBaseServerUrl(this) + "/" + fileUrl.trimStart('/')
        val intent = Intent(this, PdfViewerActivity::class.java).apply {
            putExtra(PdfViewerActivity.EXTRA_TITLE, title)
            putExtra(PdfViewerActivity.EXTRA_URL, fullUrl)
            putExtra(PdfViewerActivity.EXTRA_DOC_TYPE, "E-FILE GURU")
        }
        startActivity(intent)
    }

    private fun confirmDeleteTeacherEFile(file: TeacherEFileDto) {
        AlertDialog.Builder(this)
            .setTitle("Hapus Berkas E-File")
            .setMessage("Apakah Anda yakin ingin menghapus berkas \"${file.title}\"?")
            .setPositiveButton("Hapus") { _, _ ->
                ApiClient.getClient(this).deleteTeacherEFile(file.id).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@TeacherMainActivity, "🗑️ Dokumen guru berhasil dihapus", Toast.LENGTH_SHORT).show()
                            loadTeacherEFiles()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal menghapus dokumen", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun loadTeacherClasses() {
        ApiClient.getClient(this).getTeacherClasses().enqueue(object : Callback<TeacherClassesResponse> {
            override fun onResponse(call: Call<TeacherClassesResponse>, response: Response<TeacherClassesResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    renderTeacherClasses(data.classes)
                }
            }

            override fun onFailure(call: Call<TeacherClassesResponse>, t: Throwable) {
                // Silently keep default UI
            }
        })
    }

    private fun renderTeacherClasses(classes: List<TeacherClassDto>) {
        binding.containerTeacherClasses.removeAllViews()
        binding.tvClassesCount.text = "${classes.size} Rombel"

        val totalStudents = classes.sumOf { it.totalStudents ?: 0 }
        binding.tvQuickClassesTotal.text = "${classes.size} Rombel"
        binding.tvQuickClassesSub.text = if (totalStudents > 0) "Total $totalStudents Siswa • Buka →" else "Ketuk untuk lihat siswa →"

        if (classes.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "Belum ada jadwal rombel kelas terdaftar."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 12, 0, 12)
            }
            binding.containerTeacherClasses.addView(tvEmpty)
            return
        }

        for (cls in classes) {
            val card = CardView(this).apply {
                radius = 12f
                cardElevation = 1f
                setCardBackgroundColor(if (cls.isHomeroom == true) Color.parseColor("#F0FDF4") else Color.parseColor("#F8FAFC"))
                useCompatPadding = true
            }

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvClassName = TextView(this).apply {
                text = "Kelas ${cls.className}"
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val teacherActualName = sessionManager.getName().ifEmpty { "Guru Pengampu" }
            val tvRoleBadge = TextView(this).apply {
                text = if (cls.isHomeroom == true) "⭐ Wali Kelas" else teacherActualName
                setTextColor(if (cls.isHomeroom == true) Color.parseColor("#15803D") else Color.parseColor("#0369A1"))
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundColor(if (cls.isHomeroom == true) Color.parseColor("#DCFCE7") else Color.parseColor("#E0F2FE"))
                setPadding(10, 4, 10, 4)
            }

            headerRow.addView(tvClassName)
            headerRow.addView(tvRoleBadge)

            val tvDetails = TextView(this).apply {
                text = "${cls.subject ?: "Mata Pelajaran"} • ${cls.totalStudents ?: 0} Siswa"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11.5f
                setPadding(0, 4, 0, 8)
            }

            val btnViewStudents = Button(this).apply {
                text = "Lihat Siswa →"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#059669"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    showStudentsOfClassDialog(cls.className)
                }
            }

            layout.addView(headerRow)
            layout.addView(tvDetails)
            layout.addView(btnViewStudents)
            card.addView(layout)

            binding.containerTeacherClasses.addView(card)
        }
    }

    private fun showStudentsOfClassDialog(className: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setMessage("Memuat data siswa kelas $className...")
            .setCancelable(false)
            .show()

        ApiClient.getClient(this).getStudentsByClass(className).enqueue(object : Callback<ClassStudentsResponse> {
            override fun onResponse(call: Call<ClassStudentsResponse>, response: Response<ClassStudentsResponse>) {
                progressDialog.dismiss()
                if (response.isSuccessful && response.body() != null) {
                    val students = response.body()!!.students
                    displayStudentsListDialog(className, students)
                } else {
                    Toast.makeText(this@TeacherMainActivity, "Gagal memuat siswa kelas $className", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<ClassStudentsResponse>, t: Throwable) {
                progressDialog.dismiss()
                Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun displayStudentsListDialog(className: String, students: List<StudentRombelDto>) {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "Daftar Siswa Kelas $className",
            subtitle = "Total: ${students.size} Siswa • Kontak Wali & Profil Rombel",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(dialogView)

        // Tombol Absensi Cepat 1-Klik di bagian atas dialog
        val btnQuickAttendance = Button(this).apply {
            text = "⚡ Absensi Cepat Kelas Ini (1-Klik Hadir Semua)"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#059669"))
            setOnClickListener {
                val req = mapOf("className" to className, "subjectName" to "Presensi Guru")
                ApiClient.getClient(this@TeacherMainActivity).quickMarkAllAttendance(req).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@TeacherMainActivity, "✅ Seluruh siswa kelas $className ditandai HADIR!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal absensi cepat kelas", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, "Koneksi gagal", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
        dialogView.addView(btnQuickAttendance)

        // Search Bar Siswa
        val etSearch = EditText(this).apply {
            hint = "🔍 Cari nama siswa atau NISN..."
            textSize = 12.5f
            setSingleLine()
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 10, 0, 8)
            layoutParams = lp
        }
        dialogView.addView(etSearch)

        // Gender Filter Buttons
        var activeGenderFilter = "ALL" // ALL, L, P
        var currentSearch = ""

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
        }

        val btnAll = Button(this).apply {
            text = "Semua (${students.size})"
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0284C7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
            }
        }
        val btnMale = Button(this).apply {
            val maleCount = students.count { it.gender?.uppercase()?.startsWith("L") == true }
            text = "👦 L ($maleCount)"
            textSize = 10.5f
            setTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4
            }
        }
        val btnFemale = Button(this).apply {
            val femaleCount = students.count { it.gender?.uppercase()?.startsWith("P") == true }
            text = "👧 P ($femaleCount)"
            textSize = 10.5f
            setTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        filterRow.addView(btnAll)
        filterRow.addView(btnMale)
        filterRow.addView(btnFemale)
        dialogView.addView(filterRow)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (420 * resources.displayMetrics.density).toInt())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(container)
        dialogView.addView(scrollView)

        fun renderFilteredStudents() {
            container.removeAllViews()
            val filtered = students.filter { s ->
                val matchSearch = currentSearch.isEmpty() ||
                        s.name.contains(currentSearch, ignoreCase = true) ||
                        (s.nisn ?: "").contains(currentSearch) ||
                        (s.username ?: "").contains(currentSearch)
                val matchGender = when (activeGenderFilter) {
                    "L" -> s.gender?.uppercase()?.startsWith("L") == true
                    "P" -> s.gender?.uppercase()?.startsWith("P") == true
                    else -> true
                }
                matchSearch && matchGender
            }

            if (filtered.isEmpty()) {
                val tvEmpty = TextView(this).apply {
                    text = "Tidak ditemukan siswa yang sesuai filter."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12f
                    setPadding(0, 20, 0, 20)
                    gravity = android.view.Gravity.CENTER
                }
                container.addView(tvEmpty)
                return
            }

            for ((index, s) in filtered.withIndex()) {
                val card = CardView(this).apply {
                    radius = 12f
                    cardElevation = 1.5f
                    setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                    useCompatPadding = true
                }

                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(14, 12, 14, 12)
                }

                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                // Avatar Icon
                val isFemale = s.gender?.uppercase()?.startsWith("P") == true
                val tvAvatar = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                        marginEnd = 12
                    }
                    background = ContextCompat.getDrawable(this@TeacherMainActivity, if (isFemale) com.school.smartcbt.R.drawable.bg_icon_purple else com.school.smartcbt.R.drawable.bg_icon_blue)
                    gravity = android.view.Gravity.CENTER
                    text = if (s.name.isNotEmpty()) s.name.first().uppercase() else "S"
                    setTextColor(if (isFemale) Color.parseColor("#BE185D") else Color.parseColor("#1D4ED8"))
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val infoLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvName = TextView(this).apply {
                    text = "${index + 1}. ${s.name}"
                    setTextColor(Color.parseColor("#0F172A"))
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val genderLabel = if (isFemale) "Perempuan (P)" else "Laki-laki (L)"
                val tvMeta = TextView(this).apply {
                    text = "NISN: ${s.nisn ?: "-"} • $genderLabel"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 11f
                    setPadding(0, 2, 0, 0)
                }

                infoLayout.addView(tvName)
                infoLayout.addView(tvMeta)

                val pointsVal = s.points ?: 100
                val badgePoints = TextView(this).apply {
                    text = "🎖️ $pointsVal"
                    setTextColor(if (pointsVal >= 80) Color.parseColor("#15803D") else Color.parseColor("#B45309"))
                    textSize = 10.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setBackgroundColor(if (pointsVal >= 80) Color.parseColor("#DCFCE7") else Color.parseColor("#FEF3C7"))
                    setPadding(8, 3, 8, 3)
                }

                rowLayout.addView(tvAvatar)
                rowLayout.addView(infoLayout)
                rowLayout.addView(badgePoints)
                itemLayout.addView(rowLayout)

                // Action Row: WhatsApp Wali & Profil Siswa
                val actionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 0)
                }

                val btnWa = Button(this).apply {
                    text = "💬 WA Ortu"
                    textSize = 10.5f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#16A34A"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 6
                    }
                    setOnClickListener {
                        val rawPhone = s.parentPhone?.trim() ?: ""
                        val cleanPhone = rawPhone.replace("+", "").replace("-", "").replace(" ", "")
                        if (cleanPhone.isNotEmpty()) {
                            val intlPhone = if (cleanPhone.startsWith("0")) "62" + cleanPhone.substring(1) else cleanPhone
                            val msg = Uri.encode("Halo Bapak/Ibu Wali dari ${s.name} (Kelas $className), salam hangat dari Guru SMPN 1 Boyolangu.")
                            val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intlPhone?text=$msg"))
                            try {
                                startActivity(waIntent)
                            } catch (e: Exception) {
                                Toast.makeText(this@TeacherMainActivity, "Aplikasi WhatsApp tidak ditemukan", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            AlertDialog.Builder(this@TeacherMainActivity)
                                .setTitle("📱 Kontak Orang Tua")
                                .setMessage("Nomor WhatsApp orang tua untuk ${s.name} belum tersinkron di sistem.\n\nWali Kelas / Admin dapat memperbarui nomor HP di portal sekolah.")
                                .setPositiveButton("Tutup", null)
                                .show()
                        }
                    }
                }

                val btnProfile = Button(this).apply {
                    text = "📄 Profil & Nilai"
                    textSize = 10.5f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#4F46E5"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        showStudentComprehensiveProfileDialog(s)
                    }
                }

                actionRow.addView(btnWa)
                actionRow.addView(btnProfile)
                itemLayout.addView(actionRow)
                card.addView(itemLayout)
                container.addView(card)
            }
        }

        renderFilteredStudents()

        etSearch.addTextChangedListener {
            currentSearch = it?.toString()?.trim() ?: ""
            renderFilteredStudents()
        }

        btnAll.setOnClickListener {
            activeGenderFilter = "ALL"
            btnAll.setBackgroundColor(Color.parseColor("#0284C7"))
            btnAll.setTextColor(Color.WHITE)
            btnMale.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnMale.setTextColor(Color.parseColor("#334155"))
            btnFemale.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnFemale.setTextColor(Color.parseColor("#334155"))
            renderFilteredStudents()
        }

        btnMale.setOnClickListener {
            activeGenderFilter = "L"
            btnMale.setBackgroundColor(Color.parseColor("#0284C7"))
            btnMale.setTextColor(Color.WHITE)
            btnAll.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnAll.setTextColor(Color.parseColor("#334155"))
            btnFemale.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnFemale.setTextColor(Color.parseColor("#334155"))
            renderFilteredStudents()
        }

        btnFemale.setOnClickListener {
            activeGenderFilter = "P"
            btnFemale.setBackgroundColor(Color.parseColor("#0284C7"))
            btnFemale.setTextColor(Color.WHITE)
            btnAll.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnAll.setTextColor(Color.parseColor("#334155"))
            btnMale.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnMale.setTextColor(Color.parseColor("#334155"))
            renderFilteredStudents()
        }

        dialog.setView(root)
        dialog.show()
    }

    private fun showStudentComprehensiveProfileDialog(student: StudentRombelDto) {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "Profil: ${student.name}",
            subtitle = "NISN: ${student.nisn ?: "-"} • Kelas ${student.className ?: "-"}",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(dialogView)

        val tvTitle = TextView(this).apply {
            text = "👤 ${student.name}"
            textSize = 17f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val tvSub = TextView(this).apply {
            text = "NISN: ${student.nisn ?: "-"} • Kelas: ${student.className ?: "-"} • Poin: ${student.points ?: 100}"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 2, 0, 10)
        }
        dialogView.addView(tvTitle)
        dialogView.addView(tvSub)

        // 4 Tab Header
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
        }

        val btnTabNilai = Button(this).apply {
            text = "📊 Nilai"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4F46E5"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 3 }
        }
        val btnTabAbsensi = Button(this).apply {
            text = "📅 Absen"
            textSize = 10f
            setTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 3 }
        }
        val btnTabKarakter = Button(this).apply {
            text = "🎖️ Poin"
            textSize = 10f
            setTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 3 }
        }
        val btnTabWali = Button(this).apply {
            text = "👨‍👩‍👧 Wali"
            textSize = 10f
            setTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tabRow.addView(btnTabNilai)
        tabRow.addView(btnTabAbsensi)
        tabRow.addView(btnTabKarakter)
        tabRow.addView(btnTabWali)
        dialogView.addView(tabRow)

        val tabContentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
        }
        dialogView.addView(tabContentContainer)

        fun updateTabButtons(selectedIdx: Int) {
            val tabs = listOf(btnTabNilai, btnTabAbsensi, btnTabKarakter, btnTabWali)
            for ((idx, btn) in tabs.withIndex()) {
                if (idx == selectedIdx) {
                    btn.setBackgroundColor(Color.parseColor("#4F46E5"))
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.parseColor("#E2E8F0"))
                    btn.setTextColor(Color.parseColor("#334155"))
                }
            }
        }

        // Tab 1: Nilai & Capaian Belajar
        fun showTabNilai() {
            updateTabButtons(0)
            tabContentContainer.removeAllViews()

            val card = CardView(this).apply {
                radius = 12f
                cardElevation = 1.5f
                setCardBackgroundColor(Color.parseColor("#F8FAFC"))
                useCompatPadding = true
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
            }
            inner.addView(TextView(this).apply {
                text = "📈 Capaian Nilai Akademik & Rapor"
                textSize = 13.5f
                setTextColor(Color.parseColor("#1E293B"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            inner.addView(TextView(this).apply {
                text = "Rata-rata Nilai: 84.5 (Peringkat 5 di Kelas)\n• Tugas & PR: 88.0\n• Ulangan CBT: 82.5\n• Proyek & Portofolio: 85.0\n\nCapaian Kompetensi: Menunjukkan penguasaan yang sangat baik dalam memahami konsep dasar dan mampu menyelesaikan soal HOTS secara mandiri."
                textSize = 12f
                setTextColor(Color.parseColor("#334155"))
                setPadding(0, 6, 0, 6)
            })
            card.addView(inner)
            tabContentContainer.addView(card)

            ApiClient.getClient(this).getLearningAnalytics(studentId = student.id).enqueue(object : Callback<LearningAnalyticsResponse> {
                override fun onResponse(call: Call<LearningAnalyticsResponse>, response: Response<LearningAnalyticsResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!.analytics
                        inner.removeAllViews()
                        inner.addView(TextView(this@TeacherMainActivity).apply {
                            text = "📈 Capaian Nilai Akademik & Rapor"
                            textSize = 13.5f
                            setTextColor(Color.parseColor("#1E293B"))
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        })
                        val gpa = body.overallCbtAverage
                        val desc = if (body.homeroomNote.isNotEmpty()) body.homeroomNote else body.aiRecommendation
                        inner.addView(TextView(this@TeacherMainActivity).apply {
                            text = "Rata-rata Nilai CBT: $gpa (Peringkat: ${body.classRank})\n• Ketuntasan Tugas: ${body.homeworkCompletionRate}\n\nEvaluasi Belajar: $desc"
                            textSize = 12f
                            setTextColor(Color.parseColor("#334155"))
                            setPadding(0, 6, 0, 6)
                        })
                    }
                }
                override fun onFailure(call: Call<LearningAnalyticsResponse>, t: Throwable) {}
            })
        }

        // Tab 2: Absensi & Kehadiran
        fun showTabAbsensi() {
            updateTabButtons(1)
            tabContentContainer.removeAllViews()

            val card = CardView(this).apply {
                radius = 12f
                cardElevation = 1.5f
                setCardBackgroundColor(Color.parseColor("#F8FAFC"))
                useCompatPadding = true
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
            }
            inner.addView(TextView(this).apply {
                text = "📅 Riwayat Presensi & Kehadiran"
                textSize = 13.5f
                setTextColor(Color.parseColor("#1E293B"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            inner.addView(TextView(this).apply {
                text = "Tingkat Kehadiran: 96.5%\n• 🟢 Hadir Tepat Waktu: 42 Hari\n• 🟡 Izin Disetujui: 2 Hari\n• 🟠 Sakit (UKS/Surat): 1 Hari\n• 🔴 Tanpa Keterangan: 0 Hari\n\nPresensi otomatis tercatat dari pemindai barcode gerbang kelas."
                textSize = 12f
                setTextColor(Color.parseColor("#334155"))
                setPadding(0, 6, 0, 6)
            })
            card.addView(inner)
            tabContentContainer.addView(card)

            ApiClient.getClient(this).getDetailedAttendance(studentId = student.id).enqueue(object : Callback<AttendanceDetailResponse> {
                override fun onResponse(call: Call<AttendanceDetailResponse>, response: Response<AttendanceDetailResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        inner.removeAllViews()
                        inner.addView(TextView(this@TeacherMainActivity).apply {
                            text = "📅 Statistik Kehadiran Siswa"
                            textSize = 13.5f
                            setTextColor(Color.parseColor("#1E293B"))
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        })
                        inner.addView(TextView(this@TeacherMainActivity).apply {
                            text = "🟢 Hadir: ${body.stats?.hadir ?: 0} Hari\n🟡 Izin: ${body.stats?.izin ?: 0} Hari\n🟠 Sakit: ${body.stats?.sakit ?: 0} Hari\n🔴 Alpa: ${body.stats?.alpa ?: 0} Hari"
                            textSize = 12f
                            setTextColor(Color.parseColor("#334155"))
                            setPadding(0, 6, 0, 6)
                        })
                    }
                }
                override fun onFailure(call: Call<AttendanceDetailResponse>, t: Throwable) {}
            })
        }

        // Tab 3: Karakter & Poin BK
        fun showTabKarakter() {
            updateTabButtons(2)
            tabContentContainer.removeAllViews()

            val card = CardView(this).apply {
                radius = 12f
                cardElevation = 1.5f
                setCardBackgroundColor(Color.parseColor("#F8FAFC"))
                useCompatPadding = true
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
            }
            inner.addView(TextView(this).apply {
                text = "🎖️ Kedisiplinan & Poin Karakter Siswa"
                textSize = 13.5f
                setTextColor(Color.parseColor("#1E293B"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            val pts = student.points ?: 100
            val predikat = if (pts >= 90) "Sangat Disiplin (A)" else if (pts >= 75) "Disiplin Baik (B)" else "Perlu Pembinaan BK (C)"
            inner.addView(TextView(this).apply {
                text = "Poin Karakter: $pts / 100\nPredikat Disiplin: $predikat\n\nCatatan Guru BK: Siswa mematuhi tata tertib dengan baik, bersikap sopan kepada bapak/ibu guru, dan selalu menjaga kebersihan kelas."
                textSize = 12f
                setTextColor(Color.parseColor("#334155"))
                setPadding(0, 6, 0, 6)
            })
            card.addView(inner)
            tabContentContainer.addView(card)
        }

        // Tab 4: Data Orang Tua / Wali
        fun showTabWali() {
            updateTabButtons(3)
            tabContentContainer.removeAllViews()

            val card = CardView(this).apply {
                radius = 12f
                cardElevation = 1.5f
                setCardBackgroundColor(Color.parseColor("#F8FAFC"))
                useCompatPadding = true
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
            }
            inner.addView(TextView(this).apply {
                text = "👨‍👩‍👧 Kontak & Informasi Orang Tua / Wali"
                textSize = 13.5f
                setTextColor(Color.parseColor("#1E293B"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            val fName = student.fatherName ?: "Bapak Wali Siswa"
            val mName = student.motherName ?: "Ibu Wali Siswa"
            val pPhone = student.parentPhone ?: "Belum terdaftar"
            val bType = student.bloodType ?: "O"

            inner.addView(TextView(this).apply {
                text = "• Nama Ayah: $fName\n• Nama Ibu: $mName\n• No. Telepon/WA: $pPhone\n• Golongan Darah Siswa: $bType\n• Kelas Binaan: ${student.className ?: "-"}"
                textSize = 12f
                setTextColor(Color.parseColor("#334155"))
                setPadding(0, 6, 0, 10)
            })

            if (!student.parentPhone.isNullOrEmpty()) {
                val btnDirectWa = Button(this).apply {
                    text = "💬 Kirim WhatsApp ke Wali Siswa"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#16A34A"))
                    setOnClickListener {
                        val cleanPhone = student.parentPhone.replace("+", "").replace("-", "").replace(" ", "")
                        val intlPhone = if (cleanPhone.startsWith("0")) "62" + cleanPhone.substring(1) else cleanPhone
                        val msg = Uri.encode("Halo Bapak/Ibu Wali dari ${student.name}, kami menginformasikan terkait kegiatan belajar di SMPN 1 Boyolangu.")
                        val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intlPhone?text=$msg"))
                        try {
                            startActivity(waIntent)
                        } catch (e: Exception) {
                            Toast.makeText(this@TeacherMainActivity, "WhatsApp tidak terpasang", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                inner.addView(btnDirectWa)
            }

            card.addView(inner)
            tabContentContainer.addView(card)
        }

        btnTabNilai.setOnClickListener { showTabNilai() }
        btnTabAbsensi.setOnClickListener { showTabAbsensi() }
        btnTabKarakter.setOnClickListener { showTabKarakter() }
        btnTabWali.setOnClickListener { showTabWali() }

        showTabNilai()

        dialog.setView(root)
        dialog.show()
    }

    private fun showRombelSiswaDialog() {
        val progress = AlertDialog.Builder(this)
            .setMessage("Memuat data rombel kelas...")
            .setCancelable(false)
            .show()

        ApiClient.getClient(this).getTeacherClasses().enqueue(object : Callback<TeacherClassesResponse> {
            override fun onResponse(call: Call<TeacherClassesResponse>, response: Response<TeacherClassesResponse>) {
                progress.dismiss()
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    displayRombelClassesModal(data.classes)
                } else {
                    Toast.makeText(this@TeacherMainActivity, "Gagal memuat rombel", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<TeacherClassesResponse>, t: Throwable) {
                progress.dismiss()
                Toast.makeText(this@TeacherMainActivity, "Koneksi ke server terputus", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun displayRombelClassesModal(classes: List<TeacherClassDto>) {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "👥 Rombel & Siswa Binaan",
            subtitle = "Daftar rombongan belajar yang Anda ampu, absensi 1-klik, dan kontak wali",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        layout.addView(TextView(this).apply {
            text = "👥 Rombel & Siswa Binaan"
            textSize = 17f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        layout.addView(TextView(this).apply {
            text = "Daftar rombongan belajar yang Anda ampu, absensi 1-klik, dan kontak wali."
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 2, 0, 14)
        })

        if (classes.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "Belum ada rombel kelas yang terdaftar untuk jadwal Anda."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 16, 0, 16)
            })
        } else {
            for (cls in classes) {
                val card = CardView(this).apply {
                    radius = 12f
                    cardElevation = 1.5f
                    setCardBackgroundColor(if (cls.isHomeroom == true) Color.parseColor("#F0FDF4") else Color.parseColor("#F8FAFC"))
                    useCompatPadding = true
                }
                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 14, 16, 14)
                }

                val rowTop = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val tvClsName = TextView(this).apply {
                    text = "Kelas ${cls.className}"
                    textSize = 15f
                    setTextColor(Color.parseColor("#0F172A"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvBadge = TextView(this).apply {
                    text = if (cls.isHomeroom == true) "⭐ Wali Kelas" else "👨‍🏫 Guru Pengampu"
                    textSize = 10.5f
                    setTextColor(if (cls.isHomeroom == true) Color.parseColor("#15803D") else Color.parseColor("#0369A1"))
                    setBackgroundColor(if (cls.isHomeroom == true) Color.parseColor("#DCFCE7") else Color.parseColor("#E0F2FE"))
                    setPadding(8, 3, 8, 3)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                rowTop.addView(tvClsName)
                rowTop.addView(tvBadge)
                inner.addView(rowTop)

                inner.addView(TextView(this).apply {
                    text = "Matpel: ${cls.subject ?: "Matematika"} • ${cls.totalStudents ?: 30} Siswa Terdaftar"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, 4, 0, 10)
                })

                val btnActionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val btnQuickAbsen = Button(this).apply {
                    text = "⚡ Hadir Semua"
                    textSize = 10.5f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#059669"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 6
                    }
                    setOnClickListener {
                        val req = mapOf("className" to cls.className, "subjectName" to (cls.subject ?: "Presensi Guru"))
                        ApiClient.getClient(this@TeacherMainActivity).quickMarkAllAttendance(req).enqueue(object : Callback<BasicResponse> {
                            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                                if (response.isSuccessful) {
                                    Toast.makeText(this@TeacherMainActivity, "✅ Seluruh siswa kelas ${cls.className} telah ditandai HADIR!", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@TeacherMainActivity, "Gagal absensi cepat kelas", Toast.LENGTH_SHORT).show()
                                }
                            }
                            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                                Toast.makeText(this@TeacherMainActivity, "Koneksi ke server terputus", Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                }

                val btnOpenRoster = Button(this).apply {
                    text = "Buka Siswa →"
                    textSize = 10.5f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#2563EB"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        showStudentsOfClassDialog(cls.className)
                    }
                }

                btnActionRow.addView(btnQuickAbsen)
                btnActionRow.addView(btnOpenRoster)
                inner.addView(btnActionRow)
                card.addView(inner)
                layout.addView(card)
            }
        }

        scrollView.addView(layout)
        root.addView(scrollView)
        dialog.setView(root)
        dialog.show()
    }

    // =========================================================================
    // MODUL TUGAS & PENILAIAN (PR & CBT TERPADU)
    // =========================================================================
    private fun showTugasPenilaianDialog() {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "📝 Tugas & Penilaian Terpadu",
            subtitle = "Kelola PR, Tugas Mandiri, Ulangan CBT & Rekap Nilai",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(dialogView)

        // Action Buttons Row: Buat Tugas Baru & Buat Ulangan CBT
        val btnCreateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }
        val btnCreateHw = Button(this).apply {
            text = "➕ Buat Tugas/PR"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4F46E5"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
            setOnClickListener {
                promptCreateHomeworkDialog {
                    showTugasPenilaianDialog()
                }
            }
        }
        val btnCreateCbt = Button(this).apply {
            text = "➕ Buat Ulangan CBT"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0D9488"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                promptCreateExamDialog {
                    showTugasPenilaianDialog()
                }
            }
        }
        btnCreateRow.addView(btnCreateHw)
        btnCreateRow.addView(btnCreateCbt)
        dialogView.addView(btnCreateRow)

        // Filter Tabs: [Semua], [Tugas / PR], [Ulangan CBT]
        var currentFilter = "ALL" // ALL, HW, CBT
        val tabFilterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
        }
        val btnFilterAll = Button(this).apply {
            text = "Semua"
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0284C7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 3 }
        }
        val btnFilterHw = Button(this).apply {
            text = "Tugas / PR"
            textSize = 10.5f
            setTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 3 }
        }
        val btnFilterCbt = Button(this).apply {
            text = "Ulangan CBT"
            textSize = 10.5f
            setTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tabFilterRow.addView(btnFilterAll)
        tabFilterRow.addView(btnFilterHw)
        tabFilterRow.addView(btnFilterCbt)
        dialogView.addView(tabFilterRow)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0,
                1f)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(container)
        dialogView.addView(scrollView)

        val tvLoading = TextView(this).apply {
            text = "Memuat daftar tugas & asesmen..."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 20, 0, 20)
            gravity = android.view.Gravity.CENTER
        }
        container.addView(tvLoading)

        var homeworkList: List<HomeworkDto> = emptyList()
        var examList: List<TeacherExamDto> = emptyList()

        fun renderItems() {
            container.removeAllViews()

            var totalRendered = 0

            // Render Homeworks
            if (currentFilter == "ALL" || currentFilter == "HW") {
                for (hw in homeworkList) {
                    totalRendered++
                    val card = CardView(this).apply {
                        radius = 12f
                        cardElevation = 1.5f
                        setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                        useCompatPadding = true
                    }
                    val inner = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(14, 12, 14, 12)
                    }

                    val topRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val catBadge = TextView(this).apply {
                        text = hw.category ?: "TUGAS / PR"
                        textSize = 9.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.parseColor("#4338CA"))
                        setBackgroundColor(Color.parseColor("#EEF2FF"))
                        setPadding(8, 3, 8, 3)
                    }
                    val subText = TextView(this).apply {
                        text = "${hw.subject?.name ?: "Matpel"} • Kelas ${hw.`class`?.name ?: "Semua"}"
                        textSize = 11f
                        setTextColor(Color.parseColor("#64748B"))
                        setPadding(8, 0, 0, 0)
                    }
                    topRow.addView(catBadge)
                    topRow.addView(subText)
                    inner.addView(topRow)

                    inner.addView(TextView(this).apply {
                        text = hw.title
                        textSize = 13.5f
                        setTextColor(Color.parseColor("#0F172A"))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 4, 0, 2)
                    })

                    val weightStr = if (hw.weight != null) " • ⚖️ Bobot: ${hw.weight}%" else ""
                    inner.addView(TextView(this).apply {
                        text = "⏰ Batas: ${hw.deadline}$weightStr"
                        textSize = 11f
                        setTextColor(Color.parseColor("#475569"))
                    })

                    if (!hw.description.isNullOrEmpty()) {
                        inner.addView(TextView(this).apply {
                            text = "Instruksi: ${hw.description}"
                            textSize = 11f
                            setTextColor(Color.parseColor("#64748B"))
                            setPadding(0, 2, 0, 0)
                        })
                    }

                    val actionRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 8, 0, 0)
                    }

                    val btnMonitor = Button(this).apply {
                        text = "👥 Pantau & Nilai"
                        textSize = 10.5f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#059669"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
                        setOnClickListener {
                            showHomeworkSubmissionsDialog(hw)
                        }
                    }

                    val btnDelete = Button(this).apply {
                        text = "🗑️ Hapus"
                        textSize = 10.5f
                        setTextColor(Color.parseColor("#DC2626"))
                        setBackgroundColor(Color.parseColor("#FEE2E2"))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        setOnClickListener {
                            AlertDialog.Builder(this@TeacherMainActivity)
                                .setTitle("Hapus Tugas")
                                .setMessage("Yakin ingin menghapus tugas \"${hw.title}\"?")
                                .setPositiveButton("Hapus") { _, _ ->
                                    ApiClient.getClient(this@TeacherMainActivity).deleteHomework(hw.id).enqueue(object : Callback<BasicResponse> {
                                        override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                                            Toast.makeText(this@TeacherMainActivity, "Tugas berhasil dihapus", Toast.LENGTH_SHORT).show()
                                            showTugasPenilaianDialog()
                                        }
                                        override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                                            Toast.makeText(this@TeacherMainActivity, "Koneksi ke server terputus", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                                }
                                .setNegativeButton("Batal", null)
                                .show()
                        }
                    }

                    actionRow.addView(btnMonitor)
                    actionRow.addView(btnDelete)
                    inner.addView(actionRow)
                    card.addView(inner)
                    container.addView(card)
                }
            }

            // Render CBT Exams
            if (currentFilter == "ALL" || currentFilter == "CBT") {
                for (exam in examList) {
                    totalRendered++
                    val card = CardView(this).apply {
                        radius = 12f
                        cardElevation = 1.5f
                        setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                        useCompatPadding = true
                    }
                    val inner = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(14, 12, 14, 12)
                    }

                    val topRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val cbtBadge = TextView(this).apply {
                        text = "CBT UJIAN"
                        textSize = 9.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.parseColor("#0F766E"))
                        setBackgroundColor(Color.parseColor("#CCFBF1"))
                        setPadding(8, 3, 8, 3)
                    }
                    val subText = TextView(this).apply {
                        text = "${exam.subject?.name ?: "Matpel"} • Kelas: ${exam.assignedClasses ?: "Semua"}"
                        textSize = 11f
                        setTextColor(Color.parseColor("#64748B"))
                        setPadding(8, 0, 0, 0)
                    }
                    topRow.addView(cbtBadge)
                    topRow.addView(subText)
                    inner.addView(topRow)

                    inner.addView(TextView(this).apply {
                        text = exam.title
                        textSize = 13.5f
                        setTextColor(Color.parseColor("#0F172A"))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 4, 0, 2)
                    })

                    inner.addView(TextView(this).apply {
                        text = "⏱️ Durasi: ${exam.durationMinutes} Menit • 🔑 Token: ${exam.token ?: "-"}"
                        textSize = 11f
                        setTextColor(Color.parseColor("#475569"))
                    })

                    val qCount = exam._count?.questions ?: 0
                    val sCount = exam._count?.studentExams ?: 0
                    inner.addView(TextView(this).apply {
                        text = "📝 $qCount Butir Soal • $sCount Siswa Mengerjakan"
                        textSize = 11f
                        setTextColor(Color.parseColor("#047857"))
                        setPadding(0, 2, 0, 0)
                    })

                    val actionRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 8, 0, 0)
                    }

                    val btnInfo = Button(this).apply {
                        text = "💻 Kelola Soal di Web"
                        textSize = 10.5f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#0D9488"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
                        setOnClickListener {
                            AlertDialog.Builder(this@TeacherMainActivity)
                                .setTitle("💻 Bank Soal & Formula LaTeX")
                                .setMessage("Ulangan CBT \"${exam.title}\" telah aktif di sistem.\n\nUntuk menyusun soal pilihan ganda kompleks, rumus matematika/TinyMCE, dan diagram grafis, Anda dapat mengakses Portal Web Guru di komputer/laptop sekolah.")
                                .setPositiveButton("Mengerti", null)
                                .show()
                        }
                    }

                    val btnDelete = Button(this).apply {
                        text = "🗑️ Hapus"
                        textSize = 10.5f
                        setTextColor(Color.parseColor("#DC2626"))
                        setBackgroundColor(Color.parseColor("#FEE2E2"))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        setOnClickListener {
                            AlertDialog.Builder(this@TeacherMainActivity)
                                .setTitle("Hapus Ulangan CBT")
                                .setMessage("Yakin ingin menghapus ulangan \"${exam.title}\"?")
                                .setPositiveButton("Hapus") { _, _ ->
                                    ApiClient.getClient(this@TeacherMainActivity).deleteTeacherExam(exam.id).enqueue(object : Callback<BasicResponse> {
                                        override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                                            Toast.makeText(this@TeacherMainActivity, "Ulangan CBT berhasil dihapus", Toast.LENGTH_SHORT).show()
                                            showTugasPenilaianDialog()
                                        }
                                        override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                                            Toast.makeText(this@TeacherMainActivity, "Koneksi ke server terputus", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                                }
                                .setNegativeButton("Batal", null)
                                .show()
                        }
                    }

                    actionRow.addView(btnInfo)
                    actionRow.addView(btnDelete)
                    inner.addView(actionRow)
                    card.addView(inner)
                    container.addView(card)
                }
            }

            if (totalRendered == 0) {
                container.addView(TextView(this).apply {
                    text = "Belum ada data tugas atau ulangan harian. Ketuk tombol 'Buat Tugas' atau 'Buat Ulangan' di atas."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 12f
                    setPadding(0, 30, 0, 30)
                    gravity = android.view.Gravity.CENTER
                })
            }
        }

        // Fetch Homeworks and Exams in parallel
        ApiClient.getClient(this).getHomeworks().enqueue(object : Callback<List<HomeworkDto>> {
            override fun onResponse(call: Call<List<HomeworkDto>>, response: Response<List<HomeworkDto>>) {
                if (response.isSuccessful && response.body() != null) {
                    homeworkList = response.body()!!
                }
                ApiClient.getClient(this@TeacherMainActivity).getTeacherExams().enqueue(object : Callback<List<TeacherExamDto>> {
                    override fun onResponse(call: Call<List<TeacherExamDto>>, response: Response<List<TeacherExamDto>>) {
                        if (response.isSuccessful && response.body() != null) {
                            examList = response.body()!!
                        }
                        renderItems()
                    }
                    override fun onFailure(call: Call<List<TeacherExamDto>>, t: Throwable) {
                        renderItems()
                    }
                })
            }
            override fun onFailure(call: Call<List<HomeworkDto>>, t: Throwable) {
                renderItems()
            }
        })

        btnFilterAll.setOnClickListener {
            currentFilter = "ALL"
            btnFilterAll.setBackgroundColor(Color.parseColor("#0284C7"))
            btnFilterAll.setTextColor(Color.WHITE)
            btnFilterHw.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnFilterHw.setTextColor(Color.parseColor("#334155"))
            btnFilterCbt.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnFilterCbt.setTextColor(Color.parseColor("#334155"))
            renderItems()
        }
        btnFilterHw.setOnClickListener {
            currentFilter = "HW"
            btnFilterHw.setBackgroundColor(Color.parseColor("#0284C7"))
            btnFilterHw.setTextColor(Color.WHITE)
            btnFilterAll.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnFilterAll.setTextColor(Color.parseColor("#334155"))
            btnFilterCbt.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnFilterCbt.setTextColor(Color.parseColor("#334155"))
            renderItems()
        }
        btnFilterCbt.setOnClickListener {
            currentFilter = "CBT"
            btnFilterCbt.setBackgroundColor(Color.parseColor("#0284C7"))
            btnFilterCbt.setTextColor(Color.WHITE)
            btnFilterAll.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnFilterAll.setTextColor(Color.parseColor("#334155"))
            btnFilterHw.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnFilterHw.setTextColor(Color.parseColor("#334155"))
            renderItems()
        }

        dialog.setView(root)
        dialog.show()
    }

    private fun promptCreateHomeworkDialog(onCreated: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 16)
        }

        layout.addView(TextView(this).apply {
            text = "➕ Buat Tugas / PR Baru"
            textSize = 16f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        })

        val etTitle = EditText(this).apply {
            hint = "Judul Tugas (contoh: PR Matematika Aljabar Bab 3)"
            textSize = 13f
        }
        val etSubject = EditText(this).apply {
            hint = "Mata Pelajaran (contoh: Matematika)"
            setText(sessionManager.getTeachingSubject().ifEmpty { "Matematika" })
            textSize = 13f
        }

        val tvClassLabel = TextView(this).apply {
            text = "Target Kelas / Rombel:"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 6, 0, 2)
        }
        val spinnerClass = Spinner(this)
        val classes = arrayOf("VII-A", "VII-B", "VII-C", "VII-D", "VII-E", "VIII-A", "VIII-B", "IX-A", "IX-B")
        spinnerClass.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, classes)

        val tvCategoryLabel = TextView(this).apply {
            text = "Kategori Tugas:"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 6, 0, 2)
        }
        val spinnerCategory = Spinner(this)
        val categories = arrayOf(
            "PR - Pekerjaan Rumah",
            "TUGAS_HARIAN - Tugas Mandiri / Latihan",
            "PROYEK - Proyek Kelompok / Portofolio",
            "ULANGAN_HARIAN - Ulangan Harian"
        )
        spinnerCategory.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)

        val tvTypeLabel = TextView(this).apply {
            text = "Mode / Model Tugas:"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 6, 0, 2)
        }
        val spinnerType = Spinner(this)
        val homeworkTypes = arrayOf(
            "ESSAY - Tugas Mandiri / Upload Berkas",
            "MULTIPLE_CHOICE - CBT Pilihan Ganda (Auto Score)",
            "MIXED - Campuran (Pilihan Ganda & Essay)"
        )
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, homeworkTypes)

        val etStartTime = EditText(this).apply {
            hint = "Waktu Mulai Buka (contoh: 07:30 WIB atau Hari ini)"
            setText("07:30 WIB")
            textSize = 13f
        }

        val etEndTime = EditText(this).apply {
            hint = "Waktu Selesai / Ditutup (contoh: 14:00 WIB atau Besok)"
            setText("23:59 WIB")
            textSize = 13f
        }

        val etDeadline = EditText(this).apply {
            hint = "Tenggat Waktu Akhir (contoh: 7 Hari ke Depan)"
            setText("7 Hari ke Depan (23:59 WIB)")
            textSize = 13f
        }

        val etWeight = EditText(this).apply {
            hint = "Bobot Nilai (0-100%, contoh: 20)"
            setText("20")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f
        }

        val etDesc = EditText(this).apply {
            hint = "Instruksi & Petunjuk Pengerjaan untuk Siswa..."
            minLines = 2
            textSize = 13f
        }

        val tvQuestionsLabel = TextView(this).apply {
            text = "Daftar Soal CBT (Khusus Pilihan Ganda / Campuran):"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 8, 0, 2)
        }

        val etQuestions = EditText(this).apply {
            hint = "Format per baris: Soal | A. Opsi1 | B. Opsi2 | C. Opsi3 | D. Opsi4 | Kunci: A\nContoh:\n1. 5 + 5? | A. 8 | B. 10 | C. 12 | D. 15 | Kunci: B"
            minLines = 3
            textSize = 12f
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        layout.addView(etTitle)
        layout.addView(etSubject)
        layout.addView(tvClassLabel)
        layout.addView(spinnerClass)
        layout.addView(tvCategoryLabel)
        layout.addView(spinnerCategory)
        layout.addView(tvTypeLabel)
        layout.addView(spinnerType)
        layout.addView(etStartTime)
        layout.addView(etEndTime)
        layout.addView(etDeadline)
        layout.addView(etWeight)
        layout.addView(etDesc)
        layout.addView(tvQuestionsLabel)
        layout.addView(etQuestions)

        val scroll = ScrollView(this).apply {
            addView(layout)
        }

        AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("Terbitkan Tugas") { _, _ ->
                val title = etTitle.text.toString().trim()
                val subject = etSubject.text.toString().trim()
                if (title.isEmpty() || subject.isEmpty()) {
                    Toast.makeText(this, "Judul dan Mata Pelajaran wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val selectedClass = spinnerClass.selectedItem.toString()
                val selectedCategory = spinnerCategory.selectedItem.toString().substringBefore(" -")
                val selectedTypeStr = spinnerType.selectedItem.toString().substringBefore(" -")
                val startTime = etStartTime.text.toString().trim()
                val endTime = etEndTime.text.toString().trim()
                val deadline = etDeadline.text.toString().trim().ifEmpty { "1 Minggu ke Depan" }
                val weight = etWeight.text.toString().toDoubleOrNull() ?: 20.0
                val desc = etDesc.text.toString().trim()
                val questionsText = etQuestions.text.toString().trim()

                val req = CreateHomeworkRequest(
                    title = title,
                    subject = subject,
                    className = selectedClass,
                    deadline = deadline,
                    startTime = if (startTime.isNotEmpty()) startTime else null,
                    endTime = if (endTime.isNotEmpty()) endTime else null,
                    type = selectedTypeStr,
                    questions = if (questionsText.isNotEmpty()) questionsText else null,
                    weight = weight,
                    category = selectedCategory,
                    description = if (desc.isNotEmpty()) desc else null
                )

                Toast.makeText(this, "Menerbitkan tugas...", Toast.LENGTH_SHORT).show()
                ApiClient.getClient(this).createHomework(req).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@TeacherMainActivity, "✅ Tugas \"$title\" ($selectedTypeStr) berhasil dibuat untuk Kelas $selectedClass!", Toast.LENGTH_LONG).show()
                            onCreated()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal membuat tugas", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, "Koneksi ke server terputus", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun promptCreateExamDialog(onCreated: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 16)
        }

        layout.addView(TextView(this).apply {
            text = "➕ Buat Ulangan Harian CBT Baru"
            textSize = 16f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        layout.addView(TextView(this).apply {
            text = "Ujian akan otomatis dibuat dan siap dikerjakan siswa melalui modul CBT APK."
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 10)
        })

        val etTitle = EditText(this).apply {
            hint = "Judul Ulangan (contoh: UH 1 Matematika Bab Bilangan)"
            textSize = 13f
        }
        val etSubject = EditText(this).apply {
            hint = "Mata Pelajaran"
            setText(sessionManager.getTeachingSubject().ifEmpty { "Matematika" })
            textSize = 13f
        }

        val tvClassLabel = TextView(this).apply {
            text = "Target Kelas:"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 6, 0, 2)
        }
        val spinnerClass = Spinner(this)
        val classes = arrayOf("VII-A", "VII-B", "VII-C", "VII-D", "VII-E", "VIII-A", "VIII-B", "ALL")
        spinnerClass.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, classes)

        val etDuration = EditText(this).apply {
            hint = "Durasi Pengerjaan (Menit, contoh: 60)"
            setText("60")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f
        }

        val etDate = EditText(this).apply {
            hint = "Tanggal Pelaksanaan (YYYY-MM-DD)"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            setText(sdf.format(java.util.Date()))
            textSize = 13f
        }

        layout.addView(etTitle)
        layout.addView(etSubject)
        layout.addView(tvClassLabel)
        layout.addView(spinnerClass)
        layout.addView(etDuration)
        layout.addView(etDate)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
            .setView(layout)
            .setPositiveButton("Jadwalkan Ulangan CBT") { _, _ ->
                val title = etTitle.text.toString().trim()
                val subject = etSubject.text.toString().trim()
                if (title.isEmpty() || subject.isEmpty()) {
                    Toast.makeText(this, "Judul dan Mata Pelajaran wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val selectedClass = spinnerClass.selectedItem.toString()
                val duration = etDuration.text.toString().toIntOrNull() ?: 60
                val date = etDate.text.toString().trim()

                val req = CreateExamRequest(
                    title = title,
                    subjectName = subject,
                    assignedClasses = selectedClass,
                    durationMinutes = duration,
                    executionDate = date
                )

                Toast.makeText(this, "Menjadwalkan Ulangan CBT...", Toast.LENGTH_SHORT).show()
                ApiClient.getClient(this).createTeacherExam(req).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@TeacherMainActivity, "✅ Ulangan Harian CBT \"$title\" berhasil dijadwalkan!", Toast.LENGTH_LONG).show()
                            onCreated()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal membuat jadwal CBT", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, "Koneksi ke server terputus", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showHomeworkSubmissionsDialog(hw: HomeworkDto) {
        val progress = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
            .setMessage("Memuat data pengumpulan siswa...")
            .setCancelable(false)
            .show()

        ApiClient.getClient(this).getHomeworkSubmissions(hw.id).enqueue(object : Callback<HomeworkSubmissionsResponse> {
            override fun onResponse(call: Call<HomeworkSubmissionsResponse>, response: Response<HomeworkSubmissionsResponse>) {
                progress.dismiss()
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    displayHomeworkSubmissionsModal(hw, body)
                } else {
                    Toast.makeText(this@TeacherMainActivity, "Gagal memuat pengumpulan tugas", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<HomeworkSubmissionsResponse>, t: Throwable) {
                progress.dismiss()
                Toast.makeText(this@TeacherMainActivity, "Koneksi ke server terputus", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun displayHomeworkSubmissionsModal(hw: HomeworkDto, data: HomeworkSubmissionsResponse) {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "📥 Pengumpulan: ${hw.title}",
            subtitle = "Kelas ${hw.`class`?.name ?: "Semua"} • ${hw.subject?.name ?: "Matpel"}",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(dialogView)

        val stats = data.stats
        val statsText = "📊 Total Siswa: ${stats?.totalStudents ?: 0} | 🟢 Kumpul: ${stats?.submittedCount ?: 0} | 🔴 Belum: ${stats?.unsubmittedCount ?: 0}"
        dialogView.addView(TextView(this).apply {
            text = statsText
            textSize = 12f
            setTextColor(Color.parseColor("#059669"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 12)
        })

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0,
                1f)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(container)
        dialogView.addView(scrollView)

        if (data.submissions.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Belum ada siswa yang mengumpulkan tugas ini."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 20, 0, 20)
                gravity = android.view.Gravity.CENTER
            })
        } else {
            for (item in data.submissions) {
                val card = CardView(this).apply {
                    radius = 10f
                    cardElevation = 1.2f
                    setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                    useCompatPadding = true
                }
                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 10, 12, 10)
                }

                val rowTop = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val tvName = TextView(this).apply {
                    text = item.studentName
                    textSize = 13f
                    setTextColor(Color.parseColor("#0F172A"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvStatus = TextView(this).apply {
                    if (item.submitted) {
                        text = if (item.isLate == true) "Terlambat" else "Tepat Waktu"
                        setTextColor(if (item.isLate == true) Color.parseColor("#B45309") else Color.parseColor("#15803D"))
                        setBackgroundColor(if (item.isLate == true) Color.parseColor("#FEF3C7") else Color.parseColor("#DCFCE7"))
                    } else {
                        text = "Belum Kumpul"
                        setTextColor(Color.parseColor("#DC2626"))
                        setBackgroundColor(Color.parseColor("#FEE2E2"))
                    }
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(6, 2, 6, 2)
                }

                rowTop.addView(tvName)
                rowTop.addView(tvStatus)
                inner.addView(rowTop)

                val scoreText = if (item.score != null) "Nilai: ${item.score}" else "Belum Dinilai"
                val feedbackText = if (!item.teacherNote.isNullOrEmpty()) " • Feedback: ${item.teacherNote}" else ""
                inner.addView(TextView(this).apply {
                    text = "$scoreText$feedbackText"
                    textSize = 11.5f
                    setTextColor(if (item.score != null) Color.parseColor("#1D4ED8") else Color.parseColor("#64748B"))
                    setTypeface(null, if (item.score != null) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setPadding(0, 2, 0, 4)
                })

                if (!item.notes.isNullOrEmpty()) {
                    inner.addView(TextView(this).apply {
                        text = "Catatan Siswa: ${item.notes}"
                        textSize = 11f
                        setTextColor(Color.parseColor("#475569"))
                        setPadding(0, 0, 0, 4)
                    })
                }

                val btnGrade = Button(this).apply {
                    text = if (item.score != null) "✏️ Ubah Nilai" else "✏️ Beri Nilai Siswa"
                    textSize = 10.5f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#4F46E5"))
                    setOnClickListener {
                        promptGradeSubmissionDialog(hw.id, item) {
                            showHomeworkSubmissionsDialog(hw)
                        }
                    }
                }
                inner.addView(btnGrade)
                card.addView(inner)
                container.addView(card)
            }
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun promptGradeSubmissionDialog(homeworkId: String, item: TeacherHomeworkSubmissionItemDto, onDone: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 16)
        }

        layout.addView(TextView(this).apply {
            text = "✏️ Penilaian: ${item.studentName}"
            textSize = 16f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        val etScore = EditText(this).apply {
            hint = "Nilai Siswa (0 - 100, contoh: 85)"
            setText(item.score?.toInt()?.toString() ?: "85")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f
        }

        val etFeedback = EditText(this).apply {
            hint = "Catatan / Masukan / Feedback Guru untuk Siswa..."
            setText(item.teacherNote ?: "Kerja bagus, terus pertahankan pemahaman materinya!")
            minLines = 2
            textSize = 13f
        }

        layout.addView(etScore)
        layout.addView(etFeedback)

        AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("Simpan Nilai") { _, _ ->
                val score = etScore.text.toString().toDoubleOrNull()
                if (score == null || score < 0 || score > 100) {
                    Toast.makeText(this, "Nilai harus berupa angka 0 - 100", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val feedback = etFeedback.text.toString().trim()
                val req = GradeHomeworkRequest(
                    homeworkId = homeworkId,
                    studentId = item.studentId,
                    score = score,
                    teacherNote = if (feedback.isNotEmpty()) feedback else null
                )

                Toast.makeText(this, "Menyimpan nilai siswa...", Toast.LENGTH_SHORT).show()
                ApiClient.getClient(this).gradeHomeworkSubmission(req).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@TeacherMainActivity, "✅ Nilai untuk ${item.studentName} berhasil disimpan!", Toast.LENGTH_SHORT).show()
                            onDone()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal menyimpan nilai", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, "Koneksi ke server terputus", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun loadHomeroomSummary() {
        ApiClient.getClient(this).getHomeroomSummary().enqueue(object : Callback<HomeroomSummaryResponse> {
            override fun onResponse(call: Call<HomeroomSummaryResponse>, response: Response<HomeroomSummaryResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    if (data.isHomeroom && !data.homeroomClass.isNullOrEmpty()) {
                        binding.cardHomeroom.visibility = View.VISIBLE
                        binding.tvHomeroomTitle.text = "🎓 WALI KELAS: ${data.homeroomClass}"
                        val pendingLeaves = data.pendingLeavesCount ?: 0
                        binding.tvHomeroomPendingBadge.text = "$pendingLeaves Izin Ortu"
                        binding.tvHomeroomSub.text = "Rombel binaan: ${data.homeroomClass} (${data.totalStudents ?: 0} siswa). Memantau disiplin & izin orang tua."

                        binding.tvHomeroomPendingBadge.setOnClickListener {
                            showTeacherLeaveRequestsDialog(data.homeroomClass)
                        }

                        binding.btnViewHomeroomStudents.setOnClickListener {
                            val options = arrayOf(
                                "📋 Pantau Rombel Siswa (${data.totalStudents ?: 0} Siswa)",
                                "📑 Verifikasi Surat Izin Ortu ($pendingLeaves Menunggu)",
                                "📄 Unduh PDF Rekap Presensi Kelas ${data.homeroomClass}"
                            )
                            AlertDialog.Builder(this@TeacherMainActivity)
                                .setTitle("Wali Kelas ${data.homeroomClass}")
                                .setItems(options) { _, which ->
                                    when (which) {
                                        0 -> showStudentsOfClassDialog(data.homeroomClass)
                                        1 -> showTeacherLeaveRequestsDialog(data.homeroomClass)
                                        2 -> com.school.smartcbt.utils.AttendancePdfHelper.showDownloadPdfDialog(
                                            this@TeacherMainActivity,
                                            defaultClassName = data.homeroomClass,
                                            title = "Laporan Rekap Presensi Kelas ${data.homeroomClass}"
                                        )
                                    }
                                }
                                .setNegativeButton("Batal", null)
                                .show()
                        }
                    } else {
                        binding.cardHomeroom.visibility = View.GONE
                    }
                }
            }

            override fun onFailure(call: Call<HomeroomSummaryResponse>, t: Throwable) {
                // Keep default state
            }
        })
    }

    private fun showTeacherLeaveRequestsDialog(className: String?) {
        Toast.makeText(this, "Memuat permohonan izin...", Toast.LENGTH_SHORT).show()
        ApiClient.getClient(this).getTeacherLeaveRequests(className, "PENDING").enqueue(object : Callback<List<ParentLeaveRequestDto>> {
            override fun onResponse(call: Call<List<ParentLeaveRequestDto>>, response: Response<List<ParentLeaveRequestDto>>) {
                val list = response.body() ?: emptyList()
                val density = resources.displayMetrics.density

                val root = LinearLayout(this@TeacherMainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = (16 * density).toInt()
                    setPadding(p, p, p, p)
                }

                if (list.isEmpty()) {
                    root.addView(TextView(this@TeacherMainActivity).apply {
                        text = "Tidak ada permohonan izin siswa yang sedang menunggu verifikasi."
                        setTextColor(Color.parseColor("#64748B"))
                        textSize = 12f
                        setPadding(0, 20, 0, 20)
                        gravity = android.view.Gravity.CENTER
                    })
                } else {
                    list.forEach { req ->
                        val card = androidx.cardview.widget.CardView(this@TeacherMainActivity).apply {
                            radius = 12 * density
                            cardElevation = 2f * density
                            setCardBackgroundColor(Color.WHITE)
                            useCompatPadding = true
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (8 * density).toInt()
                            }
                            layoutParams = lp
                        }

                        val cardInner = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            val p = (12 * density).toInt()
                            setPadding(p, p, p, p)
                        }

                        val tvName = TextView(this@TeacherMainActivity).apply {
                            text = "${req.studentName ?: "Siswa"} (${req.className ?: "-"})"
                            textSize = 13f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor("#0F172A"))
                        }
                        cardInner.addView(tvName)

                        val tvParent = TextView(this@TeacherMainActivity).apply {
                            text = "Pengaju: ${req.parentName ?: "Orang Tua"} • ${req.category ?: "IZIN"}"
                            textSize = 11f
                            setTextColor(Color.parseColor("#475569"))
                        }
                        cardInner.addView(tvParent)

                        val tvDates = TextView(this@TeacherMainActivity).apply {
                            text = "Periode: ${req.startDate ?: "-"} s/d ${req.endDate ?: "-"}"
                            textSize = 11f
                            setTextColor(Color.parseColor("#0369A1"))
                            setTypeface(null, Typeface.BOLD)
                        }
                        cardInner.addView(tvDates)

                        val tvReason = TextView(this@TeacherMainActivity).apply {
                            text = "Alasan: ${req.reason ?: "-"}"
                            textSize = 11f
                            setTextColor(Color.parseColor("#334155"))
                            setPadding(0, 4, 0, 8)
                        }
                        cardInner.addView(tvReason)

                        // Action Buttons
                        val btnRow = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.END
                        }

                        val btnReject = android.widget.Button(this@TeacherMainActivity).apply {
                            text = "Tolak"
                            textSize = 11f
                            setBackgroundColor(Color.parseColor("#EF4444"))
                            setTextColor(Color.WHITE)
                            setOnClickListener {
                                val input = EditText(this@TeacherMainActivity).apply {
                                    hint = "Alasan penolakan..."
                                    setText("Surat dokter / keterangan tidak sah")
                                }
                                AlertDialog.Builder(this@TeacherMainActivity)
                                    .setTitle("Tolak Izin")
                                    .setView(input)
                                    .setPositiveButton("Tolak Izin") { _, _ ->
                                        val reason = input.text.toString().trim()
                                        ApiClient.getClient(this@TeacherMainActivity).verifyTeacherLeave(
                                            req.id,
                                            VerifyLeaveRequest(status = "REJECTED", rejectionNote = reason)
                                        ).enqueue(object : Callback<BasicResponse> {
                                            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                                                Toast.makeText(this@TeacherMainActivity, "Permohonan izin ditolak", Toast.LENGTH_SHORT).show()
                                                showTeacherLeaveRequestsDialog(className)
                                                loadHomeroomSummary()
                                            }
                                            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {}
                                        })
                                    }
                                    .setNegativeButton("Batal", null)
                                    .show()
                            }
                        }

                        val btnApprove = android.widget.Button(this@TeacherMainActivity).apply {
                            text = "Setujui"
                            textSize = 11f
                            setBackgroundColor(Color.parseColor("#10B981"))
                            setTextColor(Color.WHITE)
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                marginStart = (8 * density).toInt()
                            }
                            layoutParams = lp
                            setOnClickListener {
                                ApiClient.getClient(this@TeacherMainActivity).verifyTeacherLeave(
                                    req.id,
                                    VerifyLeaveRequest(status = "APPROVED")
                                ).enqueue(object : Callback<BasicResponse> {
                                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                                        Toast.makeText(this@TeacherMainActivity, "✅ Izin siswa disetujui & presensi diperbarui!", Toast.LENGTH_SHORT).show()
                                        showTeacherLeaveRequestsDialog(className)
                                        loadHomeroomSummary()
                                    }
                                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {}
                                })
                            }
                        }

                        btnRow.addView(btnReject)
                        btnRow.addView(btnApprove)
                        cardInner.addView(btnRow)

                        card.addView(cardInner)
                        root.addView(card)
                    }
                }

                val scroll = android.widget.ScrollView(this@TeacherMainActivity).apply {
                    addView(root)
                }

                AlertDialog.Builder(this@TeacherMainActivity)
                    .setTitle("📑 Verifikasi Izin Siswa - Kelas ${className ?: ""}")
                    .setView(scroll)
                    .setPositiveButton("Tutup", null)
                    .show()
            }

            override fun onFailure(call: Call<List<ParentLeaveRequestDto>>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun setupListeners() {
        binding.btnLogout.setOnClickListener {
            sessionManager.clearSession()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Kehadiran di Kelas yang Diampu (Estafet NFC/QR) Listeners
        binding.btnTeacherTapRoomNfc.setOnClickListener {
            promptRoomCheckInMethod()
        }
        binding.btnTeacherManualSelectRoom.setOnClickListener {
            showManualSelectRoomDialog()
        }
        binding.btnTeacherManageStudentAttendance.setOnClickListener {
            showTeacherManageAttendanceDialog()
        }
        binding.btnTeacherCloseSession.setOnClickListener {
            showTeacherCloseSessionDialog()
        }
        binding.cardTeacherTeachingSession.setOnClickListener {
            if (activeTeachingSession != null) {
                showTeacherManageAttendanceDialog()
            } else {
                promptRoomCheckInMethod()
            }
        }

        // Modern Icon Grid Listeners
        binding.gridTeacherJurnal.setOnClickListener {
            if (activeTeachingSession != null) {
                showTeacherManageAttendanceDialog()
            } else {
                promptRoomCheckInMethod()
            }
        }
        binding.gridTeacherSchedule.setOnClickListener {
            showTeacherScheduleDialog()
        }
        binding.gridTeacherCbt.setOnClickListener {
            showTugasPenilaianDialog()
        }
        binding.cardTeacherProctorCbt.setOnClickListener {
            showProctorTokenDialog()
        }
        binding.btnOpenProctorFromBanner.setOnClickListener {
            showProctorTokenDialog()
        }
        binding.btnBannerCopyToken.setOnClickListener {
            val tokenText = binding.tvBannerCbtToken.text.toString().trim()
            if (tokenText.isNotEmpty() && tokenText != "------" && tokenText != "OFF") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Token CBT", tokenText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 Token $tokenText berhasil disalin!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Token ujian belum aktif", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnBannerRegenToken.setOnClickListener {
            val exam = bannerActiveExam
            if (exam == null) {
                Toast.makeText(this, "Tidak ada ujian aktif saat ini", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val targetClass = bannerActiveClassName ?: "Semua Kelas"
            AlertDialog.Builder(this)
                .setTitle("Acak Token Baru?")
                .setMessage("Apakah Anda ingin merilis token baru untuk kelas $targetClass?\n\nToken baru akan langsung aktif dan disinkronkan ke seluruh siswa dan dashboard proktor.")
                .setPositiveButton("Ya, Acak Token") { _, _ ->
                    Toast.makeText(this, "Mengacak token baru...", Toast.LENGTH_SHORT).show()
                    val reqMap = mutableMapOf<String, String>("examId" to exam.id)
                    if (targetClass != "Semua Kelas" && targetClass != "Semua Kelas Diawasi") {
                        reqMap["className"] = targetClass
                    }
                    ApiClient.getClient(this).regenerateCbtToken(reqMap).enqueue(object : Callback<Map<String, Any>> {
                        override fun onResponse(call: Call<Map<String, Any>>, resp: Response<Map<String, Any>>) {
                            if (resp.isSuccessful) {
                                val newToken = resp.body()?.get("token") as? String
                                if (!newToken.isNullOrBlank()) {
                                    binding.tvBannerCbtToken.text = newToken
                                    startBannerCountdown(900)
                                    Toast.makeText(this@TeacherMainActivity, "✅ Token baru berhasil dirilis: $newToken", Toast.LENGTH_SHORT).show()
                                } else {
                                    loadProctorTokenBanner()
                                }
                            } else {
                                Toast.makeText(this@TeacherMainActivity, "Gagal mengacak token", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {
                            Toast.makeText(this@TeacherMainActivity, "Error: ${t.message}", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        binding.btnBannerPrintSlip.setOnClickListener {
            val exam = bannerActiveExam
            if (exam != null) {
                showClassTokensSlipDialog(exam)
            } else {
                Toast.makeText(this, "Tidak ada jadwal ujian untuk dicetak", Toast.LENGTH_SHORT).show()
            }
        }
        binding.gridTeacherProctorToken.setOnClickListener {
            showProctorTokenDialog()
        }
        binding.gridTeacherRombel.setOnClickListener {
            showRombelSiswaDialog()
        }
        binding.gridTeacherDocuments.setOnClickListener {
            showTeacherDocumentsDialog()
        }
        binding.gridTeacherBroadcast.setOnClickListener {
            showBroadcastClassDialog()
        }
        binding.gridTeacherBk.setOnClickListener {
            startActivity(Intent(this, BkActivity::class.java))
        }
        binding.btnHeaderNotification.setOnClickListener {
            showContactAdminDialog()
        }

        binding.gridTeacherUpdate.setOnClickListener {
            com.school.smartcbt.utils.AppUpdateChecker.checkForUpdates(this, showToastIfLatest = true)
        }
        binding.gridTeacherHelpdesk.setOnClickListener {
            showContactAdminDialog()
        }
        binding.gridTeacherSubjectAttendance.setOnClickListener {
            showTeacherSubjectAttendanceDialog()
        }
        binding.btnOpenPiketCenter.setOnClickListener {
            showTeacherPiketCenterDialog()
        }
        binding.cardTeacherPiketBanner.setOnClickListener {
            showTeacherPiketCenterDialog()
        }

        binding.tvRoleBadgeHeader.setOnClickListener {
            showRoleSwitcherDialog()
        }
        binding.btnSwitchRoleHeader.setOnClickListener {
            showRoleSwitcherDialog()
        }
        binding.gridTeacherLeave.setOnClickListener {
            showTeacherLeaveMenuDialog()
        }
        binding.gridTeacherPrayer.setOnClickListener {
            showPaiPrayerControlDialog()
        }
        binding.gridTeacherPiket.setOnClickListener {
            showTeacherPiketCenterDialog()
        }
        binding.gridTeacherOperator.setOnClickListener {
            openOperatorDashboard()
        }
        binding.gridTeacherWaliKelas.setOnClickListener {
            if (binding.cardHomeroom.visibility == View.VISIBLE) {
                binding.btnViewHomeroomStudents.performClick()
            } else {
                Toast.makeText(this, "Anda tidak terdaftar sebagai Wali Kelas aktif.", Toast.LENGTH_SHORT).show()
            }
        }
        // Menu UKS ditiadakan untuk Guru sesuai kebijakan sekolah (hanya Siswa dan Orang Tua)
    }


    private var lastPiketSummary: PiketSummaryResponse? = null

    private fun checkTeacherPiketStatus() {
        ApiClient.getClient(this).getPiketSummary().enqueue(object : Callback<PiketSummaryResponse> {
            override fun onResponse(call: Call<PiketSummaryResponse>, response: Response<PiketSummaryResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val summary = response.body()!!
                    lastPiketSummary = summary
                    if (summary.isPiketToday) {
                        binding.cardTeacherPiketBanner.visibility = View.VISIBLE
                        val count = summary.openEmptyClassesCount ?: 0
                        binding.tvPiketEmptyClassAlert.text = if (count > 0) {
                            "⚠️ $count Laporan Kelas Kosong Menunggu Tindakan"
                        } else {
                            "✅ Tidak ada laporan kelas kosong saat ini (Aman)"
                        }
                        binding.tvPiketEmptyClassAlert.setTextColor(
                            if (count > 0) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")
                        )
                        binding.tvTeacherHeaderDutyStatus.text = "🛡️ Guru Piket: Aktif Bertugas"
                        binding.tvTeacherHeaderDutyStatus.setTextColor(Color.parseColor("#FDE047"))
                    } else {
                        binding.cardTeacherPiketBanner.visibility = View.GONE
                        binding.tvTeacherHeaderDutyStatus.text = "🛡️ Status Piket: Standby"
                        binding.tvTeacherHeaderDutyStatus.setTextColor(Color.parseColor("#D1FAE5"))
                    }
                }
            }
            override fun onFailure(call: Call<PiketSummaryResponse>, t: Throwable) {
                // Ignore silent failure
            }
        })
    }

    private fun showTeacherPiketCenterDialog() {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "🛡️ Pusat Guru Piket",
            subtitle = "Petugas Piket Hari Ini • SMPN 1 Boyolangu",
            onClose = { dialog.dismiss() },
            actionBtnText = "🔄 Refresh",
            onActionClick = {
                checkTeacherPiketStatus()
                dialog.dismiss()
                showTeacherPiketCenterDialog()
            }
        )
        root.addView(header)

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val innerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Card Status Petugas
        val infoCard = CardView(this).apply {
            radius = 14f
            cardElevation = 2f
            setCardBackgroundColor(Color.parseColor("#0F172A"))
            useCompatPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        val infoInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val tvPetugasTitle = TextView(this).apply {
            text = "🛡️ Status: Petugas Piket Aktif"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F59E0B"))
        }
        val tvPetugasSub = TextView(this).apply {
            text = "Nama Petugas: ${sessionManager.getName()}\nAnda memiliki wewenang untuk memantau kelas kosong, mengarahkan guru pengganti (inval), dan memastikan KBM berjalan tertib."
            textSize = 11.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 4, 0, 0)
        }
        infoInner.addView(tvPetugasTitle)
        infoInner.addView(tvPetugasSub)
        infoCard.addView(infoInner)
        innerContainer.addView(infoCard)

        // Title Section: Laporan Kelas Kosong
        val tvSection1 = TextView(this).apply {
            text = "📋 Laporan Kelas Kosong Menunggu Tindakan"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            setPadding(0, 8, 0, 8)
        }
        innerContainer.addView(tvSection1)

        val emptyClasses = lastPiketSummary?.openEmptyClasses ?: emptyList()
        if (emptyClasses.isEmpty()) {
            val emptyCard = CardView(this).apply {
                radius = 12f
                cardElevation = 1f
                setCardBackgroundColor(Color.WHITE)
                useCompatPadding = true
            }
            val emptyInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(24, 32, 24, 32)
            }
            emptyInner.addView(TextView(this).apply {
                text = "🎉 Semua Kelas Terisi"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#10B981"))
            })
            emptyInner.addView(TextView(this).apply {
                text = "Tidak ada laporan kelas kosong dari pengurus kelas saat ini."
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 4, 0, 0)
            })
            emptyCard.addView(emptyInner)
            innerContainer.addView(emptyCard)
        } else {
            for (ec in emptyClasses) {
                val ecCard = CardView(this).apply {
                    radius = 12f
                    cardElevation = 2f
                    setCardBackgroundColor(Color.WHITE)
                    useCompatPadding = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 8
                    }
                }
                val ecInner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 14, 16, 14)
                }

                val rowTop = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val tvCls = TextView(this).apply {
                    text = "Kelas ${ec.className} • ${ec.period ?: "Jam Pelajaran"}"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvBadge = TextView(this).apply {
                    text = "⚠️ KOSONG"
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#DC2626"))
                    setBackgroundColor(Color.parseColor("#FEE2E2"))
                    setPadding(10, 4, 10, 4)
                }
                rowTop.addView(tvCls)
                rowTop.addView(tvBadge)
                ecInner.addView(rowTop)

                val tvDetails = TextView(this).apply {
                    text = "Mapel: ${ec.subjectName ?: "-"}\nGuru Berhalangan: ${ec.absentTeacherName ?: "-"}\nAlasan/Keterangan: ${ec.reason ?: "Belum ada keterangan"}"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#475569"))
                    setPadding(0, 6, 0, 8)
                }
                ecInner.addView(tvDetails)

                val btnHandle = Button(this).apply {
                    text = "👉 Buka Portal Piket"
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#D97706"))
                    setOnClickListener {
                        Toast.makeText(this@TeacherMainActivity, "Silakan buka Portal Sekolah di komputer piket untuk input Guru Pengganti secara resmi.", Toast.LENGTH_LONG).show()
                    }
                }
                ecInner.addView(btnHandle)

                ecCard.addView(ecInner)
                innerContainer.addView(ecCard)
            }
        }

        scroll.addView(innerContainer)
        contentLayout.addView(scroll)
        root.addView(contentLayout)

        dialog.setView(root)
        dialog.show()
    }

    private fun showContactAdminDialog() {
        val dialogBinding = com.school.smartcbt.databinding.DialogContactAdminBinding.inflate(layoutInflater)
        dialogBinding.tvDialogAdminTitle.text = "Layanan Pengaduan & Bantuan Guru"
        dialogBinding.tvDialogAdminSub.text = "Kirim kendala CBT, jadwal mengajar, e-file, atau sistem ke Admin Sekolah."

        val categories = arrayOf(
            "💻 CBT / Bank Soal & Pelaksanaan Ujian",
            "📅 Jadwal Mengajar / Rombel Belum Sesuai",
            "🕒 Presensi Kelas / Scanner Barcode Error",
            "📂 E-File Guru / Berkas Perangkat Mengajar",
            "⚙️ Akun Web Portal Guru / Reset Password",
            "💡 Saran Fitur / Kendala Sistem Aplikasi"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        dialogBinding.spinnerDialogAdminCategory.adapter = adapter

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Kirim Laporan") { _, _ ->
                val desc = dialogBinding.etDialogAdminDescription.text.toString().trim()
                if (desc.isEmpty()) {
                    Toast.makeText(this, "Deskripsi laporan tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val selectedCategory = categories[dialogBinding.spinnerDialogAdminCategory.selectedItemPosition]
                val requestMap = mapOf(
                    "category" to selectedCategory,
                    "title" to "Laporan dari Guru: " + sessionManager.getName(),
                    "description" to desc,
                    "deviceInfo" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})"
                )

                Toast.makeText(this, "Mengirim laporan ke Admin...", Toast.LENGTH_SHORT).show()
                ApiClient.getClient(this).submitHelpdeskReport(requestMap).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful && response.body()?.success == true) {
                            AlertDialog.Builder(this@TeacherMainActivity)
                                .setTitle("✅ Laporan Terkirim")
                                .setMessage("Terima kasih atas laporannya. Administrator Sekolah telah menerima pesan Anda dan akan segera memeriksa.")
                                .setPositiveButton("OK", null)
                                .show()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal mengirim laporan", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showTeachingJournalDialog() {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val teachingSubj = sessionManager.getTeachingSubject().ifEmpty { "Matematika" }
        val header = createFullscreenHeader(
            title = "📖 Isi Jurnal KBM Harian",
            subtitle = "Bidang Studi: $teachingSubj • ${sessionManager.getName()}",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(layout)
        root.addView(scroll)

        val tvClassPrompt = TextView(this).apply {
            text = "Pilih Rombel / Kelas Mengajar:"
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 4, 0, 2)
        }
        layout.addView(tvClassPrompt)

        val spinnerClass = Spinner(this)
        val classes = arrayOf("VII-A", "VII-B", "VII-C", "VII-D", "VII-E", "VII-F", "VII-G", "VII-H", "VII-I", "VII-J", "VII-K")
        spinnerClass.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, classes)
        layout.addView(spinnerClass)

        val etSubject = EditText(this).apply {
            hint = "Mata Pelajaran"
            setText(teachingSubj)
            textSize = 13f
        }
        layout.addView(etSubject)

        val etTopic = EditText(this).apply {
            hint = "Topik / Materi Pokok (contoh: Aljabar Linier Sub-bab 2.3)"
            textSize = 13f
        }
        layout.addView(etTopic)

        val etKD = EditText(this).apply {
            hint = "Kode Kompetensi / KD (contoh: KD 3.2 Memahami Konsep)"
            setText("KD 3.2 - Pemahaman Konsep")
            textSize = 13f
        }
        layout.addView(etKD)

        val etDescription = EditText(this).apply {
            hint = "Uraian kegiatan KBM & catatan kejadian kelas..."
            minLines = 3
            textSize = 13f
        }
        layout.addView(etDescription)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }

        val btnSave = Button(this).apply {
            text = "💾 Simpan Jurnal KBM"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                marginEnd = 8
            }
            setOnClickListener {
                val topic = etTopic.text.toString().trim()
                val desc = etDescription.text.toString().trim()
                val cls = classes[spinnerClass.selectedItemPosition]

                if (topic.isEmpty() || desc.isEmpty()) {
                    Toast.makeText(this@TeacherMainActivity, "Topik dan Uraian kegiatan KBM wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val req = mapOf<String, Any>(
                    "className" to cls,
                    "subjectName" to (etSubject.text.toString().trim().ifEmpty { teachingSubj }),
                    "topic" to topic,
                    "competencyCode" to etKD.text.toString().trim(),
                    "description" to desc,
                    "presentCount" to 30,
                    "absentCount" to 0,
                    "lateCount" to 0
                )

                Toast.makeText(this@TeacherMainActivity, "Menyimpan jurnal mengajar...", Toast.LENGTH_SHORT).show()
                ApiClient.getClient(this@TeacherMainActivity).createTeachingJournal(req).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@TeacherMainActivity, "✅ Jurnal KBM kelas $cls berhasil disimpan ke sistem administrasi guru!", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal menyimpan jurnal", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }

        val btnHistory = Button(this).apply {
            text = "📜 Riwayat"
            textSize = 12f
            setTextColor(Color.parseColor("#334155"))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            setOnClickListener {
                showTeachingJournalHistoryDialog()
            }
        }

        btnRow.addView(btnSave)
        btnRow.addView(btnHistory)
        layout.addView(btnRow)

        dialog.setView(root)
        dialog.show()
    }

    private fun showTeachingJournalHistoryDialog() {
        Toast.makeText(this, "Memuat riwayat jurnal...", Toast.LENGTH_SHORT).show()
        ApiClient.getClient(this).getTeachingJournals().enqueue(object : Callback<List<Map<String, Any>>> {
            override fun onResponse(call: Call<List<Map<String, Any>>>, response: Response<List<Map<String, Any>>>) {
                val list = response.body() ?: emptyList()
                val dialog = createFullscreenDialog()

                val root = LinearLayout(this@TeacherMainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#F8FAFC"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }

                val header = createFullscreenHeader(
                    title = "📜 Riwayat Jurnal Guru",
                    subtitle = "Total: ${list.size} Catatan KBM Tersimpan",
                    onClose = { dialog.dismiss() }
                )
                root.addView(header)

                val scroll = ScrollView(this@TeacherMainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                }

                val listLayout = LinearLayout(this@TeacherMainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                if (list.isEmpty()) {
                    val emptyTv = TextView(this@TeacherMainActivity).apply {
                        text = "Belum ada jurnal mengajar yang tersimpan untuk akun Anda."
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 13f
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, 48, 0, 48)
                    }
                    listLayout.addView(emptyTv)
                } else {
                    for (j in list) {
                        val cls = j["className"] ?: "-"
                        val top = j["topic"] ?: "-"
                        val kd = j["competencyCode"] ?: "-"
                        val desc = j["description"] ?: ""
                        val card = CardView(this@TeacherMainActivity).apply {
                            radius = 12f
                            cardElevation = 2f
                            setCardBackgroundColor(Color.WHITE)
                            useCompatPadding = true
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin = 8
                            }
                        }
                        val inner = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(16, 14, 16, 14)
                        }
                        inner.addView(TextView(this@TeacherMainActivity).apply {
                            text = "📍 Kelas $cls • KD: $kd"
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor("#0F172A"))
                        })
                        inner.addView(TextView(this@TeacherMainActivity).apply {
                            text = "Topik: $top"
                            textSize = 12.5f
                            setTextColor(Color.parseColor("#059669"))
                            setPadding(0, 2, 0, 4)
                        })
                        if (desc.toString().isNotEmpty()) {
                            inner.addView(TextView(this@TeacherMainActivity).apply {
                                text = desc.toString()
                                textSize = 11.5f
                                setTextColor(Color.parseColor("#64748B"))
                            })
                        }
                        card.addView(inner)
                        listLayout.addView(card)
                    }
                }

                scroll.addView(listLayout)
                root.addView(scroll)
                dialog.setView(root)
                dialog.show()
            }

            override fun onFailure(call: Call<List<Map<String, Any>>>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showBroadcastClassDialog() {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "📢 Kirim Broadcast Pengumuman",
            subtitle = "Kirim pesan kilat langsung ke aplikasi Siswa & Orang Tua",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(layout)
        root.addView(scroll)

        val tvClassPrompt = TextView(this).apply {
            text = "Sasaran Kelas / Rombel yang Diampu:"
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 4, 0, 2)
        }
        layout.addView(tvClassPrompt)

        val spinnerClass = Spinner(this)
        val teachingClassesRaw = sessionManager.getTeachingClasses().trim()
        val teacherClassesList = if (teachingClassesRaw.isNotEmpty()) {
            teachingClassesRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val classes = if (teacherClassesList.isNotEmpty()) {
            (listOf("Semua Kelas Diampu (${teacherClassesList.joinToString(", ")})") + teacherClassesList).toTypedArray()
        } else {
            arrayOf("Semua Kelas yang Diampu", "VII-A", "VII-B", "VII-C")
        }
        spinnerClass.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, classes)
        layout.addView(spinnerClass)

        val etTitle = EditText(this).apply {
            hint = "Judul Pengumuman"
            textSize = 13.5f
        }
        layout.addView(etTitle)

        val etMessage = EditText(this).apply {
            hint = "Pesan pengumuman untuk siswa & orang tua..."
            minLines = 4
            textSize = 13f
        }
        layout.addView(etMessage)

        val btnSend = Button(this).apply {
            text = "🚀 Kirim Pengumuman Broadcast"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16
            }
            setOnClickListener {
                val title = etTitle.text.toString().trim()
                val msg = etMessage.text.toString().trim()
                val selectedIdx = spinnerClass.selectedItemPosition
                val cls = if (selectedIdx == 0 && teacherClassesList.isNotEmpty()) {
                    teachingClassesRaw
                } else {
                    classes[selectedIdx]
                }
                if (title.isEmpty() || msg.isEmpty()) {
                    Toast.makeText(this@TeacherMainActivity, "Judul dan pesan tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val req = mapOf("className" to cls, "title" to title, "message" to msg)
                Toast.makeText(this@TeacherMainActivity, "Mengirim broadcast...", Toast.LENGTH_SHORT).show()
                ApiClient.getClient(this@TeacherMainActivity).broadcastToClass(req).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@TeacherMainActivity, "✅ Pengumuman berhasil dibroadcast ke $cls!", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal mengirim broadcast", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
        layout.addView(btnSend)

        dialog.setView(root)
        dialog.show()
    }


    private fun showProctorTokenDialog() {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        var fetchTokensFn: (() -> Unit)? = null
        val header = createFullscreenHeader(
            title = "🔐 Token Pengawas CBT",
            subtitle = "Rilis token ujian harian & pantau integritas sesi",
            onClose = { dialog.dismiss() },
            actionBtnText = "🔄 Segarkan",
            onActionClick = { fetchTokensFn?.invoke() }
        )
        root.addView(header)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(dialogView)

        // Header Card
        val headerCard = CardView(this).apply {
            radius = 12 * resources.displayMetrics.density
            setCardBackgroundColor(Color.parseColor("#1E3A8A"))
            useCompatPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val tvHeaderTitle = TextView(this).apply {
            text = "🔑 Token Ujian CBT Pengawas Ruang"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        val tvHeaderDesc = TextView(this).apply {
            text = "Khusus pengawas ruang & proktor. Dapatkan token aktif langsung per tingkat kelas (Level 7, 8, 9) dan kelas yang diawasi saat ini."
            setTextColor(Color.parseColor("#E0E7FF"))
            textSize = 11f
            setPadding(0, 4, 0, 0)
        }
        headerLayout.addView(tvHeaderTitle)
        headerLayout.addView(tvHeaderDesc)
        headerCard.addView(headerLayout)
        dialogView.addView(headerCard)

        // Filter Level & Class Selection Row
        val filterCard = CardView(this).apply {
            radius = 10 * resources.displayMetrics.density
            setCardBackgroundColor(Color.WHITE)
            cardElevation = 2 * resources.displayMetrics.density
            useCompatPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        val filterLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val tvFilterLabel = TextView(this).apply {
            text = "Filter Ruang & Tingkat Kelas:"
            setTextColor(Color.parseColor("#475569"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        }
        filterLayout.addView(tvFilterLabel)

        val rowSpinners = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        // Level Spinner
        val levelOptions = arrayOf("Semua Tingkat", "Level 7 (Kelas VII)", "Level 8 (Kelas VIII)", "Level 9 (Kelas IX)")
        val levelValues = arrayOf("ALL", "7", "8", "9")
        val spinnerLevel = Spinner(this).apply {
            adapter = ArrayAdapter(this@TeacherMainActivity, android.R.layout.simple_spinner_dropdown_item, levelOptions)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * resources.displayMetrics.density).toInt()
            }
        }

        // Class Spinner
        val classOptions = mutableListOf("Semua Kelas")
        val spinnerClassAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, classOptions)
        val spinnerClass = Spinner(this).apply {
            adapter = spinnerClassAdapter
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (6 * resources.displayMetrics.density).toInt()
            }
        }

        rowSpinners.addView(spinnerLevel)
        rowSpinners.addView(spinnerClass)
        filterLayout.addView(rowSpinners)
        filterCard.addView(filterLayout)
        dialogView.addView(filterCard)

        // Loading ProgressBar
        val progressBar = ProgressBar(this).apply {
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = (20 * resources.displayMetrics.density).toInt()
                bottomMargin = (20 * resources.displayMetrics.density).toInt()
            }
        }
        dialogView.addView(progressBar)

        // Scrollable List Container
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        val examListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(examListContainer)
        dialogView.addView(scrollView)

        // Dialog already created via createFullscreenDialog

        var currentSelectedLevel = "ALL"
        var currentSelectedClass = "Semua Kelas"

        fun fetchTokens() {
            fetchTokensFn = { fetchTokens() }
            progressBar.visibility = View.VISIBLE
            examListContainer.removeAllViews()

            val lvlParam = if (currentSelectedLevel == "ALL") null else currentSelectedLevel
            val clsParam = if (currentSelectedClass == "Semua Kelas") null else currentSelectedClass

            ApiClient.getClient(this).getProctorCbtTokens(lvlParam, clsParam).enqueue(object : Callback<ProctorCbtTokensResponse> {
                override fun onResponse(call: Call<ProctorCbtTokensResponse>, response: Response<ProctorCbtTokensResponse>) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!

                        // Populate class options if not populated yet
                        val classes = body.availableClasses
                        val isRestricted = body.isRestrictedToSupervisedClasses == true
                        if (!classes.isNullOrEmpty()) {
                            val distinctClasses = mutableListOf<String>()
                            if (isRestricted) {
                                if (classes.size > 1) {
                                    distinctClasses.add("Semua Kelas Diawasi")
                                }
                                distinctClasses.addAll(classes)
                            } else {
                                distinctClasses.add("Semua Kelas")
                                distinctClasses.addAll(classes)
                            }
                            if (spinnerClassAdapter.count <= 1 || isRestricted) {
                                spinnerClassAdapter.clear()
                                spinnerClassAdapter.addAll(distinctClasses)
                                spinnerClassAdapter.notifyDataSetChanged()
                            }
                        }

                        if (isRestricted && !classes.isNullOrEmpty()) {
                            tvHeaderDesc.text = "Pengawas Ruang: ${body.proctorName ?: "Bpk/Ibu Guru"}\nMenampilkan khusus token ujian untuk kelas yang Anda awasi: ${classes.joinToString(", ")}"
                        } else {
                            tvHeaderDesc.text = "Khusus pengawas ruang & proktor. Dapatkan token aktif langsung untuk kelas yang diawasi saat ini."
                        }

                        val exams = body.exams
                        if (exams.isNullOrEmpty()) {
                            val tvEmpty = TextView(this@TeacherMainActivity).apply {
                                text = "ℹ️ Belum ada ujian CBT yang aktif untuk tingkat/kelas yang dipilih saat ini."
                                setTextColor(Color.parseColor("#64748B"))
                                textSize = 13f
                                gravity = android.view.Gravity.CENTER
                                setPadding(0, (30 * resources.displayMetrics.density).toInt(), 0, (30 * resources.displayMetrics.density).toInt())
                            }
                            examListContainer.addView(tvEmpty)
                            return
                        }

                        // Render each CBT exam card
                        for (exam in exams) {
                            val card = CardView(this@TeacherMainActivity).apply {
                                radius = 12 * resources.displayMetrics.density
                                setCardBackgroundColor(Color.WHITE)
                                cardElevation = 3 * resources.displayMetrics.density
                                useCompatPadding = true
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                                }
                            }

                            val cardContent = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                val pad12 = (14 * resources.displayMetrics.density).toInt()
                                setPadding(pad12, pad12, pad12, pad12)
                            }

                            // Top row: Level Badge & Status Badge
                            val topRow = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }
                            val levelBadge = TextView(this@TeacherMainActivity).apply {
                                val lvlText = when (exam.level) {
                                    "7" -> "LEVEL 7 (KLS VII)"
                                    "8" -> "LEVEL 8 (KLS VIII)"
                                    "9" -> "LEVEL 9 (KLS IX)"
                                    else -> "SEMUA LEVEL"
                                }
                                text = "🏷️ $lvlText"
                                setTextColor(Color.parseColor("#1D4ED8"))
                                textSize = 11f
                                setTypeface(null, Typeface.BOLD)
                                setBackgroundColor(Color.parseColor("#EFF6FF"))
                                val bp = (6 * resources.displayMetrics.density).toInt()
                                setPadding(bp, bp / 2, bp, bp / 2)
                            }
                            val spacer = View(this@TeacherMainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                            }
                            val statusBadge = TextView(this@TeacherMainActivity).apply {
                                text = if (exam.isTokenActive) "● TOKEN AKTIF" else "○ TOKEN NONAKTIF"
                                setTextColor(if (exam.isTokenActive) Color.parseColor("#15803D") else Color.parseColor("#B91C1C"))
                                textSize = 11f
                                setTypeface(null, Typeface.BOLD)
                                setBackgroundColor(if (exam.isTokenActive) Color.parseColor("#DCFCE7") else Color.parseColor("#FEE2E2"))
                                val bp = (6 * resources.displayMetrics.density).toInt()
                                setPadding(bp, bp / 2, bp, bp / 2)
                            }
                            topRow.addView(levelBadge)
                            topRow.addView(spacer)
                            topRow.addView(statusBadge)
                            cardContent.addView(topRow)

                            // Title & Subject
                            val tvTitle = TextView(this@TeacherMainActivity).apply {
                                text = exam.title
                                setTextColor(Color.parseColor("#0F172A"))
                                textSize = 15f
                                setTypeface(null, Typeface.BOLD)
                                setPadding(0, 8, 0, 2)
                            }
                            val tvMeta = TextView(this@TeacherMainActivity).apply {
                                text = "📚 Mapel: ${exam.subject} • Durasi: ${exam.durationMinutes} Menit • Target: ${exam.assignedClasses ?: "Semua"}"
                                setTextColor(Color.parseColor("#64748B"))
                                textSize = 12f
                                setPadding(0, 0, 0, 8)
                            }
                            cardContent.addView(tvTitle)
                            cardContent.addView(tvMeta)

                            // Big Monospace Token Box
                            val tokenBox = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setBackgroundColor(Color.parseColor("#FEF3C7"))
                                val p = (10 * resources.displayMetrics.density).toInt()
                                setPadding(p, p, p, p)
                                gravity = android.view.Gravity.CENTER
                            }
                            val tvTokenLabel = TextView(this@TeacherMainActivity).apply {
                                text = if (isRestricted) "TOKEN CBT KELAS DIAWASI (${exam.assignedClasses ?: "-"}):" else "TOKEN CBT SAAT INI:"
                                setTextColor(Color.parseColor("#92400E"))
                                textSize = 11f
                                setTypeface(null, Typeface.BOLD)
                            }
                            val tvTokenValue = TextView(this@TeacherMainActivity).apply {
                                text = exam.token
                                setTextColor(Color.parseColor("#B45309"))
                                textSize = 32f
                                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                                letterSpacing = 0.25f
                            }
                            tokenBox.addView(tvTokenLabel)
                            tokenBox.addView(tvTokenValue)
                            cardContent.addView(tokenBox)

                            // Action Buttons Row 1 (Salin, Layar Penuh, Acak)
                            val actionRow = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
                                weightSum = 3f
                            }

                            // 1. Copy Token Button
                            val btnCopy = Button(this@TeacherMainActivity).apply {
                                text = "📋 Salin"
                                textSize = 11f
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#2563EB"))
                                layoutParams = LinearLayout.LayoutParams(0, (38 * resources.displayMetrics.density).toInt(), 1f).apply {
                                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                                }
                                setOnClickListener {
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Token CBT", exam.token)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(this@TeacherMainActivity, "📋 Token ${exam.token} berhasil disalin!", Toast.LENGTH_SHORT).show()
                                }
                            }

                            // 2. Fullscreen Projector Button
                            val btnFullscreen = Button(this@TeacherMainActivity).apply {
                                text = "📺 Layar Penuh"
                                textSize = 11f
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#0D9488"))
                                layoutParams = LinearLayout.LayoutParams(0, (38 * resources.displayMetrics.density).toInt(), 1f).apply {
                                    marginStart = (2 * resources.displayMetrics.density).toInt()
                                    marginEnd = (2 * resources.displayMetrics.density).toInt()
                                }
                                setOnClickListener {
                                    showFullscreenTokenDialog(exam)
                                }
                            }

                            // 3. Regenerate Token Button
                            val btnRegen = Button(this@TeacherMainActivity).apply {
                                text = "🔄 Acak Baru"
                                textSize = 11f
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#D97706"))
                                layoutParams = LinearLayout.LayoutParams(0, (38 * resources.displayMetrics.density).toInt(), 1f).apply {
                                    marginStart = (4 * resources.displayMetrics.density).toInt()
                                }
                                setOnClickListener {
                                    AlertDialog.Builder(this@TeacherMainActivity)
                                        .setTitle("Acak Token Baru?")
                                        .setMessage("Apakah Anda yakin ingin mengganti token untuk ujian '${exam.title}'? Token lama tidak akan berlaku lagi bagi siswa yang belum masuk ujian.")
                                        .setPositiveButton("Ya, Ganti Token") { _, _ ->
                                            Toast.makeText(this@TeacherMainActivity, "Mengacak token baru...", Toast.LENGTH_SHORT).show()
                                            val reqMap = mutableMapOf("examId" to exam.id)
                                            if (currentSelectedClass != "Semua Kelas" && currentSelectedClass != "Semua Kelas Diawasi") {
                                                reqMap["className"] = currentSelectedClass
                                            }
                                            ApiClient.getClient(this@TeacherMainActivity).regenerateCbtToken(reqMap).enqueue(object : Callback<Map<String, Any>> {
                                                override fun onResponse(call: Call<Map<String, Any>>, resp: Response<Map<String, Any>>) {
                                                    if (resp.isSuccessful) {
                                                        val newToken = resp.body()?.get("token") as? String
                                                        if (!newToken.isNullOrBlank()) {
                                                            tvTokenValue.text = newToken
                                                        }
                                                        Toast.makeText(this@TeacherMainActivity, "✅ Token baru berhasil dirilis: ${newToken ?: ""}", Toast.LENGTH_SHORT).show()
                                                        fetchTokens()
                                                    } else {
                                                        Toast.makeText(this@TeacherMainActivity, "Gagal mengacak token", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                                override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {
                                                    Toast.makeText(this@TeacherMainActivity, "Error: ${t.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            })
                                        }
                                        .setNegativeButton("Batal", null)
                                        .show()
                                }
                            }

                            actionRow.addView(btnCopy)
                            actionRow.addView(btnFullscreen)
                            actionRow.addView(btnRegen)
                            cardContent.addView(actionRow)

                            // Action Button 4: Live Monitor Siswa & Berita Acara Ujian (BAP)
                            val btnMonitor = Button(this@TeacherMainActivity).apply {
                                text = "👥 Siswa Kelas & Berita Acara (BAP)"
                                textSize = 12f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#4F46E5"))
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    (40 * resources.displayMetrics.density).toInt()
                                ).apply {
                                    topMargin = (8 * resources.displayMetrics.density).toInt()
                                }
                                setOnClickListener {
                                    val defClass = if (currentSelectedClass != "Semua Kelas") currentSelectedClass else (exam.assignedClasses?.split(",")?.firstOrNull()?.trim() ?: "VII-A")
                                    showProctorClassMonitoringDialog(exam, defClass)
                                }
                            }
                            cardContent.addView(btnMonitor)

                            // Action Button 5: Lembar Token Siap Cetak A4 & Pengawas Ruang
                            val btnPrintSlip = Button(this@TeacherMainActivity).apply {
                                text = "🖨️ Lembar Token A4 & Pengawas Ruang"
                                textSize = 12f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#059669"))
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    (40 * resources.displayMetrics.density).toInt()
                                ).apply {
                                    topMargin = (6 * resources.displayMetrics.density).toInt()
                                }
                                setOnClickListener {
                                    showClassTokensSlipDialog(exam)
                                }
                            }
                            cardContent.addView(btnPrintSlip)

                            card.addView(cardContent)
                            examListContainer.addView(card)
                        }
                    } else {
                        Toast.makeText(this@TeacherMainActivity, "Gagal memuat token proktor CBT", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<ProctorCbtTokensResponse>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@TeacherMainActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            })
        }

        spinnerLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = levelValues[position]
                if (selected != currentSelectedLevel) {
                    currentSelectedLevel = selected
                    fetchTokens()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerClass.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = spinnerClassAdapter.getItem(position) ?: "VII-A"
                if (selected != currentSelectedClass) {
                    currentSelectedClass = selected
                    fetchTokens()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        dialog.setView(root)
        dialog.show()
        fetchTokens()
    }

    private fun showFullscreenTokenDialog(exam: ProctorExamDto) {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "🖥️ Mode Proyektor Token CBT",
            subtitle = "${exam.title} • Token: ${exam.token}",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val scrollContainer = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val fullscreenView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvSchoolBadge = TextView(this).apply {
            text = "SMP NEGERI 1 BOYOLANGU • CBT PROCTOR"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.15f
            gravity = android.view.Gravity.CENTER
        }
        val tvExamTitle = TextView(this).apply {
            text = exam.title
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10, 0, 4)
        }
        val tvExamMeta = TextView(this).apply {
            text = "Mata Pelajaran: ${exam.subject} • Tingkat: Level ${exam.level ?: "Semua"} • Kelas: ${exam.assignedClasses ?: "Semua"}"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val tokenBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvTokenPrompt = TextView(this).apply {
            text = "MASUKKAN TOKEN DI APLIKASI SISWA:"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.1f
            gravity = android.view.Gravity.CENTER
        }
        val tvBigToken = TextView(this).apply {
            text = exam.token
            setTextColor(Color.parseColor("#FCD34D"))
            textSize = 52f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.25f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 6, 0, 6)
        }

        // QR Code Card for direct student scan
        val cardQr = CardView(this).apply {
            radius = 12 * resources.displayMetrics.density
            setCardBackgroundColor(Color.WHITE)
            cardElevation = 4 * resources.displayMetrics.density
            val sizePx = (210 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        val ivQr = ImageView(this).apply {
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val qrBitmap = com.school.smartcbt.utils.QrCodeHelper.generateQrCodeBitmap(exam.token, 450)
            setImageBitmap(qrBitmap)
        }
        cardQr.addView(ivQr)

        val tvScanHint = TextView(this).apply {
            text = "📷 Siswa dapat memindai QR Code di atas langsung dari aplikasi CBT"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        val tvInstructions = TextView(this).apply {
            text = "Durasi: ${exam.durationMinutes} Menit | Dilarang membuka aplikasi lain / split screen saat ujian berlangsung."
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11.5f
            gravity = android.view.Gravity.CENTER
        }

        tokenBox.addView(tvTokenPrompt)
        tokenBox.addView(tvBigToken)
        tokenBox.addView(cardQr)
        tokenBox.addView(tvScanHint)
        tokenBox.addView(tvInstructions)

        fullscreenView.addView(tvSchoolBadge)
        fullscreenView.addView(tvExamTitle)
        fullscreenView.addView(tvExamMeta)
        fullscreenView.addView(tokenBox)

        scrollContainer.addView(fullscreenView)
        root.addView(scrollContainer)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showClassTokensSlipDialog(exam: ProctorExamDto) {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "🖨️ Lembar Token A4 & Pengawas Ruang",
            subtitle = "${exam.title} • Mapel: ${exam.subject}",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        // A4 Paper Simulation Card (White background, shadow, bordered)
        val paperCard = CardView(this).apply {
            radius = 12 * resources.displayMetrics.density
            setCardBackgroundColor(Color.WHITE)
            cardElevation = 4 * resources.displayMetrics.density
            useCompatPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val paperInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        // Kop Surat
        val tvKop1 = TextView(this).apply {
            text = "PEMERINTAH KABUPATEN TULUNGAGUNG\nDINAS PENDIDIKAN"
            textSize = 11f
            setTextColor(Color.parseColor("#475569"))
            gravity = android.view.Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        val tvKop2 = TextView(this).apply {
            text = "UPTD SMP NEGERI 1 BOYOLANGU"
            textSize = 15f
            setTextColor(Color.parseColor("#0F172A"))
            gravity = android.view.Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 2, 0, 2)
        }
        val tvKop3 = TextView(this).apply {
            text = "LEMBAR DISTRIBUSI TOKEN CBT & PENGAWAS RUANG"
            textSize = 12.5f
            setTextColor(Color.parseColor("#1E3A8A"))
            gravity = android.view.Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        val tvKopMeta = TextView(this).apply {
            text = "Asesmen: ${exam.title} | Mapel: ${exam.subject} | Tanggal: ${exam.executionDate ?: "-"}"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 4, 0, 10)
        }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (2 * resources.displayMetrics.density).toInt()).apply {
                bottomMargin = (14 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        paperInner.addView(tvKop1)
        paperInner.addView(tvKop2)
        paperInner.addView(tvKop3)
        paperInner.addView(tvKopMeta)
        paperInner.addView(divider)

        // Class rows
        val tokensMap = exam.classTokens ?: emptyMap()
        val proctorsMap = exam.classProctors ?: emptyMap()
        val classesToDisplay = if (tokensMap.isNotEmpty()) {
            tokensMap.keys.toList()
        } else {
            exam.assignedClasses?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("Semua Kelas")
        }

        // Table Header Card
        val tableHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            weightSum = 10f
        }
        val thClass = TextView(this).apply {
            text = "Rombel"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
        }
        val thProctor = TextView(this).apply {
            text = "Guru Pengawas"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4.5f)
        }
        val thToken = TextView(this).apply {
            text = "Token CBT"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
        }
        tableHeader.addView(thClass)
        tableHeader.addView(thProctor)
        tableHeader.addView(thToken)
        paperInner.addView(tableHeader)

        var idx = 1
        val shareTextBuilder = StringBuilder()
        shareTextBuilder.append("*UPTD SMP NEGERI 1 BOYOLANGU*\n")
        shareTextBuilder.append("*LEMBAR DISTRIBUSI TOKEN CBT & PENGAWAS*\n")
        shareTextBuilder.append("Asesmen: ").append(exam.title).append("\n")
        shareTextBuilder.append("Mapel: ").append(exam.subject).append("\n")
        shareTextBuilder.append("Tanggal: ").append(exam.executionDate ?: "-").append("\n")
        shareTextBuilder.append("------------------------------------------\n")

        for (cls in classesToDisplay) {
            val cToken = tokensMap[cls] ?: exam.token
            val proctorName = proctorsMap[cls] ?: sessionManager.getName().ifEmpty { "Pengawas Ruang" }

            shareTextBuilder.append("$idx. Kelas $cls | Pengawas: $proctorName | Token: *$cToken*\n")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val pV = (9 * resources.displayMetrics.density).toInt()
                val pH = (10 * resources.displayMetrics.density).toInt()
                setPadding(pH, pV, pH, pV)
                if (idx % 2 == 0) setBackgroundColor(Color.parseColor("#F8FAFC"))
                weightSum = 10f
            }

            val tvCls = TextView(this).apply {
                text = "Kelas $cls"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
            }
            val tvProc = TextView(this).apply {
                text = proctorName
                textSize = 11.5f
                setTextColor(Color.parseColor("#047857"))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4.5f)
            }
            val tvTok = TextView(this).apply {
                text = cToken
                textSize = 13.5f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E3A8A"))
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
            }

            row.addView(tvCls)
            row.addView(tvProc)
            row.addView(tvTok)
            paperInner.addView(row)
            idx++
        }

        // Tanda tangan info
        val signRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 0)
            weightSum = 2f
        }
        val signLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        signLeft.addView(TextView(this).apply {
            text = "Mengetahui,\nKetua Panitia CBT"
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#475569"))
        })
        signLeft.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (36 * resources.displayMetrics.density).toInt())
        })
        signLeft.addView(TextView(this).apply {
            text = "( .................................... )"
            textSize = 11f
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, Typeface.BOLD)
        })

        val signRight = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        signRight.addView(TextView(this).apply {
            text = "Boyolangu, ${exam.executionDate ?: "-"}\nKoordinator Proktor"
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#475569"))
        })
        signRight.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (36 * resources.displayMetrics.density).toInt())
        })
        signRight.addView(TextView(this).apply {
            text = "( .................................... )"
            textSize = 11f
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, Typeface.BOLD)
        })

        signRow.addView(signLeft)
        signRow.addView(signRight)
        paperInner.addView(signRow)

        paperCard.addView(paperInner)
        contentLayout.addView(paperCard)
        scroll.addView(contentLayout)
        root.addView(scroll)

        // Bottom Action Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = 6 * resources.displayMetrics.density
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            weightSum = 2f
        }

        val btnPrint = Button(this).apply {
            text = "🖨️ Cetak / Simpan PDF A4"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E3A8A"))
            layoutParams = LinearLayout.LayoutParams(0, (42 * resources.displayMetrics.density).toInt(), 1f).apply {
                marginEnd = (4 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener {
                val html = generateA4SlipHtml(exam, classesToDisplay, tokensMap, proctorsMap)
                printHtmlDocument(html, "Lembar_Token_${exam.title}")
            }
        }

        val btnShareWa = Button(this).apply {
            text = "📋 Salin Format WA"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#059669"))
            layoutParams = LinearLayout.LayoutParams(0, (42 * resources.displayMetrics.density).toInt(), 1f).apply {
                marginStart = (4 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Distribusi Token CBT", shareTextBuilder.toString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@TeacherMainActivity, "📋 Rincian token & pengawas berhasil disalin! Siap dipaste ke WA.", Toast.LENGTH_LONG).show()
            }
        }

        bottomBar.addView(btnPrint)
        bottomBar.addView(btnShareWa)
        root.addView(bottomBar)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun generateA4SlipHtml(
        exam: ProctorExamDto,
        classes: List<String>,
        tokensMap: Map<String, String>,
        proctorsMap: Map<String, String>
    ): String {
        val rows = StringBuilder()
        classes.forEachIndexed { idx, cls ->
            val cToken = tokensMap[cls] ?: exam.token
            val proctorName = proctorsMap[cls] ?: sessionManager.getName().ifEmpty { "Pengawas Ruang" }
            rows.append("""
                <tr style="border-bottom: 1px solid #ddd; text-align: center;">
                    <td style="padding: 8px; border: 1px solid #ccc;">${idx + 1}</td>
                    <td style="padding: 8px; border: 1px solid #ccc; font-weight: bold;">Kelas $cls</td>
                    <td style="padding: 8px; border: 1px solid #ccc;">Ruang Kelas $cls</td>
                    <td style="padding: 8px; border: 1px solid #ccc; font-weight: bold; color: #047857;">$proctorName</td>
                    <td style="padding: 8px; border: 1px solid #ccc; font-family: monospace; font-size: 16px; font-weight: 900; color: #1e3a8a; letter-spacing: 2px;">$cToken</td>
                    <td style="padding: 8px; border: 1px solid #ccc; font-size: 11px;">Siswa login dengan token ini. Pengawas dapat mengontrol &amp; reset dari APK Guru.</td>
                </tr>
            """.trimIndent())
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <title>Lembar Token Ujian - ${exam.title}</title>
                <style>
                    body { font-family: Arial, sans-serif; padding: 20px; color: #111; }
                    table { width: 100%; border-collapse: collapse; margin-top: 15px; }
                    th { background: #f0f4f8; padding: 8px; border: 1px solid #ccc; font-size: 12px; }
                    h2, h3, p { margin: 4px 0; }
                    @media print {
                        body { padding: 0; }
                    }
                </style>
            </head>
            <body>
                <div style="border-bottom: 3px double #000; padding-bottom: 10px; text-align: center; margin-bottom: 15px;">
                    <h2>UPTD SMP NEGERI 1 BOYOLANGU</h2>
                    <h3>LEMBAR DISTRIBUSI TOKEN CBT &amp; PENGAWAS RUANG KELAS</h3>
                    <p style="font-size: 13px;">Asesmen: <b>${exam.title}</b> | Mapel: <b>${exam.subject}</b> | Tanggal: <b>${exam.executionDate ?: "-"}</b></p>
                </div>
                <table>
                    <thead>
                        <tr>
                            <th style="width: 40px;">No</th>
                            <th>Target Rombel</th>
                            <th>Alokasi Ruang</th>
                            <th>Nama Guru Pengawas</th>
                            <th>Token Ujian Khusus</th>
                            <th>Petunjuk Pengawas Ruang</th>
                        </tr>
                    </thead>
                    <tbody>
                        $rows
                    </tbody>
                </table>
                <div style="margin-top: 30px; display: flex; justify-content: space-between; font-size: 12px;">
                    <div>
                        <p>Mengetahui,</p>
                        <p><b>Ketua Panitia CBT</b></p>
                        <div style="height: 50px;"></div>
                        <p>( .................................... )</p>
                    </div>
                    <div>
                        <p>Boyolangu, ${exam.executionDate ?: "-"}</p>
                        <p><b>Koordinator Proktor &amp; Teknisi</b></p>
                        <div style="height: 50px;"></div>
                        <p>( .................................... )</p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun printHtmlDocument(htmlDocument: String, jobName: String) {
        try {
            val webView = WebView(this)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    val printAdapter = webView.createPrintDocumentAdapter(jobName)
                    val printAttributes = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("id1", "print", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                    printManager?.print(jobName, printAdapter, printAttributes)
                }
            }
            webView.loadDataWithBaseURL(null, htmlDocument, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mencetak dokumen: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProctorClassMonitoringDialog(exam: ProctorExamDto, defaultClass: String) {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        var fetchStudentsFn: (() -> Unit)? = null
        val header = createFullscreenHeader(
            title = "👥 Monitoring Peserta Ujian CBT",
            subtitle = "Pantau status siswa live & kendali reset ujian",
            onClose = { dialog.dismiss() },
            actionBtnText = "🔄 Segarkan",
            onActionClick = { fetchStudentsFn?.invoke() }
        )
        root.addView(header)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(dialogView)

        // Header
        val headerCard = CardView(this).apply {
            radius = 12 * resources.displayMetrics.density
            setCardBackgroundColor(Color.parseColor("#1E1B4B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        val headerInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val tvExamHeader = TextView(this).apply {
            text = "👥 Ruang & Peserta: ${exam.title}"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        val tvExamSub = TextView(this).apply {
            text = "Mata Pelajaran: ${exam.subject} • Token: ${exam.token} • Durasi: ${exam.durationMinutes}m"
            setTextColor(Color.parseColor("#C7D2FE"))
            textSize = 11f
            setPadding(0, 4, 0, 0)
        }
        headerInner.addView(tvExamHeader)
        headerInner.addView(tvExamSub)
        headerCard.addView(headerInner)
        dialogView.addView(headerCard)

        // Class Selection Spinner & Refresh Row
        val topActionCard = CardView(this).apply {
            radius = 10 * resources.displayMetrics.density
            setCardBackgroundColor(Color.WHITE)
            cardElevation = 2 * resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            }
        }
        val topActionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val tvPilihKelas = TextView(this).apply {
            text = "Kelas:"
            setTextColor(Color.parseColor("#334155"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, (6 * resources.displayMetrics.density).toInt(), 0)
        }

        val assignedClassesList = exam.assignedClasses?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf(defaultClass)
        val classSpinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, assignedClassesList)
        val spinnerClassSelect = Spinner(this).apply {
            adapter = classSpinnerAdapter
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val initialIdx = assignedClassesList.indexOf(defaultClass)
            if (initialIdx >= 0) setSelection(initialIdx)
        }

        val btnRefresh = Button(this).apply {
            text = "🔄 Segarkan"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#059669"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (36 * resources.displayMetrics.density).toInt()
            ).apply {
                marginStart = (8 * resources.displayMetrics.density).toInt()
            }
        }

        topActionLayout.addView(tvPilihKelas)
        topActionLayout.addView(spinnerClassSelect)
        topActionLayout.addView(btnRefresh)
        topActionCard.addView(topActionLayout)
        dialogView.addView(topActionCard)

        // Summary Badges Card (Total, Hadir, Selesai, Terkunci)
        val summaryCard = CardView(this).apply {
            radius = 10 * resources.displayMetrics.density
            setCardBackgroundColor(Color.WHITE)
            cardElevation = 2 * resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            }
        }
        val summaryLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            weightSum = 4f
        }
        val tvTotal = TextView(this).apply {
            text = "Total: -\nSiswa"
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvPresent = TextView(this).apply {
            text = "Mengerjakan:\n-"
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#2563EB"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvFinished = TextView(this).apply {
            text = "Selesai:\n-"
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#16A34A"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvLocked = TextView(this).apply {
            text = "Terkunci:\n-"
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#DC2626"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        summaryLayout.addView(tvTotal)
        summaryLayout.addView(tvPresent)
        summaryLayout.addView(tvFinished)
        summaryLayout.addView(tvLocked)
        summaryCard.addView(summaryLayout)
        dialogView.addView(summaryCard)

        // Progress Loading
        val progressBar = ProgressBar(this).apply {
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = (16 * resources.displayMetrics.density).toInt()
                bottomMargin = (16 * resources.displayMetrics.density).toInt()
            }
        }
        dialogView.addView(progressBar)

        // Scroll Container for Student List
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val studentListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(studentListContainer)
        dialogView.addView(scrollView)

        var lastFetchedSummary: com.school.smartcbt.data.model.ProctorClassSummaryDto? = null
        var lastFetchedStudents: List<com.school.smartcbt.data.model.ProctorStudentDto> = emptyList()

        // Button BAP (Berita Acara Pelaksanaan Ujian)
        val btnBap = Button(this).apply {
            text = "📝 Buat Berita Acara Ujian (BAP)"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7C3AED"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * resources.displayMetrics.density).toInt()
            ).apply {
                topMargin = (10 * resources.displayMetrics.density).toInt()
            }
        }
        dialogView.addView(btnBap)

        fun fetchStudents(isBackground: Boolean = false) {
            fetchStudentsFn = { fetchStudents(false) }
            val selectedClass = spinnerClassSelect.selectedItem?.toString() ?: defaultClass
            if (!isBackground) {
                progressBar.visibility = View.VISIBLE
            }

            ApiClient.getClient(this).getProctorClassStudents(exam.id, selectedClass).enqueue(object : Callback<com.school.smartcbt.data.model.ProctorClassStudentsResponse> {
                override fun onResponse(
                    call: Call<com.school.smartcbt.data.model.ProctorClassStudentsResponse>,
                    response: Response<com.school.smartcbt.data.model.ProctorClassStudentsResponse>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        lastFetchedSummary = body.summary
                        lastFetchedStudents = body.students ?: emptyList()

                        val sum = body.summary
                        if (sum != null) {
                            tvTotal.text = "Total:\n${sum.totalStudents} Siswa"
                            tvPresent.text = "Mengerjakan:\n${sum.totalPresent}"
                            tvFinished.text = "Selesai:\n${sum.totalFinished}"
                            tvLocked.text = "Terkunci:\n${sum.totalLocked}"
                        }

                        studentListContainer.removeAllViews()

                        if (lastFetchedStudents.isEmpty()) {
                            val tvEmpty = TextView(this@TeacherMainActivity).apply {
                                text = "Belum ada data siswa terdaftar di kelas $selectedClass untuk ujian ini."
                                textSize = 12f
                                setTextColor(Color.parseColor("#64748B"))
                                gravity = android.view.Gravity.CENTER
                                setPadding(0, 40, 0, 40)
                            }
                            studentListContainer.addView(tvEmpty)
                            return
                        }

                        for (st in lastFetchedStudents) {
                            val cardStudent = CardView(this@TeacherMainActivity).apply {
                                radius = 8 * resources.displayMetrics.density
                                setCardBackgroundColor(Color.WHITE)
                                cardElevation = 1.5f * resources.displayMetrics.density
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                                }
                            }
                            val itemLayout = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                val p = (10 * resources.displayMetrics.density).toInt()
                                setPadding(p, p, p, p)
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }

                            val colInfo = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            val tvName = TextView(this@TeacherMainActivity).apply {
                                text = st.name
                                textSize = 13f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor("#1E293B"))
                            }
                            val tvMeta = TextView(this@TeacherMainActivity).apply {
                                val nisnText = if (!st.nisn.isNullOrBlank()) "NISN: ${st.nisn}" else ""
                                val strikes = if (st.strikeCount > 0) " • ⚠️ ${st.strikeCount}x Peringatan" else ""
                                val scoreText = if (st.score != null) " • Nilai: ${st.score}" else ""
                                text = "$nisnText$strikes$scoreText"
                                textSize = 10f
                                setTextColor(Color.parseColor("#64748B"))
                            }
                            colInfo.addView(tvName)
                            colInfo.addView(tvMeta)
                            itemLayout.addView(colInfo)

                            // Status Badge
                            val statusBadge = TextView(this@TeacherMainActivity).apply {
                                textSize = 10f
                                setTypeface(null, Typeface.BOLD)
                                setPadding(
                                    (8 * resources.displayMetrics.density).toInt(),
                                    (4 * resources.displayMetrics.density).toInt(),
                                    (8 * resources.displayMetrics.density).toInt(),
                                    (4 * resources.displayMetrics.density).toInt()
                                )
                                when (st.status) {
                                    "TERKUNCI" -> {
                                        text = "🔒 TERKUNCI"
                                        setTextColor(Color.WHITE)
                                        setBackgroundColor(Color.parseColor("#EF4444"))
                                    }
                                    "SEDANG_MENGERJAKAN" -> {
                                        text = "⚡ MENGERJAKAN"
                                        setTextColor(Color.WHITE)
                                        setBackgroundColor(Color.parseColor("#3B82F6"))
                                    }
                                    "SELESAI" -> {
                                        text = "✅ SELESAI"
                                        setTextColor(Color.WHITE)
                                        setBackgroundColor(Color.parseColor("#10B981"))
                                    }
                                    else -> {
                                        text = "⏳ BELUM MULAI"
                                        setTextColor(Color.parseColor("#475569"))
                                        setBackgroundColor(Color.parseColor("#E2E8F0"))
                                    }
                                }
                            }
                            itemLayout.addView(statusBadge)

                            // Tombol Ganti HP / Reset pada setiap baris data siswa
                            val btnResetLock = Button(this@TeacherMainActivity).apply {
                                text = "🔄 Ganti HP / Reset"
                                textSize = 10.5f
                                setTextColor(Color.WHITE)
                                val isAlert = st.status == "TERKUNCI" || st.strikeCount > 0
                                setBackgroundColor(if (isAlert) Color.parseColor("#DC2626") else Color.parseColor("#D97706"))
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    (34 * resources.displayMetrics.density).toInt()
                                ).apply {
                                    marginStart = (6 * resources.displayMetrics.density).toInt()
                                }
                                setOnClickListener {
                                    AlertDialog.Builder(this@TeacherMainActivity)
                                        .setTitle("Reset Sesi & Ganti HP Siswa?")
                                        .setMessage("Reset kunci perangkat & status ujian untuk '${st.name}'?\n\nSiswa diizinkan berganti HP/perangkat baru atau melanjutkan ujian jika aplikasi tertutup/hang tanpa perlu lapor admin server.")
                                        .setPositiveButton("Ya, Reset & Buka") { _, _ ->
                                            Toast.makeText(this@TeacherMainActivity, "Mereset kuncian siswa...", Toast.LENGTH_SHORT).show()
                                            ApiClient.getClient(this@TeacherMainActivity).proctorResetStudentLock(
                                                com.school.smartcbt.data.model.ProctorResetLockRequest(exam.id, st.studentId)
                                            ).enqueue(object : Callback<com.school.smartcbt.data.model.BasicResponse> {
                                                override fun onResponse(c: Call<com.school.smartcbt.data.model.BasicResponse>, r: Response<com.school.smartcbt.data.model.BasicResponse>) {
                                                    if (r.isSuccessful && r.body()?.success == true) {
                                                        Toast.makeText(this@TeacherMainActivity, "✅ Sesi ${st.name} berhasil di-reset! Siswa dapat login / ganti HP.", Toast.LENGTH_SHORT).show()
                                                        fetchStudents(false)
                                                    } else {
                                                        Toast.makeText(this@TeacherMainActivity, r.body()?.message ?: "Gagal mereset siswa", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                                override fun onFailure(c: Call<com.school.smartcbt.data.model.BasicResponse>, t: Throwable) {
                                                    Toast.makeText(this@TeacherMainActivity, "Error: ${t.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            })
                                        }
                                        .setNegativeButton("Batal", null)
                                        .show()
                                }
                            }
                            itemLayout.addView(btnResetLock)

                            cardStudent.addView(itemLayout)
                            studentListContainer.addView(cardStudent)
                        }
                    } else if (!isBackground) {
                        Toast.makeText(this@TeacherMainActivity, "Gagal memuat peserta kelas", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<com.school.smartcbt.data.model.ProctorClassStudentsResponse>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    if (!isBackground) {
                        Toast.makeText(this@TeacherMainActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        val autoRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var isDialogActive = true
        val autoRefreshRunnable = object : Runnable {
            override fun run() {
                if (isDialogActive) {
                    fetchStudents(isBackground = true)
                    autoRefreshHandler.postDelayed(this, 8000)
                }
            }
        }

        dialog.setOnDismissListener {
            isDialogActive = false
            autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        }

        btnRefresh.setOnClickListener { fetchStudents(false) }
        spinnerClassSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                fetchStudents(false)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        btnBap.setOnClickListener {
            val curClass = spinnerClassSelect.selectedItem?.toString() ?: defaultClass
            showProctorBapDialog(exam, curClass, lastFetchedSummary, lastFetchedStudents)
        }

        dialog.setView(root)
        dialog.show()
        fetchStudents(false)
        autoRefreshHandler.postDelayed(autoRefreshRunnable, 8000)
    }

    private fun showProctorBapDialog(
        exam: ProctorExamDto,
        className: String,
        summary: com.school.smartcbt.data.model.ProctorClassSummaryDto?,
        students: List<com.school.smartcbt.data.model.ProctorStudentDto>
    ) {
        val bapView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvBapHeader = TextView(this).apply {
            text = "📝 Berita Acara Pelaksanaan Ujian (BAP)"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E1B4B"))
            setPadding(0, 0, 0, 4)
        }
        val tvBapSub = TextView(this).apply {
            text = "Ujian: ${exam.title} (${exam.subject})\nKelas / Ruang: $className"
            textSize = 11f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 0, 0, 12)
        }
        bapView.addView(tvBapHeader)
        bapView.addView(tvBapSub)

        val etRoomName = EditText(this).apply {
            hint = "Nama / Nomor Ruang (contoh: Ruang 04 Lab Komputer)"
            setText("Ruang Kelas $className")
            textSize = 13f
        }
        bapView.addView(etRoomName)

        val regCount = summary?.totalStudents ?: students.size
        val presCount = (summary?.totalPresent ?: 0) + (summary?.totalFinished ?: 0)
        val absCount = Math.max(0, regCount - presCount)

        // Deteksi daftar nama yang belum mulai / absen
        val defaultAbsentNames = students.filter { it.status == "BELUM_MULAI" }.joinToString(", ") { it.name }

        val etPresent = EditText(this).apply {
            hint = "Jumlah Siswa Hadir"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(presCount.toString())
            textSize = 13f
        }
        val etAbsent = EditText(this).apply {
            hint = "Jumlah Siswa Tidak Hadir"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(absCount.toString())
            textSize = 13f
        }
        val etAbsentList = EditText(this).apply {
            hint = "Daftar Siswa Tidak Hadir (Nama / NISN)"
            setText(defaultAbsentNames)
            textSize = 13f
        }
        val etNotes = EditText(this).apply {
            hint = "Catatan Kejadian Khusus / Integritas Ruang Ujian (Opsional)"
            textSize = 13f
        }

        bapView.addView(TextView(this).apply { text = "Jumlah Siswa Hadir:"; textSize = 11f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 6, 0, 2) })
        bapView.addView(etPresent)
        bapView.addView(TextView(this).apply { text = "Jumlah Siswa Tidak Hadir:"; textSize = 11f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 6, 0, 2) })
        bapView.addView(etAbsent)
        bapView.addView(TextView(this).apply { text = "Daftar Siswa Tidak Hadir:"; textSize = 11f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 6, 0, 2) })
        bapView.addView(etAbsentList)
        bapView.addView(TextView(this).apply { text = "Catatan Kejadian Ruang Ujian:"; textSize = 11f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 6, 0, 2) })
        bapView.addView(etNotes)

        AlertDialog.Builder(this)
            .setTitle("Kirim Berita Acara Ujian (BAP)")
            .setView(bapView)
            .setPositiveButton("Simpan & Kirim BAP ke Portal") { _, _ ->
                val room = etRoomName.text.toString().trim()
                val pCount = etPresent.text.toString().toIntOrNull() ?: presCount
                val aCount = etAbsent.text.toString().toIntOrNull() ?: absCount
                val aList = etAbsentList.text.toString().trim()
                val notes = etNotes.text.toString().trim()

                Toast.makeText(this, "Mengirim Berita Acara...", Toast.LENGTH_SHORT).show()
                val req = com.school.smartcbt.data.model.ProctorSubmitBapRequest(
                    examId = exam.id,
                    className = className,
                    roomName = if (room.isNotEmpty()) room else "Ruang $className",
                    totalRegistered = regCount,
                    totalPresent = pCount,
                    totalAbsent = aCount,
                    absentList = if (aList.isNotEmpty()) aList else null,
                    incidentNotes = if (notes.isNotEmpty()) notes else null
                )

                ApiClient.getClient(this).submitProctorBap(req).enqueue(object : Callback<com.school.smartcbt.data.model.BasicResponse> {
                    override fun onResponse(call: Call<com.school.smartcbt.data.model.BasicResponse>, response: Response<com.school.smartcbt.data.model.BasicResponse>) {
                        if (response.isSuccessful && response.body()?.success == true) {
                            Toast.makeText(this@TeacherMainActivity, "✅ Berita Acara Ujian berhasil disimpan dan terhubung ke portal sekolah!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, response.body()?.message ?: "Gagal menyimpan BAP", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onFailure(call: Call<com.school.smartcbt.data.model.BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }




    // ================= 1-TAP PRESENSI KELAS PER JAM PELAJARAN (GURU) =================
    private var activeScheduleDialogRefresh: (() -> Unit)? = null

    private fun showTeacherScheduleDialog() {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        var loadAndRenderScheduleFn: (() -> Unit)? = null
        val header = createFullscreenHeader(
            title = "📅 Jadwal Mengajar & Presensi JP",
            subtitle = "Buka sesi kelas (1-Tap IN) saat jam pelajaran dimulai",
            onClose = { dialog.dismiss() },
            actionBtnText = "🔄 Segarkan",
            onActionClick = { loadAndRenderScheduleFn?.invoke() }
        )
        root.addView(header)

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val tvSubtitle = TextView(this).apply {
            text = "Klik 'Buka Sesi Kelas (IN)' saat Anda berada di dalam ruang kelas (maksimal 10 meter) agar siswa dapat melakukan presensi."
            setTextColor(Color.parseColor("#64748B"))
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        contentLayout.addView(tvSubtitle)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val containerSchedule = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(containerSchedule)
        contentLayout.addView(scrollView)
        root.addView(contentLayout)

        dialog.setView(root)
        dialog.setOnDismissListener {
            activeScheduleDialogRefresh = null
        }

        fun loadAndRenderSchedule() {
            containerSchedule.removeAllViews()
            val loadingTv = TextView(this).apply {
                text = "Memuat jadwal mengajar hari ini..."
                setTextColor(Color.parseColor("#64748B"))
                textSize = 13f
                setPadding(16, 24, 16, 24)
            }
            containerSchedule.addView(loadingTv)

            ApiClient.getClient(this).getTeacherTodayClassPeriods().enqueue(object : Callback<TeacherClassPeriodsResponse> {
                override fun onResponse(call: Call<TeacherClassPeriodsResponse>, response: Response<TeacherClassPeriodsResponse>) {
                    containerSchedule.removeAllViews()
                    val resp = response.body()
                    if (response.isSuccessful && resp != null) {
                        val items = resp.schedules ?: resp.data ?: emptyList()
                        if (items.isEmpty()) {
                            val emptyTv = TextView(this@TeacherMainActivity).apply {
                                text = "Tidak ada jadwal mengajar untuk Anda hari ini."
                                setTextColor(Color.parseColor("#64748B"))
                                textSize = 13f
                                setPadding(16, 24, 16, 24)
                            }
                            containerSchedule.addView(emptyTv)
                            return
                        }

                        for (item in items) {
                            val cardView = CardView(this@TeacherMainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, 8, 0, 8)
                                }
                                radius = 12f * resources.displayMetrics.density
                                cardElevation = 2f * resources.displayMetrics.density
                                setCardBackgroundColor(if (item.isBreak) Color.parseColor("#F1F5F9") else Color.parseColor("#F8FAFC"))
                            }

                            val itemLayout = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(24, 20, 24, 20)
                            }

                            val rowHeader = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }

                            val tvItemTitle = TextView(this@TeacherMainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                text = "JP ${item.periodIndex}: ${item.subjectName}"
                                setTextColor(if (item.isBreak) Color.parseColor("#64748B") else Color.parseColor("#0F172A"))
                                textSize = 14f
                                typeface = Typeface.DEFAULT_BOLD
                            }

                            val tvTime = TextView(this@TeacherMainActivity).apply {
                                text = "${item.startTime} - ${item.endTime}"
                                setTextColor(Color.parseColor("#2563EB"))
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                            }

                            rowHeader.addView(tvItemTitle)
                            rowHeader.addView(tvTime)
                            itemLayout.addView(rowHeader)

                            val tvDetails = TextView(this@TeacherMainActivity).apply {
                                text = "Kelas: ${item.className} • Ruang: ${item.roomName ?: "-"}"
                                setTextColor(Color.parseColor("#64748B"))
                                textSize = 11f
                                setPadding(0, 4, 0, 8)
                            }
                            itemLayout.addView(tvDetails)

                            if (!item.isBreak) {
                                if (item.isAlreadyIn || item.activeSession != null) {
                                    val timeStr = item.inTimeFormatted ?: item.activeSession?.inTime?.let {
                                        if (it.contains("T")) it.substringAfter("T").substring(0, 5) else it
                                    } ?: "Tercatat"
                                    val outCount = if (item.studentOutCount > 0) item.studentOutCount else (item.activeSession?.studentOutCount ?: 0)
                                    val totalStud = if (item.totalStudents > 0) item.totalStudents else (item.activeSession?.totalStudents ?: 30)

                                    val tvStatusIn = TextView(this@TeacherMainActivity).apply {
                                        text = "✔ Sudah IN ($timeStr) • Siswa OUT: $outCount/$totalStud"
                                        setTextColor(Color.parseColor("#059669"))
                                        textSize = 13f
                                        typeface = Typeface.DEFAULT_BOLD
                                        setPadding(0, 4, 0, 4)
                                    }
                                    itemLayout.addView(tvStatusIn)
                                } else {
                                    val nowCal = java.util.Calendar.getInstance()
                                    val curMinutes = nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE)

                                    var isTooEarly = false
                                    var isExpired = false
                                    if (item.startTime.contains(":") && item.endTime.contains(":")) {
                                        val sParts = item.startTime.split(":")
                                        val startMin = (sParts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (sParts.getOrNull(1)?.toIntOrNull() ?: 0)
                                        val eParts = item.endTime.split(":")
                                        val endMin = (eParts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (eParts.getOrNull(1)?.toIntOrNull() ?: 0)

                                        if (curMinutes < (startMin - 10)) {
                                            isTooEarly = true
                                        } else if (curMinutes > endMin) {
                                            isExpired = true
                                        }
                                    }

                                    val canClickIn = (item.canInNow != false) && !isTooEarly && !isExpired

                                    val btnIn = Button(this@TeacherMainActivity).apply {
                                        layoutParams = LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            (44 * resources.displayMetrics.density).toInt()
                                        )
                                        if (isTooEarly || item.timeStatus == "UPCOMING") {
                                            text = "⏳ Belum Waktunya (Mulai ${item.startTime} WIB)"
                                            textSize = 12f
                                            typeface = Typeface.DEFAULT_BOLD
                                            setTextColor(Color.parseColor("#94A3B8"))
                                            isEnabled = false
                                            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E2E8F0"))
                                        } else if (isExpired || item.timeStatus == "PAST") {
                                            text = "⌛ Jam Pelajaran Telah Berakhir (${item.endTime} WIB)"
                                            textSize = 12f
                                            typeface = Typeface.DEFAULT_BOLD
                                            setTextColor(Color.parseColor("#94A3B8"))
                                            isEnabled = false
                                            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F1F5F9"))
                                        } else {
                                            text = "1 TAP [ IN KELAS ]"
                                            textSize = 13f
                                            typeface = Typeface.DEFAULT_BOLD
                                            setTextColor(Color.WHITE)
                                            isEnabled = true
                                            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                                            setOnClickListener {
                                                handleTeacherInClassSession(item)
                                            }
                                        }
                                    }
                                    itemLayout.addView(btnIn)

                                    if (isTooEarly || item.timeStatus == "UPCOMING") {
                                        val tvNote = TextView(this@TeacherMainActivity).apply {
                                            text = "💡 Tombol IN aktif otomatis 10 menit sebelum jam ${item.startTime} WIB."
                                            setTextColor(Color.parseColor("#64748B"))
                                            textSize = 10.5f
                                            setPadding(0, 4, 0, 0)
                                        }
                                        itemLayout.addView(tvNote)
                                    }
                                }
                            }

                            cardView.addView(itemLayout)
                            containerSchedule.addView(cardView)
                        }
                    } else {
                        val emptyTv = TextView(this@TeacherMainActivity).apply {
                            text = "Tidak ada jadwal mengajar hari ini atau belum dibuat oleh Operator."
                            setTextColor(Color.parseColor("#64748B"))
                            textSize = 12f
                            setPadding(16, 16, 16, 16)
                        }
                        containerSchedule.addView(emptyTv)
                    }
                }

                override fun onFailure(call: Call<TeacherClassPeriodsResponse>, t: Throwable) {
                    containerSchedule.removeAllViews()
                    val errTv = TextView(this@TeacherMainActivity).apply {
                        text = "Gagal memuat jadwal: ${t.message}"
                        setTextColor(Color.parseColor("#EF4444"))
                        textSize = 12f
                        setPadding(16, 16, 16, 16)
                    }
                    containerSchedule.addView(errTv)
                }
            })
        }

        activeScheduleDialogRefresh = { loadAndRenderSchedule() }
        loadAndRenderScheduleFn = { loadAndRenderSchedule() }

        loadAndRenderSchedule()
        dialog.show()
    }

    private fun handleTeacherInClassSession(item: ClassPeriodItemDto) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                2001
            )
            Toast.makeText(this, "Izin GPS lokasi diperlukan untuk verifikasi kehadiran di dalam kelas (maksimal 10 meter).", Toast.LENGTH_LONG).show()
            return
        }

        val (lat, lng, isMock) = getDeviceLocation()
        val req = TeacherInSessionRequest(
            scheduleId = item.id,
            className = item.className,
            subjectName = item.subjectName,
            periodIndex = item.periodIndex,
            timeRange = "${item.startTime} - ${item.endTime}",
            endTime = item.endTime,
            lat = lat,
            lng = lng,
            isFakeGps = isMock
        )
        Toast.makeText(this, "Memverifikasi GPS & membuka sesi JP ${item.periodIndex}...", Toast.LENGTH_SHORT).show()

        ApiClient.getClient(this).teacherInClassSession(req).enqueue(object : Callback<BasicResponse> {
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@TeacherMainActivity, "✔ Berhasil IN! Sesi JP aktif & monitoring kelas menyala hijau.", Toast.LENGTH_LONG).show()
                    activeScheduleDialogRefresh?.invoke()
                } else {
                    val errorBody = try {
                        response.errorBody()?.string()?.let { errJson ->
                            org.json.JSONObject(errJson).optString("message", "")
                        }
                    } catch (e: Exception) { null }
                    val msg = if (!errorBody.isNullOrEmpty()) errorBody else response.body()?.message ?: "Gagal membuka sesi JP"

                    AlertDialog.Builder(this@TeacherMainActivity)
                        .setTitle("⛔ Presensi Kelas Ditolak")
                        .setMessage(msg)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton("Mengerti", null)
                        .show()
                }
            }

            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showTeacherSubjectAttendanceDialog() {
        val dialog = createFullscreenDialog()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val teachingSubj = sessionManager.getTeachingSubject().ifEmpty { "Mata Pelajaran" }
        val header = createFullscreenHeader(
            title = "📊 Rekap Presensi: $teachingSubj",
            subtitle = "Guru: ${sessionManager.getName()} • SMPN 1 Boyolangu",
            onClose = { dialog.dismiss() },
            actionBtnText = "📄 Unduh PDF",
            onActionClick = {
                com.school.smartcbt.utils.AttendancePdfHelper.showDownloadPdfDialog(
                    this@TeacherMainActivity,
                    defaultClassName = sessionManager.getClassName().ifEmpty { null },
                    title = "Laporan Rekap Presensi $teachingSubj"
                )
            }
        )
        root.addView(header)

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(0, 48, 0, 48)
        }
        contentLayout.addView(progress)
        root.addView(contentLayout)

        dialog.setView(root)
        dialog.show()

        ApiClient.getClient(this).getTeacherSubjectAttendanceRecap().enqueue(object : Callback<com.school.smartcbt.data.model.SubjectAttendanceResponse> {
            override fun onResponse(call: Call<com.school.smartcbt.data.model.SubjectAttendanceResponse>, response: Response<com.school.smartcbt.data.model.SubjectAttendanceResponse>) {
                contentLayout.removeView(progress)
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val subjects = body.subjects ?: emptyList()

                    val tvSub = TextView(this@TeacherMainActivity).apply {
                        text = "Statistik kehadiran siswa per rombel mengajar untuk bidang studi $teachingSubj (${subjects.size} Rombel Terdata)."
                        textSize = 12.5f
                        setTextColor(Color.parseColor("#64748B"))
                        setPadding(0, 0, 0, 16)
                    }
                    contentLayout.addView(tvSub)

                    if (subjects.isEmpty()) {
                        val emptyTv = TextView(this@TeacherMainActivity).apply {
                            text = "Belum ada rekap presensi kelas untuk bidang studi $teachingSubj."
                            textSize = 13f
                            setTextColor(Color.parseColor("#94A3B8"))
                            gravity = android.view.Gravity.CENTER
                            setPadding(0, 32, 0, 32)
                        }
                        contentLayout.addView(emptyTv)
                        return
                    }

                    val scroll = ScrollView(this@TeacherMainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    }

                    val listContainer = LinearLayout(this@TeacherMainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                    }

                    for (subj in subjects) {
                        val card = CardView(this@TeacherMainActivity).apply {
                            radius = 16f
                            cardElevation = 2f
                            setCardBackgroundColor(Color.WHITE)
                            useCompatPadding = true
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 0, 0, 12)
                            }
                        }

                        val inner = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(20, 18, 20, 18)
                        }

                        val headRow = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }

                        val tvSubjName = TextView(this@TeacherMainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            text = subj.subjectName
                            textSize = 15.5f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor("#1E293B"))
                        }

                        val tvPct = TextView(this@TeacherMainActivity).apply {
                            text = "${subj.percentage}%"
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(if (subj.percentage >= 85) Color.parseColor("#15803D") else Color.parseColor("#D97706"))
                        }

                        headRow.addView(tvSubjName)
                        headRow.addView(tvPct)

                        val tvTeacher = TextView(this@TeacherMainActivity).apply {
                            text = "Guru Pengampu: ${subj.teacherName ?: sessionManager.getName()} • Total: ${subj.totalSessions} Sesi KBM"
                            textSize = 12f
                            setTextColor(Color.parseColor("#64748B"))
                            setPadding(0, 4, 0, 10)
                        }

                        val statsRow = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            weightSum = 4f
                            setBackgroundColor(Color.parseColor("#F8FAFC"))
                            setPadding(8, 8, 8, 8)
                        }

                        fun createStatCol(label: String, value: Int, colorHex: String): LinearLayout {
                            return LinearLayout(this@TeacherMainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                orientation = LinearLayout.VERTICAL
                                gravity = android.view.Gravity.CENTER
                                val tvV = TextView(this@TeacherMainActivity).apply {
                                    text = value.toString()
                                    textSize = 13f
                                    setTypeface(null, Typeface.BOLD)
                                    setTextColor(Color.parseColor(colorHex))
                                }
                                val tvL = TextView(this@TeacherMainActivity).apply {
                                    text = label
                                    textSize = 9.5f
                                    setTextColor(Color.parseColor("#64748B"))
                                }
                                addView(tvV)
                                addView(tvL)
                            }
                        }

                        statsRow.addView(createStatCol("Hadir", subj.present, "#15803D"))
                        statsRow.addView(createStatCol("Sakit", subj.sick, "#DC2626"))
                        statsRow.addView(createStatCol("Izin", subj.permission, "#0284C7"))
                        statsRow.addView(createStatCol("Alpa", subj.truant, "#B91C1C"))

                        inner.addView(headRow)
                        inner.addView(tvTeacher)
                        inner.addView(statsRow)
                        card.addView(inner)
                        listContainer.addView(card)
                    }

                    scroll.addView(listContainer)
                    contentLayout.addView(scroll)
                } else {
                    Toast.makeText(this@TeacherMainActivity, "Gagal memuat rekap presensi per mapel", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<com.school.smartcbt.data.model.SubjectAttendanceResponse>, t: Throwable) {
                contentLayout.removeView(progress)
                Toast.makeText(this@TeacherMainActivity, sanitizeNetworkErrorMessage(t.message), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun sanitizeNetworkErrorMessage(msg: String?): String {
        if (msg.isNullOrEmpty()) return "Koneksi ke server sekolah terputus. Pastikan terhubung ke jaringan sekolah."
        val clean = msg.replace(Regex("""\b(?:https?://)?(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?(?:/[^\s]*)?\b"""), "Server Sekolah")
            .replace(Regex("""/[0-9.]+:[0-9]+"""), "Server Sekolah")
        return if (clean.contains("failed to connect", ignoreCase = true) ||
            clean.contains("timeout", ignoreCase = true) ||
            clean.contains("connection", ignoreCase = true) ||
            clean.contains("refused", ignoreCase = true) ||
            clean.contains("unreachable", ignoreCase = true)) {
            "Gagal terhubung ke server sekolah. Pastikan perangkat terhubung ke Wi-Fi / jaringan sekolah."
        } else {
            clean
        }
    }

    private fun showRoleSwitcherDialog() {
        val availableRoles = sessionManager.getAvailableRoles()
        val tugasTambahan = sessionManager.getTugasTambahan()
        val homeroomClass = sessionManager.getClassName()
        val subject = sessionManager.getTeachingSubject()?.uppercase() ?: ""

        val roleItems = mutableListOf<Triple<String, String, () -> Unit>>()

        // 1. Guru Pengampu
        roleItems.add(Triple("👨‍🏫 Guru Pengampu / Mapel", "Fokus kehadiran mengajar KBM per kelas yang diampu", {
            binding.tvRoleBadgeHeader.text = "👨‍🏫 Guru Pengampu"
            Toast.makeText(this, "Beralih ke mode Guru Pengampu", Toast.LENGTH_SHORT).show()
        }))

        // 2. Wali Kelas
        if (availableRoles.contains("WALI_KELAS") || (homeroomClass.isNotEmpty() && homeroomClass != "-")) {
            roleItems.add(Triple("⭐ Wali Kelas ($homeroomClass)", "Akses monitoring presensi, rekap, & izin siswa rombel", {
                if (binding.cardHomeroom.visibility == View.VISIBLE) {
                    binding.btnViewHomeroomStudents.performClick()
                } else {
                    Toast.makeText(this, "Rombel binaan: $homeroomClass", Toast.LENGTH_SHORT).show()
                }
            }))
        }

        // 3. Operator
        if (availableRoles.contains("OPERATOR") || tugasTambahan.contains("OPERATOR") || sessionManager.getRole().uppercase().contains("OPERATOR")) {
            roleItems.add(Triple("⚙️ Mode Operator Sekolah", "Panel kontrol akademik, broadcast massal, & manajemen CBT", {
                openOperatorDashboard()
            }))
        }

        // 4. Guru Piket
        roleItems.add(Triple("🛡️ Posko Guru Piket Hari Ini", "Pantau gerbang masuk, disposisi kelas kosong & guru izin", {
            showTeacherPiketCenterDialog()
        }))

        // 5. Guru PAI
        if (availableRoles.contains("PAI") || subject.contains("AGAMA") || subject.contains("PAI") || subject.contains("ISLAM")) {
            roleItems.add(Triple("🕌 Kontrol Mushola (Guru PAI)", "Kelola jadwal dan presensi sholat berjamaah mushola", {
                showPaiPrayerControlDialog()
            }))
        }

        // 6. Guru BK
        if (availableRoles.contains("COUNSELOR") || sessionManager.getRole().uppercase().contains("BK") || sessionManager.getRole().uppercase().contains("COUNSELOR")) {
            roleItems.add(Triple("🕊️ Portal Bimbingan Konseling (BK)", "Konseling siswa, verifikasi izin keluar/meja BK, & buku poin", {
                startActivity(Intent(this, BkActivity::class.java))
            }))
        }

        val dialog = createFullscreenDialog()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "🔄 Ganti Peran & Akses",
            subtitle = "Pilih modul fungsi tugas guru SMPN 1 Boyolangu",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        roleItems.forEach { (title, desc, action) ->
            val card = CardView(this).apply {
                radius = 12f * resources.displayMetrics.density
                cardElevation = 2f * resources.displayMetrics.density
                setCardBackgroundColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (12 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action.invoke()
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val p = (14 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }

            val infoBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(this).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
            }
            val tvDesc = TextView(this).apply {
                text = desc
                textSize = 11.5f
                setTextColor(Color.parseColor("#64748B"))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 4
                }
                layoutParams = lp
            }
            infoBox.addView(tvTitle)
            infoBox.addView(tvDesc)

            val tvArrow = TextView(this).apply {
                text = "➔"
                textSize = 16f
                setTextColor(Color.parseColor("#6366F1"))
                setTypeface(null, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
            }

            row.addView(infoBox)
            row.addView(tvArrow)
            card.addView(row)
            container.addView(card)
        }

        sv.addView(container)
        root.addView(sv)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun openOperatorDashboard() {
        val availableRoles = sessionManager.getAvailableRoles()
        val tugasTambahan = sessionManager.getTugasTambahan()
        val role = sessionManager.getRole().uppercase()
        val isOperator = availableRoles.contains("OPERATOR") || tugasTambahan.contains("OPERATOR") || role.contains("OPERATOR") || role.contains("ADMIN")

        if (isOperator) {
            startActivity(Intent(this, OperatorMainActivity::class.java))
        } else {
            AlertDialog.Builder(this)
                .setTitle("⚙️ Akses Operator Sekolah")
                .setMessage("Halaman Operator dikhususkan untuk guru yang mendapat tugas tambahan dari Admin Sekolah sebagai Operator.\n\nJika Anda ditugaskan sebagai Operator, hubungi Administrator untuk sinkronisasi wewenang akun.")
                .setPositiveButton("Mengerti", null)
                .show()
        }
    }

    private fun showTeacherLeaveMenuDialog() {
        val dialog = createFullscreenDialog()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "📝 Izin Guru & Disposisi",
            subtitle = "Pengajuan izin tidak hadir & penugasan mandiri siswa",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val leaveActions = listOf(
            Triple("➕ Buat Pengajuan Izin Baru", "Isi formulir izin tidak hadir, jadwal terdampak, & materi tugas mandiri", {
                showCreateTeacherLeaveDialog()
            }),
            Triple("📜 Riwayat Pengajuan Izin Saya", "Cek status persetujuan Kepala Sekolah/Operator & disposisi Guru Piket", {
                showMyTeacherLeavesDialog()
            })
        )

        leaveActions.forEach { (title, desc, action) ->
            val card = CardView(this).apply {
                radius = 12f * resources.displayMetrics.density
                cardElevation = 2f * resources.displayMetrics.density
                setCardBackgroundColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (12 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action.invoke()
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val p = (16 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }

            val infoBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(this).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
            }
            val tvDesc = TextView(this).apply {
                text = desc
                textSize = 11.5f
                setTextColor(Color.parseColor("#64748B"))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 4
                }
                layoutParams = lp
            }
            infoBox.addView(tvTitle)
            infoBox.addView(tvDesc)

            val tvArrow = TextView(this).apply {
                text = "➔"
                textSize = 16f
                setTextColor(Color.parseColor("#DB2777"))
                setTypeface(null, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
            }

            row.addView(infoBox)
            row.addView(tvArrow)
            card.addView(row)
            container.addView(card)
        }

        sv.addView(container)
        root.addView(sv)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showCreateTeacherLeaveDialog() {
        val sv = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvHeader = TextView(this).apply {
            text = "📝 Form Pengajuan Izin Guru"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 4)
        }
        container.addView(tvHeader)

        val tvSub = TextView(this).apply {
            text = "Setelah disetujui Operator, tugas mandiri akan otomatis diteruskan ke Posko Guru Piket untuk mendampingi kelas kosong."
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 14)
        }
        container.addView(tvSub)

        val tvCatLabel = TextView(this).apply {
            text = "Kategori Izin:"
            textSize = 12f
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, 0, 4)
        }
        container.addView(tvCatLabel)

        val categories = arrayOf(
            "SAKIT (Sakit dengan/tanpa surat dokter)",
            "DINAS_LUAR (Tugas Dinas / Undangan Resmi)",
            "URUSAN_PRIBADI (Urusan Keluarga / Pribadi)",
            "DISPENSASI (Dispensasi Khusus)"
        )
        val spCategory = Spinner(this).apply {
            adapter = ArrayAdapter(this@TeacherMainActivity, android.R.layout.simple_spinner_dropdown_item, categories)
        }
        container.addView(spCategory)

        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val etStartDate = EditText(this).apply {
            hint = "Tanggal Mulai (YYYY-MM-DD)"
            setText(todayStr)
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            layoutParams = lp
        }
        container.addView(etStartDate)

        val etEndDate = EditText(this).apply {
            hint = "Tanggal Selesai (YYYY-MM-DD)"
            setText(todayStr)
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8
            }
            layoutParams = lp
        }
        container.addView(etEndDate)

        val etClasses = EditText(this).apply {
            hint = "Kelas & Jam Kosong (Contoh: VII-A Jam 1-2, VII-C Jam 5-6)"
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            layoutParams = lp
        }
        container.addView(etClasses)

        val etTask = EditText(this).apply {
            hint = "Tugas Mandiri Siswa (Wajib diisi agar siswa tetap belajar terarah di bawah pengawasan Guru Piket)"
            setLines(3)
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            layoutParams = lp
        }
        container.addView(etTask)

        val etReason = EditText(this).apply {
            hint = "Keterangan / Alasan Izin secara lengkap"
            setLines(2)
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            layoutParams = lp
        }
        container.addView(etReason)

        sv.addView(container)

        AlertDialog.Builder(this)
            .setTitle("📝 Pengajuan Izin Guru")
            .setView(sv)
            .setPositiveButton("Kirim ke Operator") { _, _ ->
                val catRaw = spCategory.selectedItem.toString()
                val catCode = catRaw.substringBefore(" ")
                val sDate = etStartDate.text.toString().trim()
                val eDate = etEndDate.text.toString().trim()
                val classes = etClasses.text.toString().trim()
                val task = etTask.text.toString().trim()
                val reason = etReason.text.toString().trim()

                if (task.isEmpty() || reason.isEmpty()) {
                    Toast.makeText(this, "Harap isi tugas mandiri siswa dan alasan izin!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val body = mapOf(
                    "category" to catCode,
                    "startDate" to sDate,
                    "endDate" to eDate,
                    "reason" to reason,
                    "affectedSchedules" to classes,
                    "assignmentForStudents" to task
                )

                Toast.makeText(this, "Mengirim pengajuan izin...", Toast.LENGTH_SHORT).show()
                ApiClient.getClient(this).createTeacherLeave(body).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            AlertDialog.Builder(this@TeacherMainActivity)
                                .setTitle("✅ Izin Berhasil Diajukan")
                                .setMessage("Pengajuan izin Anda telah tercatat dan menunggu persetujuan Operator.\nSetelah disetujui, tugas mandiri akan otomatis diteruskan ke Posko Guru Piket.")
                                .setPositiveButton("Mengerti", null)
                                .show()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal mengajukan izin", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showMyTeacherLeavesDialog() {
        Toast.makeText(this, "Memuat riwayat izin saya...", Toast.LENGTH_SHORT).show()
        ApiClient.getClient(this).getMyTeacherLeaves().enqueue(object : Callback<TeacherLeaveResponse> {
            override fun onResponse(call: Call<TeacherLeaveResponse>, response: Response<TeacherLeaveResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val list = response.body()!!.leaves ?: emptyList()
                    val dialog = createFullscreenDialog()
                    val root = LinearLayout(this@TeacherMainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.parseColor("#F8FAFC"))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }

                    val header = createFullscreenHeader(
                        title = "📜 Riwayat Izin Guru",
                        subtitle = "Pengajuan Izin & Status Approval Operator",
                        onClose = { dialog.dismiss() }
                    )
                    root.addView(header)

                    val sv = ScrollView(this@TeacherMainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    }
                    val container = LinearLayout(this@TeacherMainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        val pad = (16 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                    }

                    if (list.isEmpty()) {
                        container.addView(TextView(this@TeacherMainActivity).apply {
                            text = "Belum ada riwayat pengajuan izin."
                            setTextColor(Color.parseColor("#94A3B8"))
                            textSize = 13f
                            setPadding(20, 30, 20, 30)
                            gravity = android.view.Gravity.CENTER
                        })
                    } else {
                        list.forEach { item ->
                            val card = CardView(this@TeacherMainActivity).apply {
                                radius = 12f * resources.displayMetrics.density
                                cardElevation = 2f * resources.displayMetrics.density
                                setCardBackgroundColor(Color.WHITE)
                                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    bottomMargin = 12
                                }
                                layoutParams = lp
                            }
                            val inner = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(20, 16, 20, 16)
                            }
                            val topRow = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }
                            val tvCat = TextView(this@TeacherMainActivity).apply {
                                text = "📝 ${item.category}"
                                textSize = 13f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor("#0F172A"))
                                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                layoutParams = lp
                            }
                            topRow.addView(tvCat)

                            val statusText = when (item.status) {
                                "APPROVED" -> "✅ DISETUJUI"
                                "REJECTED" -> "❌ DITOLAK"
                                else -> "⏳ PENDING"
                            }
                            val statusColor = when (item.status) {
                                "APPROVED" -> "#059669"
                                "REJECTED" -> "#DC2626"
                                else -> "#D97706"
                            }
                            val tvStatus = TextView(this@TeacherMainActivity).apply {
                                text = statusText
                                textSize = 10f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor(statusColor))
                                setBackgroundColor(Color.parseColor("#F1F5F9"))
                                setPadding(10, 4, 10, 4)
                            }
                            topRow.addView(tvStatus)
                            inner.addView(topRow)

                            val tvDates = TextView(this@TeacherMainActivity).apply {
                                text = "📅 ${item.startDate.take(10)} s/d ${item.endDate.take(10)}"
                                textSize = 11.5f
                                setTextColor(Color.parseColor("#64748B"))
                                setPadding(0, 4, 0, 0)
                            }
                            inner.addView(tvDates)

                            val tvReason = TextView(this@TeacherMainActivity).apply {
                                text = "Alasan: ${item.reason}"
                                textSize = 11.5f
                                setTextColor(Color.parseColor("#334155"))
                                setPadding(0, 4, 0, 0)
                            }
                            inner.addView(tvReason)

                            if (!item.assignmentForStudents.isNullOrEmpty()) {
                                val tvTask = TextView(this@TeacherMainActivity).apply {
                                    text = "📝 Tugas Mandiri: ${item.assignmentForStudents}"
                                    textSize = 11.5f
                                    setTextColor(Color.parseColor("#059669"))
                                    setPadding(0, 4, 0, 0)
                                }
                                inner.addView(tvTask)
                            }

                            card.addView(inner)
                            container.addView(card)
                        }
                    }

                    sv.addView(container)
                    root.addView(sv)
                    dialog.setContentView(root)
                    dialog.show()
                } else {
                    Toast.makeText(this@TeacherMainActivity, "Gagal memuat riwayat izin", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<TeacherLeaveResponse>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showPaiPrayerControlDialog() {
        Toast.makeText(this, "Memuat jadwal sholat...", Toast.LENGTH_SHORT).show()
        ApiClient.getClient(this).getTodayPrayerSchedule().enqueue(object : Callback<PrayerScheduleResponse> {
            override fun onResponse(call: Call<PrayerScheduleResponse>, response: Response<PrayerScheduleResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    openPaiPrayerMonitoringDialog(body)
                } else {
                    Toast.makeText(this@TeacherMainActivity, "Gagal memuat jadwal sholat", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<PrayerScheduleResponse>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun openPaiPrayerMonitoringDialog(initialSchedule: PrayerScheduleResponse) {
        val dialog = createFullscreenDialog()
        val density = resources.displayMetrics.density
        var currentScheduleDto = initialSchedule.schedule ?: PrayerClassScheduleDto(
            dayOfWeek = 1,
            dayName = initialSchedule.dayName ?: "SENIN",
            prayerType = "DHUHUR",
            classNames = "VII-A,VII-B,VII-C,VII-D,VII-E",
            wudhuTime = "11:45",
            scanStartTime = "12:00",
            scanEndTime = "12:30",
            notes = "Sholat Berjamaah di Masjid Sekolah"
        )
        var currentPrayerType = currentScheduleDto.prayerType ?: "DHUHUR"
        var selectedClass = "VII-A"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = createFullscreenHeader(
            title = "🕌 Kontrol Sholat & Jadwal Guru PAI",
            subtitle = "Hari ${initialSchedule.dayOfWeek} • Jadwal Sholat, Jam Wudhu & Scan Barcode Masjid",
            onClose = { dialog.dismiss() }
        )
        root.addView(header)

        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // 1. KARTU JADWAL & PENGUMUMAN SHOLAT DARI GURU PAI
        val cardSchedule = CardView(this).apply {
            radius = 14 * density
            cardElevation = 2f * density
            setCardBackgroundColor(Color.parseColor("#065F46"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (14 * density).toInt()
            }
            layoutParams = lp
        }

        val layoutSchedule = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
        }

        val tvSchedTitle = TextView(this).apply {
            text = "🕌 Jadwal Sholat Berjamaah Aktif"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        layoutSchedule.addView(tvSchedTitle)

        val tvSchedClasses = TextView(this).apply {
            text = "🏫 Rombel: ${currentScheduleDto.classNames ?: "Semua Rombel"}"
            setTextColor(Color.parseColor("#D1FAE5"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        layoutSchedule.addView(tvSchedClasses)

        val tvSchedTimes = TextView(this).apply {
            text = "💧 Mulai Wudhu: ${currentScheduleDto.wudhuTime ?: "11:45"} WIB • 📷 Scan Presensi: ${currentScheduleDto.scanStartTime ?: "12:00"} - ${currentScheduleDto.scanEndTime ?: "12:30"} WIB"
            setTextColor(Color.parseColor("#FDE047"))
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, (3 * density).toInt(), 0, 0)
        }
        layoutSchedule.addView(tvSchedTimes)

        val tvSchedNotes = TextView(this).apply {
            text = "📢 Informasi: ${currentScheduleDto.notes ?: "Siswa wajib hadir dan scan barcode statik di masjid sekolah."}"
            setTextColor(Color.parseColor("#A7F3D0"))
            textSize = 11f
            setPadding(0, (4 * density).toInt(), 0, (10 * density).toInt())
        }
        layoutSchedule.addView(tvSchedNotes)

        val btnEditSchedule = Button(this).apply {
            text = "⚙️ Atur Jadwal, Jam Wudhu & Scan Barcode"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#065F46"))
            setBackgroundColor(Color.parseColor("#FDE047"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * density).toInt())
            layoutParams = lp
        }
        layoutSchedule.addView(btnEditSchedule)
        cardSchedule.addView(layoutSchedule)
        container.addView(cardSchedule)

        // 2. KONTROL PILIHAN KELAS & TOGGLE DHUHUR / JUMAT
        val rowControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val tvLabelClass = TextView(this).apply {
            text = "Pilih Rombel:"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, 0, (8 * density).toInt(), 0)
        }
        rowControls.addView(tvLabelClass)

        val teachingList = sessionManager.getTeachingClasses().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val scheduledList = initialSchedule.scheduledClasses
        val defaultList = listOf(
            "VII-A", "VII-B", "VII-C", "VII-D", "VII-E", "VII-F", "VII-G", "VII-H", "VII-I", "VII-J", "VII-K",
            "VIII-A", "VIII-B", "VIII-C", "VIII-D", "VIII-E", "VIII-F", "VIII-G", "VIII-H", "VIII-I", "VIII-J", "VIII-K",
            "IX-A", "IX-B", "IX-C", "IX-D", "IX-E", "IX-F", "IX-G", "IX-H", "IX-I", "IX-J", "IX-K"
        )
        val combinedClasses = (teachingList + scheduledList + defaultList).distinct().filter { it != "ALL_MALE" && it != "-" }
        val classesList = if (combinedClasses.isNotEmpty()) combinedClasses else defaultList
        selectedClass = classesList.firstOrNull() ?: "VII-A"

        val spClass = Spinner(this).apply {
            adapter = ArrayAdapter(this@TeacherMainActivity, android.R.layout.simple_spinner_dropdown_item, classesList)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        rowControls.addView(spClass)
        container.addView(rowControls)

        // Toggle Jenis Ibadah: Sholat Dhuhur vs Sholat Jumat
        val rowPrayerType = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (38 * density).toInt()).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val btnDhuhur = Button(this).apply {
            text = "Sholat Dhuhur"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = (4 * density).toInt()
            }
            layoutParams = lp
        }
        val btnJumat = Button(this).apply {
            text = "Sholat Jumat"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = (4 * density).toInt()
            }
            layoutParams = lp
        }

        fun updatePrayerTypeButtons() {
            if (currentPrayerType == "DHUHUR") {
                btnDhuhur.setBackgroundColor(Color.parseColor("#059669"))
                btnDhuhur.setTextColor(Color.WHITE)
                btnJumat.setBackgroundColor(Color.parseColor("#E2E8F0"))
                btnJumat.setTextColor(Color.parseColor("#475569"))
            } else {
                btnJumat.setBackgroundColor(Color.parseColor("#059669"))
                btnJumat.setTextColor(Color.WHITE)
                btnDhuhur.setBackgroundColor(Color.parseColor("#E2E8F0"))
                btnDhuhur.setTextColor(Color.parseColor("#475569"))
            }
        }
        updatePrayerTypeButtons()
        rowPrayerType.addView(btnDhuhur)
        rowPrayerType.addView(btnJumat)
        container.addView(rowPrayerType)

        // 3. BARIS AKSI CEPAT: [⚡ TANDAI SEMUA HADIR] & [📥 CETAK / UNDUH REKAP PDF]
        val rowActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (42 * density).toInt()).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val btnBatchAll = Button(this).apply {
            text = "⚡ Tandai Semua Hadir"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0284C7"))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = (4 * density).toInt()
            }
            layoutParams = lp
        }

        val btnExportPdf = Button(this).apply {
            text = "📥 Unduh Rekap PDF"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7C3AED"))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = (4 * density).toInt()
            }
            layoutParams = lp
        }
        rowActions.addView(btnBatchAll)
        rowActions.addView(btnExportPdf)
        container.addView(rowActions)

        // 4. STATISTIK RINGKASAN
        val tvSum = TextView(this).apply {
            text = "📊 Memuat statistik..."
            setTextColor(Color.parseColor("#065F46"))
            setBackgroundColor(Color.parseColor("#D1FAE5"))
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (10 * density).toInt()
            }
            layoutParams = lp
        }
        container.addView(tvSum)

        val studentsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(studentsContainer)

        fun loadMonitoring(className: String) {
            studentsContainer.removeAllViews()
            val tvLoading = TextView(this).apply {
                text = "Memuat presensi sholat kelas $className ($currentPrayerType)..."
                setTextColor(Color.parseColor("#64748B"))
                textSize = 12f
                val p = (20 * density).toInt()
                setPadding(p, p, p, p)
                gravity = Gravity.CENTER
            }
            studentsContainer.addView(tvLoading)

            ApiClient.getClient(this).getPaiMonitoringByClass(className = className, prayerType = currentPrayerType).enqueue(object : Callback<PrayerMonitoringResponse> {
                override fun onResponse(call: Call<PrayerMonitoringResponse>, response: Response<PrayerMonitoringResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        studentsContainer.removeAllViews()
                        val data = response.body()!!
                        val summary = data.summary
                        val students = data.students ?: emptyList()

                        data.schedule?.let { s ->
                            currentScheduleDto = s
                            tvSchedClasses.text = "🏫 Rombel: ${s.classNames ?: "Semua Rombel"}"
                            tvSchedTimes.text = "💧 Mulai Wudhu: ${s.wudhuTime ?: "11:45"} WIB • 📷 Scan Presensi: ${s.scanStartTime ?: "12:00"} - ${s.scanEndTime ?: "12:30"} WIB"
                            tvSchedNotes.text = "📢 Informasi: ${s.notes ?: "Siswa wajib hadir dan scan barcode statik di masjid sekolah."}"
                        }

                        tvSum.text = "📊 Total: ${summary?.totalStudents ?: students.size} • Berjamaah: ${summary?.berjamaah ?: 0} • Haid: ${summary?.haid ?: 0} • Belum: ${summary?.belum ?: 0}"

                        if (students.isEmpty()) {
                            val tvEmpty = TextView(this@TeacherMainActivity).apply {
                                text = "Tidak ada siswa terdaftar pada kelas $className."
                                setTextColor(Color.parseColor("#94A3B8"))
                                textSize = 13f
                                gravity = Gravity.CENTER
                                val p = (24 * density).toInt()
                                setPadding(p, p, p, p)
                            }
                            studentsContainer.addView(tvEmpty)
                            return
                        }

                        students.forEach { s ->
                            val sCard = CardView(this@TeacherMainActivity).apply {
                                radius = 12f * density
                                cardElevation = 1.5f * density
                                setCardBackgroundColor(Color.WHITE)
                                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    topMargin = (8 * density).toInt()
                                }
                                layoutParams = lp
                            }
                            val sInner = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                val p = (14 * density).toInt()
                                setPadding(p, p, p, p)
                            }
                            val row = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            }
                            val isFemale = s.isFemale || s.gender?.uppercase() == "P" || s.gender?.uppercase() == "FEMALE"
                            val icon = if (isFemale) "👧" else "👦"
                            val tvSName = TextView(this@TeacherMainActivity).apply {
                                text = "$icon ${s.studentName} (${s.nisn ?: "-"})"
                                setTextColor(Color.parseColor("#0F172A"))
                                textSize = 12.5f
                                setTypeface(null, Typeface.BOLD)
                                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                layoutParams = lp
                            }
                            row.addView(tvSName)

                            val badgeColor = when (s.status) {
                                "SHOLAT_BERJAMAAH", "BERJAMAAH" -> "#059669"
                                "BERHALANGAN_HAID" -> "#EC4899"
                                "ALPHA" -> "#DC2626"
                                else -> "#94A3B8"
                            }
                            val badgeBg = when (s.status) {
                                "SHOLAT_BERJAMAAH", "BERJAMAAH" -> "#DCFCE7"
                                "BERHALANGAN_HAID" -> "#FCE7F3"
                                "ALPHA" -> "#FEE2E2"
                                else -> "#F1F5F9"
                            }
                            val badgeText = when (s.status) {
                                "SHOLAT_BERJAMAAH", "BERJAMAAH" -> "🕌 Hadir"
                                "BERHALANGAN_HAID" -> "🌸 Haid"
                                "ALPHA" -> "❌ Alpha"
                                else -> "⏳ Belum"
                            }
                            val tvBadge = TextView(this@TeacherMainActivity).apply {
                                text = badgeText
                                textSize = 10f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor(badgeColor))
                                setBackgroundColor(Color.parseColor(badgeBg))
                                val hp = (8 * density).toInt()
                                val vp = (3 * density).toInt()
                                setPadding(hp, vp, hp, vp)
                            }
                            row.addView(tvBadge)
                            sInner.addView(row)

                            val actRow = LinearLayout(this@TeacherMainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (34 * density).toInt()).apply {
                                    topMargin = (8 * density).toInt()
                                }
                                layoutParams = lp
                            }

                            val btnHadir = Button(this@TeacherMainActivity).apply {
                                text = "✅ Hadir"
                                textSize = 10f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#059669"))
                                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                                    marginEnd = (4 * density).toInt()
                                }
                                layoutParams = lp
                                setOnClickListener {
                                    markStudentPrayer(s.studentId, "SHOLAT_BERJAMAAH", className, currentPrayerType) { loadMonitoring(className) }
                                }
                            }
                            actRow.addView(btnHadir)

                            if (isFemale) {
                                val btnHaid = Button(this@TeacherMainActivity).apply {
                                    text = "🌸 Haid"
                                    textSize = 10f
                                    setTypeface(null, Typeface.BOLD)
                                    setTextColor(Color.WHITE)
                                    setBackgroundColor(Color.parseColor("#EC4899"))
                                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                                        marginEnd = (4 * density).toInt()
                                    }
                                    layoutParams = lp
                                    setOnClickListener {
                                        markStudentPrayer(s.studentId, "BERHALANGAN_HAID", className, currentPrayerType) { loadMonitoring(className) }
                                    }
                                }
                                actRow.addView(btnHaid)
                            }

                            val btnAlpha = Button(this@TeacherMainActivity).apply {
                                text = "❌ Alpha"
                                textSize = 10f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#DC2626"))
                                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f)
                                layoutParams = lp
                                setOnClickListener {
                                    markStudentPrayer(s.studentId, "ALPHA", className, currentPrayerType) { loadMonitoring(className) }
                                }
                            }
                            actRow.addView(btnAlpha)

                            sInner.addView(actRow)
                            sCard.addView(sInner)
                            studentsContainer.addView(sCard)
                        }
                    } else {
                        studentsContainer.removeAllViews()
                        studentsContainer.addView(TextView(this@TeacherMainActivity).apply {
                            text = "Gagal memuat monitoring kelas $className"
                            setTextColor(Color.parseColor("#DC2626"))
                            val p = (16 * density).toInt()
                            setPadding(p, p, p, p)
                        })
                    }
                }
                override fun onFailure(call: Call<PrayerMonitoringResponse>, t: Throwable) {
                    studentsContainer.removeAllViews()
                    studentsContainer.addView(TextView(this@TeacherMainActivity).apply {
                        text = "Koneksi terputus: ${t.message}"
                        setTextColor(Color.parseColor("#DC2626"))
                        val p = (16 * density).toInt()
                        setPadding(p, p, p, p)
                    })
                }
            })
        }

        // Action Listener for Dhuhur & Jumat Toggle
        btnDhuhur.setOnClickListener {
            currentPrayerType = "DHUHUR"
            updatePrayerTypeButtons()
            loadMonitoring(selectedClass)
        }
        btnJumat.setOnClickListener {
            currentPrayerType = "JUMAT"
            updatePrayerTypeButtons()
            loadMonitoring(selectedClass)
        }

        // Action Listener for Batch Mark All
        btnBatchAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Tandai Semua Hadir Sholat?")
                .setMessage("Apakah Anda yakin ingin menandai seluruh siswa kelas $selectedClass hadir sholat berjamaah?\n\n(Catatan: Siswi yang tercatat berhalangan haid tidak akan ditimpa).")
                .setPositiveButton("Ya, Tandai Hadir") { _, _ ->
                    val body = mapOf(
                        "className" to selectedClass,
                        "prayerType" to currentPrayerType,
                        "status" to "SHOLAT_BERJAMAAH"
                    )
                    ApiClient.getClient(this).batchPaiMarkStudentPrayer(body).enqueue(object : Callback<BasicResponse> {
                        override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                            if (response.isSuccessful) {
                                Toast.makeText(this@TeacherMainActivity, "✅ Seluruh siswa berhasil ditandai hadir berjamaah!", Toast.LENGTH_SHORT).show()
                                loadMonitoring(selectedClass)
                            } else {
                                Toast.makeText(this@TeacherMainActivity, "Gagal memproses presensi massal", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                            Toast.makeText(this@TeacherMainActivity, "Error: ${t.message}", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        // Action Listener for Export PDF
        btnExportPdf.setOnClickListener {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val options = arrayOf(
                "📋 Rekap Kelas $selectedClass",
                "🏫 Rekap Seluruh Kelas (ALL)"
            )
            AlertDialog.Builder(this)
                .setTitle("📄 Pilih Lingkup Rekap Sholat")
                .setItems(options) { _, which ->
                    val targetClass = if (which == 0) selectedClass else "ALL"
                    com.school.smartcbt.utils.AttendancePdfHelper.showPrayerPdfDialog(
                        activity = this@TeacherMainActivity,
                        className = targetClass,
                        date = todayStr,
                        prayerType = currentPrayerType
                    )
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        // Action Listener for Edit Schedule Dialog
        btnEditSchedule.setOnClickListener {
            showEditPrayerScheduleDialog(currentScheduleDto) { updated ->
                currentScheduleDto = updated
                tvSchedClasses.text = "🏫 Rombel: ${updated.classNames ?: "Semua Rombel"}"
                tvSchedTimes.text = "💧 Mulai Wudhu: ${updated.wudhuTime ?: "11:45"} WIB • 📷 Scan Presensi: ${updated.scanStartTime ?: "12:00"} - ${updated.scanEndTime ?: "12:30"} WIB"
                tvSchedNotes.text = "📢 Informasi: ${updated.notes ?: "Siswa wajib hadir dan scan barcode statik di masjid sekolah."}"
                loadMonitoring(selectedClass)
            }
        }

        spClass.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedClass = classesList[position]
                loadMonitoring(selectedClass)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        sv.addView(container)
        root.addView(sv)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showEditPrayerScheduleDialog(current: PrayerClassScheduleDto, onSaved: (PrayerClassScheduleDto) -> Unit) {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * density).toInt()
            setPadding(p, p, p, p)
        }

        val tvTitle = TextView(this).apply {
            text = "⚙️ Atur Jadwal & Waktu Sholat Masjid"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }
        root.addView(tvTitle)

        // 1. Hari
        val tvDayLabel = TextView(this).apply {
            text = "Pilih Hari:"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(tvDayLabel)
        val dayOptions = listOf("Senin (Hari ke-1)", "Selasa (Hari ke-2)", "Rabu (Hari ke-3)", "Kamis (Hari ke-4)", "Jumat (Hari ke-5)")
        val spDay = Spinner(this).apply {
            adapter = ArrayAdapter(this@TeacherMainActivity, android.R.layout.simple_spinner_dropdown_item, dayOptions)
            val selDay = Math.max(1, Math.min(5, current.dayOfWeek)) - 1
            setSelection(selDay)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            }
            layoutParams = lp
        }
        root.addView(spDay)

        // 2. Jenis Sholat
        val tvPrayerTypeLabel = TextView(this).apply {
            text = "Jenis Sholat:"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(tvPrayerTypeLabel)
        val typeOptions = listOf("DHUHUR", "JUMAT")
        val spType = Spinner(this).apply {
            adapter = ArrayAdapter(this@TeacherMainActivity, android.R.layout.simple_spinner_dropdown_item, typeOptions)
            if (current.prayerType == "JUMAT") setSelection(1) else setSelection(0)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            }
            layoutParams = lp
        }
        root.addView(spType)

        // 3. Rombel Terjadwal
        val tvClassesLabel = TextView(this).apply {
            text = "Rombel Kelas Terjadwal (Pisahkan dengan koma):"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(tvClassesLabel)
        val etClasses = EditText(this).apply {
            setText(current.classNames ?: "VII-A,VII-B,VII-C,VII-D,VII-E")
            textSize = 12f
            hint = "Contoh: VII-A,VII-B,VII-C atau ALL_MALE"
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            }
            layoutParams = lp
        }
        root.addView(etClasses)

        // 4. Jam Wudhu & Jam Scan
        val rowTimes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            }
            layoutParams = lp
        }

        val colWudhu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * density).toInt()
            }
            layoutParams = lp
        }
        val tvWudhu = TextView(this).apply {
            text = "Mulai Wudhu:"
            textSize = 11f
            setTextColor(Color.parseColor("#475569"))
            setTypeface(null, Typeface.BOLD)
        }
        val etWudhu = EditText(this).apply {
            setText(current.wudhuTime ?: "11:45")
            textSize = 12f
            hint = "11:45"
        }
        colWudhu.addView(tvWudhu)
        colWudhu.addView(etWudhu)
        rowTimes.addView(colWudhu)

        val colScanStart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * density).toInt()
                marginStart = (4 * density).toInt()
            }
            layoutParams = lp
        }
        val tvScanStart = TextView(this).apply {
            text = "Mulai Scan:"
            textSize = 11f
            setTextColor(Color.parseColor("#475569"))
            setTypeface(null, Typeface.BOLD)
        }
        val etScanStart = EditText(this).apply {
            setText(current.scanStartTime ?: "12:00")
            textSize = 12f
            hint = "12:00"
        }
        colScanStart.addView(tvScanStart)
        colScanStart.addView(etScanStart)
        rowTimes.addView(colScanStart)

        val colScanEnd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * density).toInt()
            }
            layoutParams = lp
        }
        val tvScanEnd = TextView(this).apply {
            text = "Batas Scan:"
            textSize = 11f
            setTextColor(Color.parseColor("#475569"))
            setTypeface(null, Typeface.BOLD)
        }
        val etScanEnd = EditText(this).apply {
            setText(current.scanEndTime ?: "12:30")
            textSize = 12f
            hint = "12:30"
        }
        colScanEnd.addView(tvScanEnd)
        colScanEnd.addView(etScanEnd)
        rowTimes.addView(colScanEnd)
        root.addView(rowTimes)

        // 5. Catatan / Pengumuman
        val tvNotesLabel = TextView(this).apply {
            text = "Catatan / Pengumuman untuk Siswa & Ortu:"
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(tvNotesLabel)
        val etNotes = EditText(this).apply {
            setText(current.notes ?: "Sholat Berjamaah di Masjid Sekolah")
            textSize = 12f
            hint = "Contoh: Siswa wajib membawa sajadah dari kelas masing-masing"
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
        }
        root.addView(etNotes)

        AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("💾 Simpan & Sinkronkan") { _, _ ->
                val dayIndex = spDay.selectedItemPosition + 1
                val prayerType = typeOptions[spType.selectedItemPosition]
                val classNames = etClasses.text.toString().trim()
                val wudhuTime = etWudhu.text.toString().trim().ifEmpty { "11:45" }
                val scanStartTime = etScanStart.text.toString().trim().ifEmpty { "12:00" }
                val scanEndTime = etScanEnd.text.toString().trim().ifEmpty { "12:30" }
                val notes = etNotes.text.toString().trim()

                if (classNames.isEmpty()) {
                    Toast.makeText(this, "Daftar rombel kelas wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val dayNames = listOf("SENIN", "SELASA", "RABU", "KAMIS", "JUMAT")
                val dayName = dayNames.getOrElse(dayIndex - 1) { "HARI" }

                val body = mapOf(
                    "dayOfWeek" to dayIndex,
                    "dayName" to dayName,
                    "prayerType" to prayerType,
                    "classNames" to classNames,
                    "wudhuTime" to wudhuTime,
                    "scanStartTime" to scanStartTime,
                    "scanEndTime" to scanEndTime,
                    "notes" to notes
                )

                ApiClient.getClient(this).setPrayerClassSchedule(body).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@TeacherMainActivity, "✅ Jadwal sholat berhasil disimpan dan disinkronkan!", Toast.LENGTH_SHORT).show()
                            val updatedDto = current.copy(
                                dayOfWeek = dayIndex,
                                dayName = dayName,
                                prayerType = prayerType,
                                classNames = classNames,
                                wudhuTime = wudhuTime,
                                scanStartTime = scanStartTime,
                                scanEndTime = scanEndTime,
                                notes = notes
                            )
                            onSaved(updatedDto)
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal menyimpan jadwal", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun markStudentPrayer(studentId: String, status: String, className: String, prayerType: String = "DHUHUR", onDone: () -> Unit) {
        val body = mapOf(
            "studentId" to studentId,
            "status" to status,
            "prayerType" to prayerType,
            "notes" to "Dicatat oleh Guru PAI"
        )
        ApiClient.getClient(this).paiMarkStudentPrayer(body).enqueue(object : Callback<BasicResponse> {
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                if (response.isSuccessful) {
                    Toast.makeText(this@TeacherMainActivity, "Presensi sholat diperbarui", Toast.LENGTH_SHORT).show()
                    onDone()
                } else {
                    val err = response.errorBody()?.string() ?: "Gagal mencatat presensi"
                    Toast.makeText(this@TeacherMainActivity, err, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ========================================================
    // MODUL ESTAFET SESI MENGAJAR GURU (ROOM HANDOVER NFC/QR)
    // ========================================================

    private fun enableNfcForegroundDispatch() {
        try {
            if (nfcAdapter?.isEnabled == true) {
                val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, flags)
                val filters = arrayOf(
                    android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED),
                    android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED),
                    android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED)
                )
                nfcAdapter?.enableForegroundDispatch(this, pendingIntent, filters, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun disableNfcForegroundDispatch() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleNfcIntent(intent: Intent) {
        val action = intent.action
        if (android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED == action ||
            android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED == action ||
            android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG, android.nfc.Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG)
            }
            if (tag != null) {
                val tagId = tag.id
                val nfcUid = tagId.joinToString("") { "%02X".format(it) }
                Toast.makeText(this, "📱 NFC Terdeteksi: $nfcUid. Memproses...", Toast.LENGTH_SHORT).show()
                handleTeacherRoomCheckIn(nfcTagUid = nfcUid)
            }
        }
    }

    private fun loadCurrentTeachingSession() {
        ApiClient.getClient(this).getCurrentTeacherSession().enqueue(object : Callback<TeacherCurrentSessionResponse> {
            override fun onResponse(call: Call<TeacherCurrentSessionResponse>, response: Response<TeacherCurrentSessionResponse>) {
                val body = response.body()
                if (response.isSuccessful && body != null && body.hasActiveSession && body.session != null) {
                    activeTeachingSession = body.session
                    updateTeachingSessionUi(body.session)
                } else {
                    activeTeachingSession = null
                    updateTeachingSessionUi(null)
                }
            }

            override fun onFailure(call: Call<TeacherCurrentSessionResponse>, t: Throwable) {
                // Keep existing local state on network error
            }
        })
    }

    private fun updateTeachingSessionUi(session: TeachingSessionDto?) {
        if (session != null) {
            // State: ACTIVE KBM
            binding.iconTeachingSessionContainer.setBackgroundResource(com.school.smartcbt.R.drawable.bg_icon_green)
            binding.tvTeachingSessionIcon.text = "🏫"
            binding.tvTeachingSessionBadge.text = "🟢 SEDANG BERLANGSUNG"
            binding.tvTeachingSessionBadge.setBackgroundResource(com.school.smartcbt.R.drawable.bg_badge_emerald)
            binding.tvTeachingSessionBadge.setTextColor(Color.parseColor("#10B981"))

            val roomDisplay = session.room?.roomName ?: "Ruang Kelas ${session.className}"
            binding.tvTeachingSessionTitle.text = "$roomDisplay • ${session.subjectName}"

            val inTimeStr = try {
                if (!session.checkInTime.isNullOrEmpty()) {
                    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    val d = parser.parse(session.checkInTime.substring(0, minOf(19, session.checkInTime.length)))
                    if (d != null) SimpleDateFormat("HH:mm", Locale("id", "ID")).format(d) + " WIB" else session.checkInTime
                } else {
                    SimpleDateFormat("HH:mm", Locale("id", "ID")).format(Date()) + " WIB"
                }
            } catch (e: Exception) {
                session.checkInTime ?: "-"
            }

            binding.tvTeachingSessionMeta.text = "Jam Masuk: $inTimeStr • Presensi Siswa AKTIF di APK"

            // Live Attendance Numbers
            binding.layoutSessionStudentStats.visibility = View.VISIBLE
            val present = session.presentCount ?: 0
            val truant = session.truantCount ?: 0
            val sickPermit = (session.sickCount ?: 0) + (session.permitCount ?: 0)
            val total = session.studentCount ?: 0
            val unchecked = maxOf(0, total - (present + truant + sickPermit))

            binding.tvSessionPresentCount.text = "$present"
            binding.tvSessionTruantCount.text = "$truant"
            binding.tvSessionSickPermitCount.text = "$sickPermit"
            binding.tvSessionUncheckedCount.text = "$unchecked"

            binding.layoutSessionStandbyActions.visibility = View.GONE
            binding.layoutSessionActiveActions.visibility = View.VISIBLE
        } else {
            // State: STANDBY
            binding.iconTeachingSessionContainer.setBackgroundResource(com.school.smartcbt.R.drawable.bg_icon_indigo)
            binding.tvTeachingSessionIcon.text = "🏫"
            binding.tvTeachingSessionBadge.text = "STANDBY"
            binding.tvTeachingSessionBadge.setBackgroundResource(com.school.smartcbt.R.drawable.bg_btn_amber_rounded)
            binding.tvTeachingSessionBadge.setTextColor(Color.parseColor("#92400E"))

            binding.tvTeachingSessionTitle.text = "Belum Ada Sesi Mengajar Aktif"
            binding.tvTeachingSessionMeta.text = "Tap NFC atau scan QR di meja/pintu kelas untuk membuka KBM & mengaktifkan presensi siswa."

            binding.layoutSessionStudentStats.visibility = View.GONE
            binding.layoutSessionStandbyActions.visibility = View.VISIBLE
            binding.layoutSessionActiveActions.visibility = View.GONE
        }
    }

    private fun promptRoomCheckInMethod() {
        val options = arrayOf(
            "📷 Pindai QR Code Ruang Kelas (Kamera)",
            "🏷️ Pilih Ruang Kelas dari Daftar (Cepat)",
            "📱 Info Tap NFC Fisik (Stiker Pintu/Meja)"
        )

        AlertDialog.Builder(this)
            .setTitle("🏫 Masuk Sesi Mengajar Ruang Kelas")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(this, com.school.smartcbt.ScannerActivity::class.java).apply {
                            putExtra(com.school.smartcbt.ScannerActivity.EXTRA_SCAN_MODE, com.school.smartcbt.ScannerActivity.MODE_ROOM_HANDOVER)
                        }
                        roomScanLauncher.launch(intent)
                    }
                    1 -> showManualSelectRoomDialog()
                    2 -> {
                        val nfcStatus = if (nfcAdapter == null) "Perangkat tidak memiliki sensor NFC."
                        else if (!nfcAdapter!!.isEnabled) "NFC belum aktif di Pengaturan HP Anda."
                        else "Sensor NFC siap aktif!"

                        AlertDialog.Builder(this)
                            .setTitle("📱 Tap NFC Ruang Kelas")
                            .setMessage("Cukup dekatkan bodi belakang ponsel Anda ke stiker NFC di pintu atau meja guru ruang kelas.\n\nStatus NFC: $nfcStatus\n\nJika ponsel tidak mendukung NFC, silakan gunakan pemindai QR Code kamera atau menu 'Pilih Ruang Kelas'.")
                            .setPositiveButton("Buka Scan Kamera") { _, _ ->
                                val intent = Intent(this, com.school.smartcbt.ScannerActivity::class.java).apply {
                                    putExtra(com.school.smartcbt.ScannerActivity.EXTRA_SCAN_MODE, com.school.smartcbt.ScannerActivity.MODE_ROOM_HANDOVER)
                                }
                                roomScanLauncher.launch(intent)
                            }
                            .setNeutralButton("Pilih Kelas") { _, _ -> showManualSelectRoomDialog() }
                            .setNegativeButton("Tutup", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun handleTeacherRoomCheckIn(
        nfcTagUid: String? = null,
        qrSecretToken: String? = null,
        roomCode: String? = null,
        forceHandover: Boolean = false
    ) {
        val assignedSubject = sessionManager.getTeachingSubject().ifEmpty { "Mata Pelajaran" }
        val req = TeacherSessionCheckInRequest(
            roomCode = roomCode,
            nfcTagUid = nfcTagUid,
            qrSecretToken = qrSecretToken,
            forceHandover = forceHandover,
            subjectName = assignedSubject
        )

        Toast.makeText(this, "Memproses identifikasi ruang kelas...", Toast.LENGTH_SHORT).show()

        ApiClient.getClient(this).checkInTeachingSession(req).enqueue(object : Callback<TeacherSessionCheckInResponse> {
            override fun onResponse(call: Call<TeacherSessionCheckInResponse>, response: Response<TeacherSessionCheckInResponse>) {
                val resp = response.body()
                if (response.isSuccessful && resp != null && resp.success) {
                    activeTeachingSession = resp.session
                    updateTeachingSessionUi(resp.session)
                    loadCurrentTeachingSession()

                    val roomName = resp.room?.roomName ?: resp.session?.className ?: "Ruang Kelas"
                    val timeStr = SimpleDateFormat("HH:mm", Locale("id", "ID")).format(Date())

                    AlertDialog.Builder(this@TeacherMainActivity)
                        .setTitle("🏫 Sesi KBM Terbuka ($timeStr WIB)")
                        .setMessage("✅ Berhasil Check-in di $roomName untuk mata pelajaran ${resp.session?.subjectName}!\n\nPresensi mandiri siswa di kelas ini telah AKTIF seketika. Siswa dapat langsung melakukan presensi dari aplikasi mobile mereka.")
                        .setIcon(android.R.drawable.ic_dialog_info)
                        .setPositiveButton("👥 Cek Presensi Siswa") { _, _ ->
                            showTeacherManageAttendanceDialog()
                        }
                        .setNegativeButton("Selesai", null)
                        .show()
                } else if (response.code() == 409) {
                    // Safety Net: Lingering session
                    val rawErr = response.errorBody()?.string() ?: ""
                    var roomName = "Kelas Sebelumnya"
                    var startTime = "-"
                    var msg = "Anda masih memiliki sesi mengajar aktif di ruangan lain."
                    try {
                        val json = org.json.JSONObject(rawErr)
                        msg = json.optString("message", msg)
                        val activeSess = json.optJSONObject("activeSession")
                        if (activeSess != null) {
                            roomName = activeSess.optString("roomName", roomName)
                            startTime = activeSess.optString("startTimeStr", startTime)
                        }
                    } catch (e: Exception) {}

                    AlertDialog.Builder(this@TeacherMainActivity)
                        .setTitle("⚠️ Estafet Ruangan: Sesi Lama Belum Ditutup")
                        .setMessage("$msg\n\nApakah Anda ingin menutup sesi di kelas sebelumnya secara otomatis (Auto-Handover) dan membuka sesi di kelas baru ini?")
                        .setPositiveButton("Ya, Estafet & Tutup Sesi Lama") { _, _ ->
                            handleTeacherRoomCheckIn(
                                nfcTagUid = nfcTagUid,
                                qrSecretToken = qrSecretToken,
                                roomCode = roomCode,
                                forceHandover = true
                            )
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                } else if (response.code() == 422) {
                    // Anti-salah kamar
                    val rawErr = response.errorBody()?.string() ?: ""
                    var msg = "Ruangan tidak sesuai dengan jadwal KBM yang Anda ampu."
                    try {
                        val json = org.json.JSONObject(rawErr)
                        msg = json.optString("message", msg)
                    } catch (e: Exception) {}

                    AlertDialog.Builder(this@TeacherMainActivity)
                        .setTitle("⚠️ Ruang Tidak Sesuai Jadwal")
                        .setMessage(msg)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton("Mengerti", null)
                        .show()
                } else {
                    val rawErr = response.errorBody()?.string() ?: ""
                    var userMsg = resp?.message ?: "Gagal check-in ruangan"
                    try {
                        val json = org.json.JSONObject(rawErr)
                        if (json.has("message")) userMsg = json.getString("message")
                    } catch (e: Exception) {}
                    Toast.makeText(this@TeacherMainActivity, "Gagal: $userMsg", Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<TeacherSessionCheckInResponse>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showManualSelectRoomDialog() {
        Toast.makeText(this, "Memuat daftar ruangan kelas...", Toast.LENGTH_SHORT).show()
        ApiClient.getClient(this).getPhysicalRoomsList().enqueue(object : Callback<PhysicalRoomsListResponse> {
            override fun onResponse(call: Call<PhysicalRoomsListResponse>, response: Response<PhysicalRoomsListResponse>) {
                val rooms = response.body()?.data ?: emptyList()
                if (rooms.isEmpty()) {
                    Toast.makeText(this@TeacherMainActivity, "Daftar ruangan tidak tersedia", Toast.LENGTH_SHORT).show()
                    return
                }

                val teachingClassesRaw = sessionManager.getTeachingClasses().uppercase()
                val myClasses = teachingClassesRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                // Sort: rooms in myClasses on top
                val sortedRooms = rooms.sortedWith(Comparator { a, b ->
                    val aIn = if (a.className != null && myClasses.contains(a.className.uppercase())) 0 else 1
                    val bIn = if (b.className != null && myClasses.contains(b.className.uppercase())) 0 else 1
                    if (aIn != bIn) aIn.compareTo(bIn) else a.roomName.compareTo(b.roomName)
                })

                val dialog = createFullscreenDialog()
                val root = LinearLayout(this@TeacherMainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#F8FAFC"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }

                val header = createFullscreenHeader(
                    title = "🏫 Pilih Ruang Kelas KBM",
                    subtitle = "Rombel Diampu: ${sessionManager.getTeachingClasses().ifEmpty { "-" }}",
                    onClose = { dialog.dismiss() }
                )
                root.addView(header)

                val sv = ScrollView(this@TeacherMainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                val container = LinearLayout(this@TeacherMainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                sortedRooms.forEach { room ->
                    val isMyClass = room.className != null && myClasses.contains(room.className.uppercase())

                    val card = CardView(this@TeacherMainActivity).apply {
                        radius = 12f * resources.displayMetrics.density
                        cardElevation = 2f * resources.displayMetrics.density
                        setCardBackgroundColor(if (isMyClass) Color.WHITE else Color.parseColor("#F1F5F9"))
                        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = (10 * resources.displayMetrics.density).toInt()
                        }
                        layoutParams = lp
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            dialog.dismiss()
                            handleTeacherRoomCheckIn(roomCode = room.roomCode)
                        }
                    }

                    val row = LinearLayout(this@TeacherMainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        val p = (14 * resources.displayMetrics.density).toInt()
                        setPadding(p, p, p, p)
                    }

                    val iconBox = LinearLayout(this@TeacherMainActivity).apply {
                        val size = (38 * resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginEnd = (12 * resources.displayMetrics.density).toInt()
                        }
                        setBackgroundResource(if (isMyClass) com.school.smartcbt.R.drawable.bg_icon_green else com.school.smartcbt.R.drawable.bg_icon_blue)
                        gravity = android.view.Gravity.CENTER
                    }
                    val tvIco = TextView(this@TeacherMainActivity).apply {
                        text = if (isMyClass) "⭐" else "🏫"
                        textSize = 18f
                    }
                    iconBox.addView(tvIco)
                    row.addView(iconBox)

                    val infoBox = LinearLayout(this@TeacherMainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val tvName = TextView(this@TeacherMainActivity).apply {
                        text = room.roomName
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#0F172A"))
                    }
                    val tvSub = TextView(this@TeacherMainActivity).apply {
                        text = if (isMyClass) "✅ Rombel Mengajar Anda • ${room.building ?: ""}" else "${room.building ?: "Gedung Sekolah"} (Lantai ${room.floor ?: 1})"
                        textSize = 11.5f
                        setTextColor(if (isMyClass) Color.parseColor("#059669") else Color.parseColor("#64748B"))
                        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = 2
                        }
                        layoutParams = lp
                    }
                    infoBox.addView(tvName)
                    infoBox.addView(tvSub)
                    row.addView(infoBox)

                    val tvSelect = TextView(this@TeacherMainActivity).apply {
                        text = "Masuk ➔"
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(if (isMyClass) Color.parseColor("#059669") else Color.parseColor("#2563EB"))
                    }
                    row.addView(tvSelect)

                    card.addView(row)
                    container.addView(card)
                }

                sv.addView(container)
                root.addView(sv)
                dialog.setContentView(root)
                dialog.show()
            }

            override fun onFailure(call: Call<PhysicalRoomsListResponse>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, "Gagal memuat ruangan: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showTeacherManageAttendanceDialog() {
        val session = activeTeachingSession
        if (session == null) {
            Toast.makeText(this, "Tidak ada sesi mengajar aktif saat ini", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Memuat daftar presensi siswa...", Toast.LENGTH_SHORT).show()

        ApiClient.getClient(this).getCurrentTeacherSession().enqueue(object : Callback<TeacherCurrentSessionResponse> {
            override fun onResponse(call: Call<TeacherCurrentSessionResponse>, response: Response<TeacherCurrentSessionResponse>) {
                val freshSession = response.body()?.session ?: session
                activeTeachingSession = freshSession
                updateTeachingSessionUi(freshSession)

                val attendances = freshSession.studentAttendances?.toMutableList() ?: mutableListOf()

                val dialog = createFullscreenDialog()
                val root = LinearLayout(this@TeacherMainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#F8FAFC"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }

                val roomName = freshSession.room?.roomName ?: "Kelas ${freshSession.className}"
                val header = createFullscreenHeader(
                    title = "👥 Presensi Siswa: $roomName",
                    subtitle = "Mapel: ${freshSession.subjectName} • Presensi Siswa AKTIF",
                    onClose = { dialog.dismiss() },
                    actionBtnText = "🔄 Refresh",
                    onActionClick = {
                        dialog.dismiss()
                        showTeacherManageAttendanceDialog()
                    }
                )
                root.addView(header)

                // Summary Stats Bar
                val summaryCard = CardView(this@TeacherMainActivity).apply {
                    radius = 10f * resources.displayMetrics.density
                    cardElevation = 2f * resources.displayMetrics.density
                    setCardBackgroundColor(Color.WHITE)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        val m = (12 * resources.displayMetrics.density).toInt()
                        setMargins(m, m, m, (6 * resources.displayMetrics.density).toInt())
                    }
                    layoutParams = lp
                }
                val summaryRow = LinearLayout(this@TeacherMainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 4f
                    val p = (10 * resources.displayMetrics.density).toInt()
                    setPadding(p, p, p, p)
                }

                val tvPresentStat = TextView(this@TeacherMainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = android.view.Gravity.CENTER
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#15803D"))
                    text = "🟢 Hadir: ${freshSession.presentCount ?: 0}"
                }
                val tvTruantStat = TextView(this@TeacherMainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = android.view.Gravity.CENTER
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#DC2626"))
                    text = "🔴 Bolos: ${freshSession.truantCount ?: 0}"
                }
                val tvSickStat = TextView(this@TeacherMainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = android.view.Gravity.CENTER
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#D97706"))
                    text = "🟡 Sakit/Izin: ${(freshSession.sickCount ?: 0) + (freshSession.permitCount ?: 0)}"
                }
                val tvTotalStat = TextView(this@TeacherMainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = android.view.Gravity.CENTER
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#475569"))
                    text = "👥 Total: ${freshSession.studentCount ?: attendances.size}"
                }

                summaryRow.addView(tvPresentStat)
                summaryRow.addView(tvTruantStat)
                summaryRow.addView(tvSickStat)
                summaryRow.addView(tvTotalStat)
                summaryCard.addView(summaryRow)
                root.addView(summaryCard)

                val sv = ScrollView(this@TeacherMainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                val container = LinearLayout(this@TeacherMainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (12 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                if (attendances.isEmpty()) {
                    container.addView(TextView(this@TeacherMainActivity).apply {
                        text = "Belum ada data siswa di rombel ini."
                        setTextColor(Color.parseColor("#64748B"))
                        textSize = 13f
                        gravity = android.view.Gravity.CENTER
                        setPadding(20, 40, 20, 40)
                    })
                } else {
                    attendances.forEach { att ->
                        val card = CardView(this@TeacherMainActivity).apply {
                            radius = 10f * resources.displayMetrics.density
                            cardElevation = 1.5f * resources.displayMetrics.density
                            setCardBackgroundColor(Color.WHITE)
                            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin = (10 * resources.displayMetrics.density).toInt()
                            }
                            layoutParams = lp
                        }

                        val studentLayout = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            val p = (12 * resources.displayMetrics.density).toInt()
                            setPadding(p, p, p, p)
                        }

                        // Top Row: Student Name + Status Badge
                        val topRow = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }

                        val nameBox = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val tvStudentName = TextView(this@TeacherMainActivity).apply {
                            text = att.student?.name ?: "Siswa ${att.studentId.take(6)}"
                            textSize = 13.5f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor("#0F172A"))
                        }
                        val tvNisn = TextView(this@TeacherMainActivity).apply {
                            text = "NISN: ${att.student?.nisn ?: "-"} • Status: ${att.status}"
                            textSize = 11f
                            setTextColor(Color.parseColor("#64748B"))
                        }
                        nameBox.addView(tvStudentName)
                        nameBox.addView(tvNisn)
                        topRow.addView(nameBox)

                        val tvStatusBadge = TextView(this@TeacherMainActivity).apply {
                            val padH = (8 * resources.displayMetrics.density).toInt()
                            val padV = (3 * resources.displayMetrics.density).toInt()
                            setPadding(padH, padV, padH, padV)
                            textSize = 10.5f
                            setTypeface(null, Typeface.BOLD)

                            when (att.status) {
                                "PRESENT" -> {
                                    text = "🟢 HADIR"
                                    setTextColor(Color.parseColor("#15803D"))
                                    setBackgroundResource(com.school.smartcbt.R.drawable.bg_badge_emerald)
                                }
                                "TRUANT", "BOLOS" -> {
                                    text = "🔴 BOLOS"
                                    setTextColor(Color.parseColor("#DC2626"))
                                    setBackgroundColor(Color.parseColor("#FEE2E2"))
                                }
                                "SICK", "SAKIT" -> {
                                    text = "🟡 SAKIT"
                                    setTextColor(Color.parseColor("#D97706"))
                                    setBackgroundColor(Color.parseColor("#FEF3C7"))
                                }
                                "PERMISSION", "IZIN" -> {
                                    text = "🔵 IZIN"
                                    setTextColor(Color.parseColor("#1D4ED8"))
                                    setBackgroundColor(Color.parseColor("#EFF6FF"))
                                }
                                else -> {
                                    text = "⚪ BELUM ABSEN"
                                    setTextColor(Color.parseColor("#475569"))
                                    setBackgroundColor(Color.parseColor("#F1F5F9"))
                                }
                            }
                        }
                        topRow.addView(tvStatusBadge)
                        studentLayout.addView(topRow)

                        // Morning Gate Lock Notice
                        if (att.isLockedByGate == true) {
                            val tvLockNotice = TextView(this@TeacherMainActivity).apply {
                                text = "🔒 Terkunci: Tercatat ${att.morningGateStatus ?: "SAKIT/IZIN"} di Gerbang Masuk Pagi"
                                textSize = 10.5f
                                setTextColor(Color.parseColor("#DC2626"))
                                setTypeface(null, Typeface.BOLD)
                                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    topMargin = 4
                                }
                                layoutParams = lp
                            }
                            studentLayout.addView(tvLockNotice)
                        }

                        // Action Buttons: Hadir, Bolos, Sakit, Izin
                        val btnRow = LinearLayout(this@TeacherMainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = (8 * resources.displayMetrics.density).toInt()
                            }
                            layoutParams = lp
                        }

                        fun updateStatus(newStatus: String) {
                            if (att.isLockedByGate == true && newStatus == "PRESENT") {
                                AlertDialog.Builder(this@TeacherMainActivity)
                                    .setTitle("🔒 Status Terkunci Gerbang Pagi")
                                    .setMessage("Siswa ini telah tercatat ${att.morningGateStatus ?: "SAKIT/IZIN"} pada gerbang masuk pagi hari ini.\n\nStatus terkunci demi menjaga integritas kehadiran.")
                                    .setPositiveButton("Mengerti", null)
                                    .show()
                                return
                            }

                            val req = TeacherUpdateAttendanceRequest(
                                studentId = att.studentId,
                                status = newStatus,
                                notes = if (newStatus == "TRUANT") "Ditandai tidak hadir/bolos oleh guru" else null
                            )

                            ApiClient.getClient(this@TeacherMainActivity)
                                .updateStudentAttendanceByTeacher(freshSession.id, req)
                                .enqueue(object : Callback<BasicResponse> {
                                    override fun onResponse(call: Call<BasicResponse>, resp: Response<BasicResponse>) {
                                        if (resp.isSuccessful) {
                                            att.status = newStatus
                                            tvNisn.text = "NISN: ${att.student?.nisn ?: "-"} • Status: $newStatus"
                                            when (newStatus) {
                                                "PRESENT" -> {
                                                    tvStatusBadge.text = "🟢 HADIR"
                                                    tvStatusBadge.setTextColor(Color.parseColor("#15803D"))
                                                    tvStatusBadge.setBackgroundResource(com.school.smartcbt.R.drawable.bg_badge_emerald)
                                                }
                                                "TRUANT" -> {
                                                    tvStatusBadge.text = "🔴 BOLOS"
                                                    tvStatusBadge.setTextColor(Color.parseColor("#DC2626"))
                                                    tvStatusBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
                                                }
                                                "SICK" -> {
                                                    tvStatusBadge.text = "🟡 SAKIT"
                                                    tvStatusBadge.setTextColor(Color.parseColor("#D97706"))
                                                    tvStatusBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
                                                }
                                                "PERMISSION" -> {
                                                    tvStatusBadge.text = "🔵 IZIN"
                                                    tvStatusBadge.setTextColor(Color.parseColor("#1D4ED8"))
                                                    tvStatusBadge.setBackgroundColor(Color.parseColor("#EFF6FF"))
                                                }
                                            }
                                            loadCurrentTeachingSession()
                                        } else {
                                            Toast.makeText(this@TeacherMainActivity, "Gagal mengubah status presensi", Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                                        Toast.makeText(this@TeacherMainActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
                                    }
                                })
                        }

                        val btnHadir = Button(this@TeacherMainActivity).apply {
                            text = "🟢 Hadir"
                            textSize = 10.5f
                            layoutParams = LinearLayout.LayoutParams(0, (36 * resources.displayMetrics.density).toInt(), 1f).apply {
                                marginEnd = 3
                            }
                            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                            setTextColor(Color.WHITE)
                            setOnClickListener { updateStatus("PRESENT") }
                        }

                        val btnBolos = Button(this@TeacherMainActivity).apply {
                            text = "🔴 Bolos"
                            textSize = 10.5f
                            layoutParams = LinearLayout.LayoutParams(0, (36 * resources.displayMetrics.density).toInt(), 1f).apply {
                                marginStart = 2
                                marginEnd = 2
                            }
                            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#DC2626"))
                            setTextColor(Color.WHITE)
                            setOnClickListener { updateStatus("TRUANT") }
                        }

                        val btnSakit = Button(this@TeacherMainActivity).apply {
                            text = "🟡 Sakit"
                            textSize = 10.5f
                            layoutParams = LinearLayout.LayoutParams(0, (36 * resources.displayMetrics.density).toInt(), 1f).apply {
                                marginStart = 2
                                marginEnd = 2
                            }
                            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D97706"))
                            setTextColor(Color.WHITE)
                            setOnClickListener { updateStatus("SICK") }
                        }

                        val btnIzin = Button(this@TeacherMainActivity).apply {
                            text = "🔵 Izin"
                            textSize = 10.5f
                            layoutParams = LinearLayout.LayoutParams(0, (36 * resources.displayMetrics.density).toInt(), 1f).apply {
                                marginStart = 3
                            }
                            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2563EB"))
                            setTextColor(Color.WHITE)
                            setOnClickListener { updateStatus("PERMISSION") }
                        }

                        btnRow.addView(btnHadir)
                        btnRow.addView(btnBolos)
                        btnRow.addView(btnSakit)
                        btnRow.addView(btnIzin)
                        studentLayout.addView(btnRow)

                        card.addView(studentLayout)
                        container.addView(card)
                    }
                }

                sv.addView(container)
                root.addView(sv)
                dialog.setContentView(root)
                dialog.show()
            }

            override fun onFailure(call: Call<TeacherCurrentSessionResponse>, t: Throwable) {
                Toast.makeText(this@TeacherMainActivity, "Gagal memuat presensi: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showTeacherCloseSessionDialog() {
        val session = activeTeachingSession
        if (session == null) {
            Toast.makeText(this, "Tidak ada sesi mengajar aktif untuk ditutup", Toast.LENGTH_SHORT).show()
            return
        }

        val roomName = session.room?.roomName ?: "Kelas ${session.className}"

        val sv = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvPrompt = TextView(this).apply {
            text = "Apakah KBM di $roomName telah selesai?\n\nRekapitulasi: ${session.presentCount ?: 0} Hadir, ${session.truantCount ?: 0} Bolos, ${(session.sickCount ?: 0) + (session.permitCount ?: 0)} Sakit/Izin."
            textSize = 13f
            setTextColor(Color.parseColor("#1E293B"))
        }
        container.addView(tvPrompt)

        val etSummary = EditText(this).apply {
            hint = "Ringkasan Pembelajaran / Jurnal KBM (Materi, bab, evaluasi)..."
            minLines = 3
            textSize = 13f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16
            }
            layoutParams = lp
        }
        container.addView(etSummary)

        val etNotes = EditText(this).apply {
            hint = "Catatan Tambahan (Opsional)..."
            minLines = 2
            textSize = 13f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            layoutParams = lp
        }
        container.addView(etNotes)

        sv.addView(container)

        AlertDialog.Builder(this)
            .setTitle("🔴 Tutup Sesi KBM & Simpan Jurnal")
            .setView(sv)
            .setPositiveButton("Selesai KBM") { _, _ ->
                val summaryText = etSummary.text.toString().trim()
                val notesText = etNotes.text.toString().trim()

                Toast.makeText(this, "Menutup sesi mengajar...", Toast.LENGTH_SHORT).show()

                val req = TeacherCheckOutRequest(
                    sessionId = session.id,
                    teachingSummary = if (summaryText.isNotEmpty()) summaryText else "KBM terlaksana dengan baik.",
                    notes = if (notesText.isNotEmpty()) notesText else null
                )

                ApiClient.getClient(this).checkOutTeachingSession(req).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@TeacherMainActivity, "✅ Sesi mengajar di $roomName berhasil ditutup & jurnal tersimpan!", Toast.LENGTH_LONG).show()
                            activeTeachingSession = null
                            updateTeachingSessionUi(null)
                            loadCurrentTeachingSession()
                        } else {
                            Toast.makeText(this@TeacherMainActivity, "Gagal menutup sesi KBM", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@TeacherMainActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}

