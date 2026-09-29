package com.school.smartcbt.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.school.smartcbt.ExamActivity
import android.content.ClipboardManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.ProgressBar
import com.school.smartcbt.ScannerActivity
import com.school.smartcbt.data.model.ActiveExamsResponse
import com.school.smartcbt.data.model.ExamItemDto
import com.school.smartcbt.data.model.VerifyTokenRequest
import com.school.smartcbt.data.model.VerifyTokenResponse
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.databinding.ActivityExambroGatewayBinding
import com.school.smartcbt.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ExambroGatewayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExambroGatewayBinding
    private lateinit var sessionManager: SessionManager
    private var allExams: List<ExamItemDto> = emptyList()
    private var isCompletedTabActive: Boolean = false

    private var pendingTokenDialogExamId: String? = null
    private var pendingTokenDialogTitle: String? = null
    private var pendingTokenDialogSubject: String? = null
    private var pendingTokenDialogDuration: Int = 90
    private var activeTokenDialog: AlertDialog? = null
    private var activeEtTokenInput: EditText? = null

    private val scanTokenQrLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val scannedToken = result.data?.getStringExtra(ScannerActivity.EXTRA_SCANNED_TOKEN)
            if (!scannedToken.isNullOrBlank()) {
                val clean = scannedToken.uppercase()
                activeEtTokenInput?.setText(clean)
                Toast.makeText(this, "✅ QR Token Terdeteksi: $clean", Toast.LENGTH_SHORT).show()

                val dialog = activeTokenDialog
                val examId = pendingTokenDialogExamId
                if (dialog != null && dialog.isShowing && examId != null) {
                    val title = pendingTokenDialogTitle ?: "Ujian"
                    val subject = pendingTokenDialogSubject ?: "Mata Pelajaran"
                    val duration = pendingTokenDialogDuration
                    val pb = dialog.findViewById<ProgressBar>(com.school.smartcbt.R.id.pbTokenLoading)
                    val errorLayout = dialog.findViewById<LinearLayout>(com.school.smartcbt.R.id.layoutTokenError)
                    val tvError = dialog.findViewById<TextView>(com.school.smartcbt.R.id.tvTokenErrorMessage)
                    val btnSubmit = dialog.findViewById<Button>(com.school.smartcbt.R.id.btnSubmitTokenDialog)
                    verifyAndLaunchExam(examId, title, subject, duration, clean, pb, errorLayout, tvError, btnSubmit, dialog)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExambroGatewayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        setupHeader()
        setupTabs()
        checkBatteryTelemetry()
        loadActiveExams()
    }

    private fun setupHeader() {
        binding.btnBackExambro.setOnClickListener { finish() }
        binding.btnRefreshExambro.setOnClickListener {
            Toast.makeText(this, "Menyegarkan jadwal ujian...", Toast.LENGTH_SHORT).show()
            loadActiveExams()
        }

        val name = sessionManager.getName().ifEmpty { "Peserta CBT" }
        val nisn = sessionManager.getNisn().ifEmpty { "0013929592" }
        val cName = sessionManager.getClassName().ifEmpty { "VII-A" }

        binding.tvExambroStudentName.text = "Peserta: $name"
        binding.tvExambroStudentClass.text = "NISN: $nisn • Rombel: $cName"
    }

    private fun setupTabs() {
        binding.btnTabExamActive.setOnClickListener {
            if (isCompletedTabActive) {
                isCompletedTabActive = false
                updateTabStyles()
                renderCurrentTabExams()
            }
        }
        binding.btnTabExamCompleted.setOnClickListener {
            if (!isCompletedTabActive) {
                isCompletedTabActive = true
                updateTabStyles()
                renderCurrentTabExams()
            }
        }
    }

    private fun updateTabStyles() {
        if (!isCompletedTabActive) {
            binding.btnTabExamActive.setBackgroundColor(Color.parseColor("#0A2E5C"))
            binding.btnTabExamActive.setTextColor(Color.WHITE)
            binding.btnTabExamCompleted.setBackgroundColor(Color.TRANSPARENT)
            binding.btnTabExamCompleted.setTextColor(Color.parseColor("#64748B"))
            binding.tvExamSectionTitle.text = "📝 DAFTAR JADWAL UJIAN AKTIF"
            binding.tvExamSectionSubtitle.text = "Pilih sesi ujian yang tersedia untuk memasukkan token dan memulai ujian."
        } else {
            binding.btnTabExamCompleted.setBackgroundColor(Color.parseColor("#0A2E5C"))
            binding.btnTabExamCompleted.setTextColor(Color.WHITE)
            binding.btnTabExamActive.setBackgroundColor(Color.TRANSPARENT)
            binding.btnTabExamActive.setTextColor(Color.parseColor("#64748B"))
            binding.tvExamSectionTitle.text = "🏆 RIWAYAT UJIAN SELESAI"
            binding.tvExamSectionSubtitle.text = "Daftar ujian CBT yang telah berhasil dikerjakan dan tersimpan di server."
        }
    }

    private fun isExamCompleted(exam: ExamItemDto): Boolean {
        val st = exam.status.uppercase()
        return st == "FINISHED" || st == "COMPLETED" || st == "SUBMITTED" || st == "DONE"
    }

    private fun updateExamProgress() {
        val total = allExams.size
        val completed = allExams.count { isExamCompleted(it) }
        val pct = if (total > 0) (completed * 100) / total else 0
        binding.tvExamProgressSummary.text = "$completed/$total Selesai ($pct%)"
        binding.pbExamProgress.progress = pct
    }

    private fun renderCurrentTabExams() {
        val filtered = if (isCompletedTabActive) {
            allExams.filter { isExamCompleted(it) }
        } else {
            allExams.filter { !isExamCompleted(it) }
        }
        binding.tvExamCountBadge.text = "${filtered.size} Ujian"
        if (filtered.isNotEmpty()) {
            renderExamCards(filtered, isCompletedTabActive)
        } else {
            val emptyMsg = if (isCompletedTabActive) {
                "Belum ada sesi ujian CBT yang telah diselesaikan untuk akun Anda."
            } else {
                "Saat ini tidak ada sesi ujian CBT aktif yang perlu dikerjakan untuk kelas Anda."
            }
            renderEmptyExamState(emptyMsg)
        }
    }

    private fun loadActiveExams() {
        ApiClient.getClient(this).getStudentCbtExams().enqueue(object : Callback<ActiveExamsResponse> {
            override fun onResponse(call: Call<ActiveExamsResponse>, response: Response<ActiveExamsResponse>) {
                val exams = response.body()?.exams ?: emptyList()
                allExams = exams
                updateExamProgress()
                renderCurrentTabExams()
            }

            override fun onFailure(call: Call<ActiveExamsResponse>, t: Throwable) {
                renderEmptyExamState("Tidak dapat terhubung ke server CBT (${t.message}). Pastikan jaringan lokal sekolah terhubung.")
            }
        })
    }

    private fun renderExamCards(exams: List<ExamItemDto>, isCompleted: Boolean = false) {
        binding.containerExamCards.removeAllViews()
        val density = resources.displayMetrics.density

        exams.forEach { exam ->
            val card = createExamCard(
                id = exam.id,
                title = exam.title,
                subject = exam.subject,
                duration = exam.durationMinutes,
                isTokenActive = exam.isTokenActive,
                token = exam.token,
                sessionName = exam.sessionName ?: "Sesi Ujian CBT",
                startTimeStr = exam.startTimeStr ?: "07:30",
                endTimeStr = exam.endTimeStr ?: "12:00",
                questionsCount = exam.questionsCount.takeIf { it > 0 } ?: 0,
                isCompleted = isCompleted,
                density = density
            )
            binding.containerExamCards.addView(card)
        }
    }

    private fun checkBatteryTelemetry() {
        try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, batteryFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 85

            binding.tvExambroBattery.text = "🔋 Baterai: $batteryPct%"
            if (batteryPct < 20) {
                binding.tvExambroBattery.setTextColor(Color.parseColor("#991B1B"))
                binding.tvExambroBattery.setBackgroundColor(Color.parseColor("#FEE2E2"))
            }
        } catch (e: Exception) {
            binding.tvExambroBattery.text = "🔋 Baterai: 85%"
        }
    }

    private fun renderEmptyExamState(message: String) {
        binding.containerExamCards.removeAllViews()
        val density = resources.displayMetrics.density

        val card = CardView(this).apply {
            radius = 16 * density
            cardElevation = 2 * density
            useCompatPadding = true
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (24 * density).toInt()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (28 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvIcon = TextView(this).apply {
            text = "📭"
            textSize = 44f
            gravity = Gravity.CENTER
        }
        layout.addView(tvIcon)

        val tvTitle = TextView(this).apply {
            text = "Belum Ada Jadwal Ujian Aktif"
            setTextColor(Color.parseColor("#1E293B"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        layout.addView(tvTitle)

        val tvDesc = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#64748B"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, (16 * density).toInt())
        }
        layout.addView(tvDesc)

        val btnRefresh = Button(this).apply {
            text = "🔄 Segarkan Jadwal"
            setBackgroundColor(Color.parseColor("#1E3A8A"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setOnClickListener { loadActiveExams() }
        }
        layout.addView(btnRefresh)

        card.addView(layout)
        binding.containerExamCards.addView(card)
    }

    private fun createExamCard(
        id: String,
        title: String,
        subject: String,
        duration: Int,
        isTokenActive: Boolean,
        token: String?,
        sessionName: String,
        startTimeStr: String,
        endTimeStr: String,
        questionsCount: Int,
        isCompleted: Boolean = false,
        density: Float
    ): CardView {
        val card = CardView(this).apply {
            radius = 14 * density
            cardElevation = 2.5f * density
            useCompatPadding = true
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
        }

        // Row 1: Session Badge & Token Status Pill
        val rowTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvSession = TextView(this).apply {
            text = "🏛️ $sessionName"
            setTextColor(Color.parseColor("#475569"))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowTop.addView(tvSession)

        val tvTokenBadge = TextView(this).apply {
            val (badgeText, badgeFg, badgeBg) = when {
                isCompleted -> Triple("✅ Sudah Selesai Dikerjakan", "#166534", "#DCFCE7")
                isTokenActive -> Triple("🔒 Butuh Token Pengawas", "#991B1B", "#FEE2E2")
                else -> Triple("🔓 Bebas Token (Langsung Mulai)", "#065F46", "#D1FAE5")
            }
            text = badgeText
            setTextColor(Color.parseColor(badgeFg))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            val gdBadge = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(Color.parseColor(badgeBg))
            }
            background = gdBadge
            val hP = (10 * density).toInt()
            val vP = (4 * density).toInt()
            setPadding(hP, vP, hP, vP)
        }
        rowTop.addView(tvTokenBadge)
        root.addView(rowTop)

        // Row 2: Subject & Title
        val tvSubject = TextView(this).apply {
            text = subject
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        root.addView(tvSubject)

        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#64748B"))
            textSize = 12f
            setPadding(0, (2 * density).toInt(), 0, (8 * density).toInt())
        }
        root.addView(tvTitle)

        // Row 3: Jam Ujian Highlight Banner
        val bannerTime = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val gdBanner = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor(if (isCompleted) "#F0FDF4" else "#EFF6FF"))
            }
            background = gdBanner
            val pB = (10 * density).toInt()
            setPadding(pB, pB, pB, pB)
        }

        val tvTime = TextView(this).apply {
            text = if (isCompleted) "✓ Status: Telah Diserahkan" else "🕒 Jam Ujian: $startTimeStr - $endTimeStr WIB"
            setTextColor(Color.parseColor(if (isCompleted) "#15803D" else "#1D4ED8"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bannerTime.addView(tvTime)

        val tvDuration = TextView(this).apply {
            text = "⏱️ $duration Menit • $questionsCount Soal"
            setTextColor(Color.parseColor(if (isCompleted) "#166534" else "#1E40AF"))
            textSize = 11f
        }
        bannerTime.addView(tvDuration)
        root.addView(bannerTime)

        // Row 4: Action Button "Mulai Ujian / Masukkan Token / Selesai"
        val btnStart = androidx.appcompat.widget.AppCompatButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46 * density).toInt()
            ).apply {
                topMargin = (12 * density).toInt()
            }
            val gdBtn = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor(if (isCompleted) "#E2E8F0" else if (isTokenActive) "#D97706" else "#1E3A8A"))
            }
            background = gdBtn
            stateListAnimator = null
            elevation = 0f
            if (isCompleted) {
                text = "✓ Jawaban Berhasil Disimpan di Server"
                setTextColor(Color.parseColor("#475569"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                isEnabled = false
            } else {
                text = if (isTokenActive) "🔑 Masukkan Token & Buka Ujian" else "🚀 Buka ExamBrowser Sekarang"
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }
        }

        if (!isCompleted) {
            val clickListener = View.OnClickListener {
                handleExamClick(id, title, subject, duration, isTokenActive, token)
            }
            btnStart.setOnClickListener(clickListener)
            card.setOnClickListener(clickListener)
        }

        root.addView(btnStart)
        card.addView(root)
        return card
    }

    private fun handleExamClick(examId: String, title: String, subject: String, duration: Int, isTokenActive: Boolean, expectedToken: String?) {
        if (!isTokenActive) {
            launchExamSession(examId, title, subject, duration, expectedToken ?: "UNBK26")
        } else {
            promptExamToken(examId, title, subject, duration, expectedToken)
        }
    }

    private fun promptExamToken(examId: String, title: String, subject: String, duration: Int, expectedToken: String?) {
        pendingTokenDialogExamId = examId
        pendingTokenDialogTitle = title
        pendingTokenDialogSubject = subject
        pendingTokenDialogDuration = duration

        val dialogView = layoutInflater.inflate(com.school.smartcbt.R.layout.dialog_exam_token_modern, null)
        val tvTitle = dialogView.findViewById<TextView>(com.school.smartcbt.R.id.tvTokenDialogTitle)
        val tvSubject = dialogView.findViewById<TextView>(com.school.smartcbt.R.id.tvTokenDialogSubject)
        val tvClass = dialogView.findViewById<TextView>(com.school.smartcbt.R.id.tvTokenDialogClass)
        val etToken = dialogView.findViewById<EditText>(com.school.smartcbt.R.id.etExamTokenInput)
        val btnPaste = dialogView.findViewById<Button>(com.school.smartcbt.R.id.btnPasteToken)
        val btnScanQr = dialogView.findViewById<Button>(com.school.smartcbt.R.id.btnScanQrToken)
        val layoutError = dialogView.findViewById<LinearLayout>(com.school.smartcbt.R.id.layoutTokenError)
        val tvErrorMessage = dialogView.findViewById<TextView>(com.school.smartcbt.R.id.tvTokenErrorMessage)
        val pbLoading = dialogView.findViewById<ProgressBar>(com.school.smartcbt.R.id.pbTokenLoading)
        val btnCancel = dialogView.findViewById<Button>(com.school.smartcbt.R.id.btnCancelTokenDialog)
        val btnSubmit = dialogView.findViewById<Button>(com.school.smartcbt.R.id.btnSubmitTokenDialog)

        activeEtTokenInput = etToken

        tvTitle.text = title
        tvSubject.text = "$subject • $duration Menit"
        val studentClass = sessionManager.getClassName().ifEmpty { "VII-A" }
        tvClass.text = "🏛️ Ruang / Rombel: Kelas $studentClass • Terhubung Pengawas"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        activeTokenDialog = dialog

        // Paste dari Clipboard
        btnPaste.setOnClickListener {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val pasted = clip.getItemAt(0).text?.toString()?.trim()?.uppercase() ?: ""
                    if (pasted.isNotEmpty()) {
                        etToken.setText(pasted)
                        etToken.setSelection(pasted.length)
                        layoutError.visibility = View.GONE
                        Toast.makeText(this, "📋 Berhasil menempel token: $pasted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal menempel: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Scan QR Pengawas
        btnScanQr.setOnClickListener {
            val scanIntent = Intent(this, ScannerActivity::class.java).apply {
                putExtra(ScannerActivity.EXTRA_SCAN_MODE, ScannerActivity.MODE_EXAM_TOKEN)
            }
            scanTokenQrLauncher.launch(scanIntent)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSubmit.setOnClickListener {
            val entered = etToken.text.toString().trim().uppercase()
            if (entered.isEmpty()) {
                layoutError.visibility = View.VISIBLE
                tvErrorMessage.text = "⚠️ Token ujian tidak boleh kosong."
                vibrateError()
                return@setOnClickListener
            }
            verifyAndLaunchExam(examId, title, subject, duration, entered, pbLoading, layoutError, tvErrorMessage, btnSubmit, dialog)
        }

        dialog.show()
    }

    private fun verifyAndLaunchExam(
        examId: String,
        title: String,
        subject: String,
        duration: Int,
        token: String,
        pbLoading: ProgressBar?,
        layoutError: LinearLayout?,
        tvErrorMessage: TextView?,
        btnSubmit: Button?,
        dialog: AlertDialog?
    ) {
        pbLoading?.visibility = View.VISIBLE
        layoutError?.visibility = View.GONE
        btnSubmit?.isEnabled = false
        btnSubmit?.text = "Memverifikasi..."

        ApiClient.getClient(this).verifyExamToken(VerifyTokenRequest(examId, token))
            .enqueue(object : Callback<VerifyTokenResponse> {
                override fun onResponse(call: Call<VerifyTokenResponse>, response: Response<VerifyTokenResponse>) {
                    pbLoading?.visibility = View.GONE
                    btnSubmit?.isEnabled = true
                    btnSubmit?.text = "🚀 Verifikasi & Mulai"

                    if (response.isSuccessful && response.body()?.valid == true) {
                        dialog?.dismiss()
                        Toast.makeText(this@ExambroGatewayActivity, "✅ Token Valid! Membuka Ruang Ujian...", Toast.LENGTH_SHORT).show()
                        launchExamSession(examId, title, subject, duration, token)
                    } else {
                        val errMsg = response.body()?.message ?: "Token ujian tidak valid atau belum dirilis Pengawas."
                        layoutError?.visibility = View.VISIBLE
                        tvErrorMessage?.text = errMsg
                        vibrateError()
                    }
                }

                override fun onFailure(call: Call<VerifyTokenResponse>, t: Throwable) {
                    pbLoading?.visibility = View.GONE
                    btnSubmit?.isEnabled = true
                    btnSubmit?.text = "🚀 Verifikasi & Mulai"

                    // Fallback jika network error / offline server
                    dialog?.dismiss()
                    Toast.makeText(this@ExambroGatewayActivity, "🚀 Menghubungkan ke Ruang Ujian...", Toast.LENGTH_SHORT).show()
                    launchExamSession(examId, title, subject, duration, token)
                }
            })
    }

    private fun vibrateError() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(200)
            }
        } catch (e: Exception) {}
    }

    private fun launchExamSession(examId: String, title: String, subject: String, duration: Int, token: String) {
        val intent = Intent(this, ExamActivity::class.java).apply {
            putExtra("EXAM_ID", examId)
            putExtra("EXAM_TITLE", title)
            putExtra("EXAM_SUBJECT", "$subject ($duration Menit)")
            putExtra("EXAM_DURATION", duration)
            putExtra("EXAM_TOKEN", token)
        }
        startActivity(intent)
    }
}
