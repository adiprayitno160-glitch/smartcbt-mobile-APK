package com.school.smartcbt.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.school.smartcbt.data.model.*
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.databinding.ActivityBkBinding
import com.school.smartcbt.utils.QrCodeHelper
import com.school.smartcbt.utils.SessionManager
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response


class BkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBkBinding
    private lateinit var sessionManager: SessionManager

    companion object {
        const val EXTRA_STUDENT_ID = "extra_student_id"
        const val EXTRA_STUDENT_NAME = "extra_student_name"
        const val EXTRA_CLASS_NAME = "extra_class_name"
        const val EXTRA_IS_PARENT = "extra_is_parent"
    }

    private var targetStudentId: String? = null
    private var isParentMode: Boolean = false
    private var isCounselorMode: Boolean = false
    private var isAnonymousReport: Boolean = false
    private var currentSelectedClass: String? = null
    private var allowedBkClasses: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        val userRole = sessionManager.getRole().uppercase()
        val subject = sessionManager.getTeachingSubject()?.uppercase() ?: ""
        isCounselorMode = (userRole == "COUNSELOR" || userRole == "BK" || subject.contains("BK") || subject.contains("BIMBINGAN") || subject.contains("KONSELING"))
        targetStudentId = intent.getStringExtra(EXTRA_STUDENT_ID)
        isParentMode = intent.getBooleanExtra(EXTRA_IS_PARENT, false) || (userRole == "PARENT")

        setupUI()
        setupBkTabs()
        if (isCounselorMode) {
            setupCounselorFeatures()
            loadBkClassRecap(null)
            loadBkTodayActivity()
        }
        loadConsultations()
        if (!isCounselorMode) {
            loadStudentProfile()
        }
    }

    private var currentBkTab: Int = 0

    private fun setupBkTabs() {
        if (isCounselorMode) {
            binding.tabBtnBkRecap.visibility = View.VISIBLE
            binding.tabBtnBkToday.visibility = View.VISIBLE
            binding.tabBtnBkLeaves.visibility = View.VISIBLE
            binding.tabBtnBkManual.visibility = View.VISIBLE
            binding.tabBtnBk360.visibility = View.VISIBLE
            binding.tabBtnBkReplies.visibility = View.VISIBLE
            binding.tvTabBkReplies.text = "💬 Curhat & Lapor"

            binding.tabBtnBkReport.visibility = View.GONE
            binding.tabBtnBkAppointment.visibility = View.GONE
            binding.tabBtnBkPoints.visibility = View.GONE

            binding.tabBtnBkRecap.setOnClickListener { switchBkTab(0) }
            binding.tabBtnBkToday.setOnClickListener { switchBkTab(1) }
            binding.tabBtnBkLeaves.setOnClickListener { switchBkTab(2) }
            binding.tabBtnBkManual.setOnClickListener { switchBkTab(3) }
            binding.tabBtnBk360.setOnClickListener { switchBkTab(4) }
            binding.tabBtnBkReplies.setOnClickListener { switchBkTab(5) }

            switchBkTab(0)
        } else {
            binding.tabBtnBkRecap.visibility = View.GONE
            binding.tabBtnBkToday.visibility = View.GONE
            binding.tabBtnBkLeaves.visibility = View.GONE
            binding.tabBtnBkManual.visibility = View.GONE
            binding.tabBtnBk360.visibility = View.GONE
            binding.tabBtnBkReplies.visibility = View.VISIBLE
            binding.tabBtnBkReplies.setOnClickListener { switchBkTab(0) }
            binding.tabBtnBkReport.setOnClickListener { switchBkTab(1) }
            binding.tabBtnBkAppointment.setOnClickListener { switchBkTab(2) }
            binding.tabBtnBkPoints.setOnClickListener { switchBkTab(3) }
            binding.tabBtnBkGatepass.visibility = View.VISIBLE
            binding.tabBtnBkGatepass.setOnClickListener {
                startActivity(Intent(this, ExitPassActivity::class.java))
            }

            switchBkTab(0)
        }
    }

    fun switchBkTab(tabIndex: Int) {
        currentBkTab = tabIndex

        val inactiveBg = Color.parseColor("#F1F5F9")
        val inactiveText = Color.parseColor("#64748B")
        val white = Color.parseColor("#FFFFFF")

        fun applyTabStyle(btn: View, tv: TextView, isActive: Boolean, activeColorHex: String) {
            val gd = android.graphics.drawable.GradientDrawable()
            gd.cornerRadius = 20f * resources.displayMetrics.density
            if (isActive) {
                gd.setColor(Color.parseColor(activeColorHex))
                btn.background = gd
                tv.setTextColor(white)
                tv.setTypeface(null, Typeface.BOLD)
            } else {
                gd.setColor(inactiveBg)
                btn.background = gd
                tv.setTextColor(inactiveText)
                tv.setTypeface(null, Typeface.NORMAL)
            }
        }

        if (isCounselorMode) {
            binding.containerTabBkRecap.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
            binding.containerTabBkToday.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
            binding.containerTabBkLeaves.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
            binding.containerTabBkManual.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
            binding.containerTabBk360.visibility = if (tabIndex == 4) View.VISIBLE else View.GONE
            binding.containerTabBkReplies.visibility = if (tabIndex == 5) View.VISIBLE else View.GONE
            binding.containerTabBkReport.visibility = View.GONE
            binding.containerTabBkAppointment.visibility = View.GONE
            binding.containerTabBkPoints.visibility = View.GONE

            applyTabStyle(binding.tabBtnBkRecap, binding.tvTabBkRecap, tabIndex == 0, "#059669")
            applyTabStyle(binding.tabBtnBkToday, binding.tvTabBkToday, tabIndex == 1, "#0284C7")
            applyTabStyle(binding.tabBtnBkLeaves, binding.tvTabBkLeaves, tabIndex == 2, "#2563EB")
            applyTabStyle(binding.tabBtnBkManual, binding.tvTabBkManual, tabIndex == 3, "#10B981")
            applyTabStyle(binding.tabBtnBk360, binding.tvTabBk360, tabIndex == 4, "#7C3AED")
            applyTabStyle(binding.tabBtnBkReplies, binding.tvTabBkReplies, tabIndex == 5, "#D97706")

            if (tabIndex == 2) loadPendingStudentLeaves()
            if (tabIndex == 3) loadBkManualLogs()
            if (tabIndex == 4) loadBk360AttendanceRecap()
            if (tabIndex == 5) loadConsultations()
        } else {
            binding.containerTabBkRecap.visibility = View.GONE
            binding.containerTabBkToday.visibility = View.GONE
            binding.containerTabBkLeaves.visibility = View.GONE
            binding.containerTabBkManual.visibility = View.GONE
            binding.containerTabBk360.visibility = View.GONE
            binding.containerTabBkReplies.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
            binding.containerTabBkReport.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
            binding.containerTabBkAppointment.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
            binding.containerTabBkPoints.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE

            applyTabStyle(binding.tabBtnBkReplies, binding.tvTabBkReplies, tabIndex == 0, "#059669")
            applyTabStyle(binding.tabBtnBkReport, binding.tvTabBkReport, tabIndex == 1, "#DC2626")
            applyTabStyle(binding.tabBtnBkAppointment, binding.tvTabBkAppointment, tabIndex == 2, "#2563EB")
            applyTabStyle(binding.tabBtnBkPoints, binding.tvTabBkPoints, tabIndex == 3, "#7C3AED")

            if (tabIndex == 0) loadConsultations()
        }
    }

    private fun setupUI() {
        binding.btnBackBk.setOnClickListener { finish() }
        binding.btnRefreshBk.setOnClickListener {
            Toast.makeText(this, "Menyegarkan data konseling...", Toast.LENGTH_SHORT).show()
            if (isCounselorMode) {
                loadBkClassRecap(currentSelectedClass)
                loadBkTodayActivity()
            }
            loadConsultations()
            if (!isCounselorMode) loadStudentProfile()
        }

        if (isCounselorMode) {
            binding.tvBkTopTitle.text = "🏛️ Portal Guru BK (Bimbingan Konseling)"
            binding.tvBkTopSubtitle.text = "Rekap Kelas Ampuan, Aduan Whistleblower & Presensi BK"
            binding.tvBkCounselorName.text = "${sessionManager.getName()} (Guru BK)"
            binding.cardAppointmentForm.visibility = View.GONE
            binding.cardReportForm.visibility = View.GONE
            binding.tabBtnBkReport.visibility = View.GONE
            binding.tabBtnBkAppointment.visibility = View.GONE
            binding.tvBkTotalPoints.text = "Portal Konselor"
            binding.tvBkSpBadge.text = "Mode Guru BK"
            binding.tvBkSpBadge.setBackgroundColor(Color.parseColor("#E0E7FF"))
            binding.tvBkSpBadge.setTextColor(Color.parseColor("#3730A3"))
            return
        }

        if (isParentMode) {
            val childName = intent.getStringExtra(EXTRA_STUDENT_NAME)?.ifEmpty { null } ?: sessionManager.getName().ifEmpty { "Ananda" }
            val className = intent.getStringExtra(EXTRA_CLASS_NAME)?.ifEmpty { null } ?: sessionManager.getClassName().ifEmpty { "-" }
            binding.tvBkTopTitle.text = "💬 Ruang Konsultasi Langsung Wali Murid"
            binding.tvBkTopSubtitle.text = "Konsultasi & Tanya Jawab Langsung Guru BK • $childName ($className)"
            binding.tvReportCardTitle.text = "💬 Tulis Pertanyaan / Konsultasi Baru ke Guru BK"
            binding.tvReportCardSub.text = "Ajukan pertanyaan, kendala belajar, atau konsultasi perkembangan ananda langsung kepada Guru BK"
            binding.etReportSubject.hint = "Topik / Perihal Konsultasi (Contoh: Kendala Belajar Matematika)"
            binding.etReportMessage.hint = "Tuliskan pertanyaan atau hal yang ingin Anda konsultasikan secara lengkap..."
            binding.btnSubmitReport.text = "Kirim Pertanyaan ke Guru BK"
            binding.btnSubmitReport.setBackgroundColor(Color.parseColor("#0D9488"))
            binding.containerIdentityCards.visibility = View.GONE
            binding.tvIdentityOptionTitle.visibility = View.GONE
        } else {
            binding.tvBkTopTitle.text = "🕊️ Ruang BK & Konseling Siswa"
            binding.tvBkTopSubtitle.text = "Privasi Terjamin • Bimbingan Belajar, Karakter & Anti-Bullying"
            binding.containerIdentityCards.visibility = View.VISIBLE
            binding.tvIdentityOptionTitle.visibility = View.VISIBLE
        }

        // Setup Identity Selector Cards (Card 1: Tampilkan Identitas / Default, Card 2: Sembunyikan Nama / Whistleblower)
        fun updateIdentitySelectionUI(isAnon: Boolean) {
            isAnonymousReport = isAnon
            if (!isAnon) {
                binding.cardIdentityOpen.setCardBackgroundColor(Color.parseColor("#EFF6FF"))
                binding.rbIdentityOpen.isChecked = true
                binding.tvIdentityOpenTitle.setTextColor(Color.parseColor("#1D4ED8"))

                binding.cardIdentityAnon.setCardBackgroundColor(Color.parseColor("#F8FAFC"))
                binding.rbIdentityAnon.isChecked = false
                binding.tvIdentityAnonTitle.setTextColor(Color.parseColor("#475569"))
            } else {
                binding.cardIdentityOpen.setCardBackgroundColor(Color.parseColor("#F8FAFC"))
                binding.rbIdentityOpen.isChecked = false
                binding.tvIdentityOpenTitle.setTextColor(Color.parseColor("#475569"))

                binding.cardIdentityAnon.setCardBackgroundColor(Color.parseColor("#FEF3C7"))
                binding.rbIdentityAnon.isChecked = true
                binding.tvIdentityAnonTitle.setTextColor(Color.parseColor("#B45309"))
            }
        }

        updateIdentitySelectionUI(false) // Default: Tampilkan Identitas Terbuka

        binding.cardIdentityOpen.setOnClickListener {
            updateIdentitySelectionUI(false)
        }
        binding.cardIdentityAnon.setOnClickListener {
            updateIdentitySelectionUI(true)
        }

        // Setup Category Spinner
        val categories = if (isParentMode) {
            arrayOf(
                "Konsultasi Perkembangan Karakter Anak",
                "Kendala Belajar & Disiplin di Rumah",
                "Laporan / Kekhawatiran Perundungan (Bullying)",
                "Koordinasi Perilaku & Sosial",
                "Rencana Studi Lanjutan Anak",
                "Lainnya"
            )
        } else {
            arrayOf(
                "Masalah Belajar & Akademik",
                "Pengaduan Perundungan (Bullying)",
                "Masalah Sosial & Pertemanan",
                "Kendala Pribadi & Emosional",
                "Bimbingan Karir & Masa Depan",
                "Lainnya"
            )
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        binding.spinnerReportCategory.adapter = adapter

        // Setup Janji Temu Submit
        binding.btnSubmitAppointment.setOnClickListener {
            val topic = binding.etAppointmentTopic.text.toString().trim()
            val date = binding.etAppointmentDate.text.toString().trim()
            val time = binding.etAppointmentTime.text.toString().trim()

            if (topic.isEmpty()) {
                Toast.makeText(this, "Harap isi topik konseling", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val senderPrefix = if (isParentMode) "[Janji Temu Wali Murid]" else "Janji Temu"
            val req = SendReportRequest(
                category = "JANJI_TEMU",
                subject = "$senderPrefix: $topic",
                message = "Permohonan sesi tatap muka: $date pukul $time. Topik: $topic",
                isAnonymous = false,
                studentId = targetStudentId
            )

            sendReportToServer(req, "✅ Permohonan janji temu berhasil diajukan ke Guru BK!")
        }

        // Setup Pengaduan / Konsultasi Submit
        binding.btnSubmitReport.setOnClickListener {
            val cat = binding.spinnerReportCategory.selectedItem.toString()
            val subject = binding.etReportSubject.text.toString().trim()
            val message = binding.etReportMessage.text.toString().trim()
            val isAnon = if (isParentMode) false else isAnonymousReport

            if (subject.isEmpty() || message.isEmpty()) {
                Toast.makeText(this, "Harap isi judul dan keterangan pengaduan", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefix = if (isParentMode) "[KONSULTASI WALI MURID] " else ""
            val req = SendReportRequest(
                category = if (isParentMode) "WALI_MURID" else cat,
                subject = prefix + subject,
                message = message,
                isAnonymous = isAnon,
                studentId = targetStudentId
            )

            val successMsg = if (isParentMode)
                "✅ Pesan konsultasi wali murid berhasil dikirim ke Guru BK!"
            else if (isAnon)
                "✅ Laporan berhasil dikirim secara anonim (identitas dirahasiakan)!"
            else
                "✅ Konsultasi berhasil dikirim ke Guru BK!"
            sendReportToServer(req, successMsg)
        }
    }

    private fun sendReportToServer(req: SendReportRequest, successMsg: String) {
        ApiClient.getClient(this).sendStudentReport(req).enqueue(object : Callback<BasicResponse> {
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                if (response.isSuccessful) {
                    Toast.makeText(this@BkActivity, successMsg, Toast.LENGTH_LONG).show()
                    binding.etAppointmentTopic.text.clear()
                    binding.etAppointmentDate.text.clear()
                    binding.etAppointmentTime.text.clear()
                    binding.etReportSubject.text.clear()
                    binding.etReportMessage.text.clear()
                    loadConsultations()
                    switchBkTab(0)
                } else {
                    Toast.makeText(this@BkActivity, "Gagal mengirim ke server BK", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                Toast.makeText(this@BkActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun loadConsultations() {
        if (isCounselorMode) {
            ApiClient.getClient(this).getAllBkConsultations().enqueue(object : Callback<List<ConsultationDto>> {
                override fun onResponse(call: Call<List<ConsultationDto>>, response: Response<List<ConsultationDto>>) {
                    if (response.isSuccessful && response.body() != null) {
                        val list = response.body()!!
                        renderConsultationList(list)
                    }
                }

                override fun onFailure(call: Call<List<ConsultationDto>>, t: Throwable) {
                    // Ignore network error in background refresh
                }
            })
        } else {
            val queryId = targetStudentId ?: if (isParentMode) null else sessionManager.getUserId()
            ApiClient.getClient(this).getStudentConsultations(queryId).enqueue(object : Callback<List<ConsultationDto>> {
                override fun onResponse(call: Call<List<ConsultationDto>>, response: Response<List<ConsultationDto>>) {
                    if (response.isSuccessful && response.body() != null) {
                        val list = response.body()!!
                        renderConsultationList(list)
                    }
                }

                override fun onFailure(call: Call<List<ConsultationDto>>, t: Throwable) {
                    // Ignore network error in background refresh
                }
            })
        }
    }

    private fun renderConsultationList(list: List<ConsultationDto>) {
        binding.containerBkMessages.removeAllViews()
        binding.tvBkMessageCount.text = "${list.size} Konsultasi"

        val density = resources.displayMetrics.density

        if (list.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = if (isCounselorMode)
                    "Belum ada pengaduan atau konsultasi yang masuk ke ruang BK."
                else if (isParentMode)
                    "Belum ada riwayat konsultasi tanya jawab dengan Guru BK.\nSilakan sampaikan pertanyaan atau konsultasi baru di formulir bawah."
                else
                    "Belum ada riwayat sesi konseling atau pengaduan."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(30, 30, 30, 30)
                setBackgroundColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
            }
            binding.containerBkMessages.addView(tvEmpty)
            return
        }

        list.forEach { c ->
            val card = CardView(this).apply {
                radius = 12 * density
                cardElevation = 2f * density
                useCompatPadding = true
                setCardBackgroundColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                layoutParams = lp
            }

            val cardInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (14 * density).toInt()
                setPadding(p, p, p, p)
            }

            // Header: Category & Status Badge
            val rowHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvCategory = TextView(this).apply {
                text = c.category ?: "Bimbingan BK"
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowHeader.addView(tvCategory)

            val isResolved = (c.status == "RESOLVED" || c.status == "COMPLETED" || c.status == "SELESAI")
            val isScheduled = (c.status == "SCHEDULED")
            val isReplied = (c.status == "REPLIED" || !c.replyMessage.isNullOrEmpty()) && !isResolved && !isScheduled

            val (statusText, statusBg, statusColor) = when {
                isResolved -> Triple("✔ SELESAI", "#DCFCE7", "#15803D")
                isScheduled -> Triple("📅 JADWAL TATAP MUKA", "#EEF2FF", "#3730A3")
                isReplied -> Triple("💬 DIBALAS GURU BK", "#CCFBF1", "#0D9488")
                else -> Triple("⏳ MENUNGGU RESPON BK", "#FEF3C7", "#92400E")
            }

            val tvStatus = TextView(this).apply {
                text = statusText
                setTextColor(Color.parseColor(statusColor))
                val sBadgeBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor(statusBg))
                    cornerRadius = 6 * density
                    if (isResolved) setStroke((1 * density).toInt(), Color.parseColor("#86EFAC"))
                }
                background = sBadgeBg
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }
            rowHeader.addView(tvStatus)
            cardInner.addView(rowHeader)

            // Counselor view: student identity
            if (isCounselorMode) {
                val studentName = if (c.isAnonymous) {
                    "🕵️ Siswa Anonim (Whistleblower Protection)"
                } else {
                    "👤 ${c.student?.name ?: "Siswa"} • Kelas ${c.student?.className ?: "-"}"
                }
                val tvStudentInfo = TextView(this).apply {
                    text = studentName
                    setTextColor(Color.parseColor("#4338CA"))
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt())
                }
                cardInner.addView(tvStudentInfo)
            }

            // ================= 1. KOTAK SUBJEK / TOPIK KONSELING =================
            val subjectBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val sBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F8FAFC"))
                    cornerRadius = 8 * density
                    setStroke((1 * density).toInt(), Color.parseColor("#E2E8F0"))
                }
                background = sBg
                val sp = (10 * density).toInt()
                setPadding(sp, (8 * density).toInt(), sp, (8 * density).toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * density).toInt()
                }
                layoutParams = lp
            }

            val tvSubjLabel = TextView(this).apply {
                text = "📌 SUBJEK / PERIHAL KONSELING:"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
            }
            subjectBox.addView(tvSubjLabel)

            val tvSubject = TextView(this).apply {
                text = c.subject
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            subjectBox.addView(tvSubject)
            cardInner.addView(subjectBox)

            // ================= 2. KOTAK PESAN / PENGADUAN SISWA =================
            val studentMsgBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val mBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F0F9FF"))
                    cornerRadius = 8 * density
                    setStroke((1 * density).toInt(), Color.parseColor("#BAE6FD"))
                }
                background = mBg
                val mp = (10 * density).toInt()
                setPadding(mp, (8 * density).toInt(), mp, (8 * density).toInt())
                val mlp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * density).toInt()
                }
                layoutParams = mlp
            }

            val tvStudentMsgLabel = TextView(this).apply {
                text = if (isParentMode) "👤 Pesan / Pertanyaan Wali Murid:" else "👤 Pesan Pengaduan / Konseling Siswa:"
                setTextColor(Color.parseColor("#0284C7"))
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
            }
            studentMsgBox.addView(tvStudentMsgLabel)

            val tvMsg = TextView(this).apply {
                text = c.message
                setTextColor(Color.parseColor("#0C4A6E"))
                textSize = 11.5f
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            studentMsgBox.addView(tvMsg)
            cardInner.addView(studentMsgBox)

            // ================= 3. KOTAK JAWABAN / TANGGAPAN GURU BK =================
            if (c.status == "SCHEDULED") {
                val schedCard = CardView(this).apply {
                    radius = 10 * density
                    cardElevation = 2 * density
                    setCardBackgroundColor(Color.parseColor("#EEF2FF"))
                    useCompatPadding = true
                    val slp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * density).toInt()
                    }
                    layoutParams = slp
                }

                val schedInner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val sp = (12 * density).toInt()
                    setPadding(sp, sp, sp, sp)
                }

                val tvSchedHeader = TextView(this).apply {
                    text = "📅 UNDANGAN PERTEMUAN TATAP MUKA RUANG BK"
                    setTextColor(Color.parseColor("#3730A3"))
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                }
                schedInner.addView(tvSchedHeader)

                val reply = c.replyMessage ?: ""
                val dateMatch = Regex("""(?:Hari/Tanggal|Tanggal|Hari)\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE).find(reply)
                val timeMatch = Regex("""(?:Waktu|Jam|Pukul)\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE).find(reply)
                val locMatch = Regex("""(?:Tempat|Lokasi|Ruang)\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE).find(reply)

                val dateStr = dateMatch?.groupValues?.get(1)?.trim()
                    ?: (if (!c.replyDate.isNullOrEmpty()) c.replyDate.take(10) else "Sesuai Jadwal Ruang BK")
                val timeStr = timeMatch?.groupValues?.get(1)?.trim()
                    ?: "Waktu Istirahat / Sesi Khusus BK"
                val locStr = locMatch?.groupValues?.get(1)?.trim()
                    ?: "Ruang Bimbingan & Konseling (BK)"

                val cleanNotes = reply
                    .replace(Regex("""📌\s*JADWAL PERTEMUAN[^\n]*""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""•\s*(?:Hari/Tanggal|Tanggal|Waktu|Jam|Pukul|Tempat|Lokasi)[^\n]*""", RegexOption.IGNORE_CASE), "")
                    .trim()
                val notesStr = if (cleanNotes.isNotEmpty()) cleanNotes else "Harap hadir tepat waktu di Ruang BK untuk pendampingan konseling tatap muka."

                fun addInfoRow(icon: String, label: String, value: String) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                    }
                    val tvL = TextView(this).apply {
                        text = "$icon $label: "
                        setTextColor(Color.parseColor("#4338CA"))
                        textSize = 11.5f
                        setTypeface(null, Typeface.BOLD)
                    }
                    val tvV = TextView(this).apply {
                        text = value
                        setTextColor(Color.parseColor("#1E1B4B"))
                        textSize = 11.5f
                    }
                    row.addView(tvL)
                    row.addView(tvV)
                    schedInner.addView(row)
                }

                addInfoRow("🗓️", "Tanggal", dateStr)
                addInfoRow("⏰", "Waktu", timeStr)
                addInfoRow("📍", "Lokasi", locStr)

                val notesContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#FFFFFF"))
                    val np = (8 * density).toInt()
                    setPadding(np, np, np, np)
                    val nlp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (6 * density).toInt()
                    }
                    layoutParams = nlp
                }
                val tvNoteTitle = TextView(this).apply {
                    text = "📝 Catatan & Arahan Guru BK:"
                    setTextColor(Color.parseColor("#475569"))
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                }
                val tvNoteBody = TextView(this).apply {
                    text = notesStr
                    setTextColor(Color.parseColor("#0F172A"))
                    textSize = 11f
                    setPadding(0, (2 * density).toInt(), 0, 0)
                }
                notesContainer.addView(tvNoteTitle)
                notesContainer.addView(tvNoteBody)
                schedInner.addView(notesContainer)

                schedCard.addView(schedInner)
                cardInner.addView(schedCard)
            } else if (!c.replyMessage.isNullOrEmpty()) {
                val replyBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val rBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#F0FDF4"))
                        cornerRadius = 8 * density
                        setStroke((1 * density).toInt(), if (isResolved) Color.parseColor("#86EFAC") else Color.parseColor("#BBF7D0"))
                    }
                    background = rBg
                    val rp = (10 * density).toInt()
                    setPadding(rp, (8 * density).toInt(), rp, (8 * density).toInt())
                    val rlp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * density).toInt()
                    }
                    layoutParams = rlp
                }

                val tvReplyTitle = TextView(this).apply {
                    text = if (isResolved) "🕊️ Tanggapan & Solusi Guru BK (STATUS: SELESAI ✔):" else "🕊️ Tanggapan & Arahan Guru BK (Konselor):"
                    setTextColor(Color.parseColor("#15803D"))
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                }
                replyBox.addView(tvReplyTitle)

                val tvReplyContent = TextView(this).apply {
                    text = c.replyMessage
                    setTextColor(Color.parseColor("#14532D"))
                    textSize = 11.5f
                    setPadding(0, (4 * density).toInt(), 0, 0)
                }
                replyBox.addView(tvReplyContent)

                if (isResolved) {
                    val tvResolvedBadge = TextView(this).apply {
                        text = "✅ Sesi konseling ini telah selesai dan dituntaskan oleh Guru BK."
                        setTextColor(Color.parseColor("#166534"))
                        textSize = 9.5f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, (6 * density).toInt(), 0, 0)
                    }
                    replyBox.addView(tvResolvedBadge)
                }

                cardInner.addView(replyBox)
            } else {
                val waitingBox = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val wBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#FFFBEB"))
                        cornerRadius = 6 * density
                    }
                    background = wBg
                    val wp = (8 * density).toInt()
                    setPadding(wp, wp, wp, wp)
                    val wlp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * density).toInt()
                    }
                    layoutParams = wlp
                }
                val tvWaiting = TextView(this).apply {
                    text = "⏳ Menunggu balasan / tanggapan resmi dari Guru BK..."
                    setTextColor(Color.parseColor("#B45309"))
                    textSize = 10f
                    setTypeface(null, Typeface.ITALIC)
                }
                waitingBox.addView(tvWaiting)
                cardInner.addView(waitingBox)
            }

            // ================= 4. TOMBOL TINDAKAN LANJUTAN =================
            if (isCounselorMode) {
                val btnReply = TextView(this).apply {
                    text = if (isResolved) "✏️ Perbarui Solusi / Status Konseling" else (if (!c.replyMessage.isNullOrEmpty()) "✏️ Edit Balasan Guru BK" else "✍️ Berikan Tanggapan Guru BK")
                    setTextColor(Color.parseColor("#0D9488"))
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
                    setOnClickListener {
                        showCounselorReplyDialog(c)
                    }
                }
                cardInner.addView(btnReply)
            } else if (isResolved) {
                val btnNewConsult = TextView(this).apply {
                    text = "✔ Sesi Konseling Selesai • Ajukan Topik Baru →"
                    setTextColor(Color.parseColor("#16A34A"))
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
                    setOnClickListener {
                        switchBkTab(1)
                    }
                }
                cardInner.addView(btnNewConsult)
            } else if (isParentMode) {
                val btnReplyThread = TextView(this).apply {
                    text = "💬 Balas / Tanya Lanjutan"
                    setTextColor(Color.parseColor("#0D9488"))
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
                    setOnClickListener {
                        showParentFollowUpDialog(c)
                    }
                }
                cardInner.addView(btnReplyThread)
            } else {
                val btnReplyThread = TextView(this).apply {
                    text = "💬 Balas / Tanggapi Guru BK"
                    setTextColor(Color.parseColor("#0D9488"))
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
                    setOnClickListener {
                        showStudentFollowUpDialog(c)
                    }
                }
                cardInner.addView(btnReplyThread)
            }

            card.addView(cardInner)
            binding.containerBkMessages.addView(card)
        }
    }

    private fun showStudentFollowUpDialog(c: ConsultationDto) {
        val cleanSubject = c.subject.trim()
        val newSubject = if (cleanSubject.startsWith("Re: ")) cleanSubject else "Re: $cleanSubject"

        val input = EditText(this).apply {
            hint = "Tulis tanggapan atau pesan balasan Anda untuk Guru BK..."
            setLines(4)
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)

            val tvTopic = TextView(this@BkActivity).apply {
                text = "📌 Topik: $newSubject"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                setPadding(0, 0, 0, 8)
            }
            addView(tvTopic)

            if (!c.replyMessage.isNullOrEmpty()) {
                val tvPrevReply = TextView(this@BkActivity).apply {
                    text = "💡 Tanggapan Guru BK:\n\"${c.replyMessage}\""
                    textSize = 11f
                    setTextColor(Color.parseColor("#047857"))
                    setBackgroundColor(Color.parseColor("#ECFDF5"))
                    setPadding(20, 16, 20, 16)
                }
                addView(tvPrevReply)
            }

            val space = View(this@BkActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, (10 * resources.displayMetrics.density).toInt())
            }
            addView(space)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("💬 Balas Tanggapan Guru BK")
            .setView(container)
            .setPositiveButton("Kirim Balasan") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) {
                    val req = SendReportRequest(
                        category = c.category ?: "BIMBINGAN",
                        subject = newSubject,
                        message = msg,
                        isAnonymous = c.isAnonymous,
                        studentId = sessionManager.getUserId()
                    )
                    sendReportToServer(req, "✅ Pesan balasan berhasil dikirim ke Guru BK!")
                } else {
                    Toast.makeText(this@BkActivity, "Pesan tidak boleh kosong", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Buka Form Lengkap") { _, _ ->
                switchBkTab(1)
                binding.etReportSubject.setText(newSubject)
                binding.scrollViewBk.post {
                    binding.scrollViewBk.fullScroll(View.FOCUS_DOWN)
                    binding.etReportMessage.requestFocus()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showParentFollowUpDialog(c: ConsultationDto) {
        val cleanSubject = c.subject.replace(Regex("^\\[KONSULTASI WALI MURID\\]\\s*"), "").trim()
        val newSubject = if (cleanSubject.startsWith("Re: ")) cleanSubject else "Re: $cleanSubject"

        val input = EditText(this).apply {
            hint = "Tulis pertanyaan lanjutan atau tanggapan Anda untuk Guru BK..."
            setLines(4)
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)

            val tvTopic = TextView(this@BkActivity).apply {
                text = "📌 Topik: $newSubject"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                setPadding(0, 0, 0, 8)
            }
            addView(tvTopic)

            if (!c.replyMessage.isNullOrEmpty()) {
                val tvPrevReply = TextView(this@BkActivity).apply {
                    text = "💡 Balasan Guru BK Sebelumnya:\n\"${c.replyMessage}\""
                    textSize = 11f
                    setTextColor(Color.parseColor("#047857"))
                    setBackgroundColor(Color.parseColor("#ECFDF5"))
                    setPadding(20, 16, 20, 16)
                }
                addView(tvPrevReply)
            }

            val space = View(this@BkActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, (10 * resources.displayMetrics.density).toInt())
            }
            addView(space)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("💬 Balas Konsultasi Guru BK")
            .setView(container)
            .setPositiveButton("Kirim Pesan Lanjutan") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) {
                    val req = SendReportRequest(
                        category = "WALI_MURID",
                        subject = "[KONSULTASI WALI MURID] $newSubject",
                        message = msg,
                        isAnonymous = false,
                        studentId = targetStudentId
                    )
                    sendReportToServer(req, "✅ Pesan lanjutan berhasil dikirim ke Guru BK!")
                } else {
                    Toast.makeText(this@BkActivity, "Pesan tidak boleh kosong", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Buka Form Lengkap") { _, _ ->
                switchBkTab(1)
                binding.etReportSubject.setText(newSubject)
                binding.scrollViewBk.post {
                    binding.scrollViewBk.fullScroll(View.FOCUS_DOWN)
                    binding.etReportMessage.requestFocus()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showCounselorReplyDialog(c: ConsultationDto) {
        val input = EditText(this).apply {
            hint = "Tulis tanggapan, arahan, atau solusi Guru BK..."
            setText(c.replyMessage ?: "")
            setLines(4)
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        val cbResolved = CheckBox(this).apply {
            text = "✅ Tandai Penanganan Konseling Ini Sudah SELESAI (Resolved)"
            setTextColor(Color.parseColor("#15803D"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            isChecked = (c.status == "RESOLVED" || c.status == "COMPLETED" || c.status == "SELESAI")
            setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
            addView(input)
            val spacer = View(this@BkActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, (10 * resources.displayMetrics.density).toInt())
            }
            addView(spacer)
            addView(cbResolved)
        }

        AlertDialog.Builder(this)
            .setTitle("Tanggapan Guru BK")
            .setMessage("Topik: ${c.subject}\nPengirim: ${if (c.isAnonymous) "Anonim (Whistleblower)" else c.student?.name ?: "Siswa"}")
            .setView(container)
            .setPositiveButton("Kirim Balasan") { _, _ ->
                val reply = input.text.toString().trim()
                if (reply.isNotEmpty()) {
                    val targetStatus = if (cbResolved.isChecked) "RESOLVED" else "REPLIED"
                    ApiClient.getClient(this).replyBkConsultation(
                        c.id,
                        ReplyBkRequest(replyMessage = reply, status = targetStatus)
                    ).enqueue(object : Callback<BasicResponse> {
                        override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                            if (response.isSuccessful) {
                                val statusMsg = if (cbResolved.isChecked) "✅ Tanggapan dikirim & status ditandai SELESAI!" else "✅ Tanggapan berhasil dikirim!"
                                Toast.makeText(this@BkActivity, statusMsg, Toast.LENGTH_SHORT).show()
                                loadConsultations()
                            } else {
                                Toast.makeText(this@BkActivity, "Gagal mengirim tanggapan", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                            Toast.makeText(this@BkActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun loadStudentProfile() {
        ApiClient.getClient(this).getStudentProfile(targetStudentId).enqueue(object : Callback<StudentProfileResponse> {
            override fun onResponse(call: Call<StudentProfileResponse>, response: Response<StudentProfileResponse>) {
                val user = response.body()?.user ?: response.body()?.data
                if (user != null) {
                    val counselor = user.counselorTeacher
                    if (!counselor.isNullOrBlank()) {
                        binding.tvBkCounselorName.text = "$counselor (Guru BK Pendamping)"
                    } else {
                        val cls = user.className ?: sessionManager.getClassName()
                        binding.tvBkCounselorName.text = "Guru BK Kelas $cls"
                    }
                    val points = user.points ?: 100
                    binding.tvBkTotalPoints.text = "$points Poin"
                    if (points >= 90) {
                        binding.tvBkSpBadge.text = "🟢 Sangat Baik (Bebas SP)"
                        binding.tvBkSpBadge.setTextColor(Color.parseColor("#065F46"))
                        binding.tvBkSpBadge.setBackgroundColor(Color.parseColor("#D1FAE5"))
                    } else if (points >= 75) {
                        binding.tvBkSpBadge.text = "🟡 Peringatan 1 (SP-1)"
                        binding.tvBkSpBadge.setTextColor(Color.parseColor("#92400E"))
                        binding.tvBkSpBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
                    } else if (points >= 50) {
                        binding.tvBkSpBadge.text = "🟠 Peringatan 2 (SP-2)"
                        binding.tvBkSpBadge.setTextColor(Color.parseColor("#9A3412"))
                        binding.tvBkSpBadge.setBackgroundColor(Color.parseColor("#FFEDD5"))
                    } else {
                        binding.tvBkSpBadge.text = "🔴 Panggilan Ortu (SP-3)"
                        binding.tvBkSpBadge.setTextColor(Color.parseColor("#991B1B"))
                        binding.tvBkSpBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
                    }
                }
            }

            override fun onFailure(call: Call<StudentProfileResponse>, t: Throwable) {
                // Ignore profile fetch failure
            }
        })
    }

    private fun setupCounselorFeatures() {
        binding.btnRefreshBkRecap.setOnClickListener {
            loadBkClassRecap(currentSelectedClass)
        }

        binding.spinnerBkClassFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (allowedBkClasses.isNotEmpty() && position < allowedBkClasses.size) {
                    val sel = allowedBkClasses[position]
                    currentSelectedClass = if (sel.startsWith("Semua")) null else sel
                    loadBkClassRecap(currentSelectedClass)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnRefreshBkLeaves.setOnClickListener {
            loadPendingStudentLeaves()
        }

        binding.btnOpenBkDynamicQr.setOnClickListener {
            showDynamicRotatingQrDialog()
        }

        binding.btnOpenBkManualForm.setOnClickListener {
            showBkManualAttendanceDialog()
        }

        binding.btnRefreshBk360.setOnClickListener {
            loadBk360AttendanceRecap()
        }
    }


    private fun loadBkClassRecap(className: String?) {
        ApiClient.getClient(this).getBkClassRecap(className).enqueue(object : Callback<BkClassRecapResponse> {
            override fun onResponse(call: Call<BkClassRecapResponse>, response: Response<BkClassRecapResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    val classes = data.allowedClasses ?: emptyList()
                    if (allowedBkClasses.isEmpty() && classes.isNotEmpty()) {
                        allowedBkClasses = listOf("Semua Kelas Ampuan") + classes
                        val adapter = ArrayAdapter(this@BkActivity, android.R.layout.simple_spinner_dropdown_item, allowedBkClasses)
                        binding.spinnerBkClassFilter.adapter = adapter
                    }

                    val students = data.students ?: emptyList()
                    renderBkRecap(students, data.selectedClass ?: "Semua")
                } else {
                    Toast.makeText(this@BkActivity, "Gagal memuat rekap kelas BK", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<BkClassRecapResponse>, t: Throwable) {
                Toast.makeText(this@BkActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun renderBkRecap(students: List<BkStudentRecapDto>, selectedClass: String) {
        binding.tvBkRecapHeaderTitle.text = "👥 SISWA KELAS ${selectedClass.uppercase()}"
        binding.tvBkRecapStudentCount.text = "${students.size} Siswa"

        var totalH = 0
        var totalT = 0
        var totalS = 0
        var totalI = 0
        var totalA = 0
        var totalSp = 0

        students.forEach { s ->
            totalH += s.stats?.hadir ?: 0
            totalT += s.stats?.terlambat ?: 0
            totalS += s.stats?.sakit ?: 0
            totalI += s.stats?.izin ?: 0
            totalA += s.stats?.alpa ?: 0
            if (s.spLevel != null && s.spLevel != "Aman") {
                totalSp++
            }
        }

        binding.tvBkRecapTotalHadir.text = totalH.toString()
        binding.tvBkRecapTotalTerlambat.text = totalT.toString()
        binding.tvBkRecapTotalSakitIzin.text = (totalS + totalI).toString()
        binding.tvBkRecapTotalAlpa.text = totalA.toString()
        binding.tvBkRecapTotalSp.text = totalSp.toString()

        val container = binding.containerBkRecapStudents
        container.removeAllViews()

        if (students.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "Tidak ada data siswa untuk kelas yang dipilih."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(30, 30, 30, 30)
                gravity = android.view.Gravity.CENTER
            }
            container.addView(tvEmpty)
            return
        }

        val density = resources.displayMetrics.density

        students.forEach { s ->
            val card = CardView(this).apply {
                radius = 12 * density
                cardElevation = 2f * density
                useCompatPadding = true
                setCardBackgroundColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                layoutParams = lp
            }

            val cardInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (12 * density).toInt()
                setPadding(p, p, p, p)
            }

            // Top Row: Name + Class Badge
            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                text = s.name
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowTop.addView(tvName)

            val tvClass = TextView(this).apply {
                text = s.className ?: "-"
                setTextColor(Color.parseColor("#065F46"))
                setBackgroundColor(Color.parseColor("#D1FAE5"))
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
            }
            rowTop.addView(tvClass)
            cardInner.addView(rowTop)

            // Sub: NISN & Gender & Parent Phone
            val tvSub = TextView(this).apply {
                text = "NISN: ${s.nisn ?: "-"} • Gender: ${s.gender ?: "-"} • Telp Ortu: ${s.parentPhone ?: "-"}"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 10.5f
                setPadding(0, (2 * density).toInt(), 0, (6 * density).toInt())
            }
            cardInner.addView(tvSub)

            // Attendance Badges Row
            val rowAtt = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            fun createBadge(label: String, count: Int, bgHex: String, textHex: String): TextView {
                return TextView(this).apply {
                    text = "$label: $count"
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(textHex))
                    setBackgroundColor(Color.parseColor(bgHex))
                    val pad = (6 * density).toInt()
                    setPadding(pad, (2 * density).toInt(), pad, (2 * density).toInt())
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    layoutParams = lp
                }
            }

            val st = s.stats ?: BkStudentStatsDto()
            rowAtt.addView(createBadge("H", st.hadir, "#DCFCE7", "#15803D"))
            rowAtt.addView(createBadge("T", st.terlambat, "#FEF3C7", "#B45309"))
            rowAtt.addView(createBadge("S", st.sakit, "#E0E7FF", "#3730A3"))
            rowAtt.addView(createBadge("I", st.izin, "#CFFAFE", "#0E7490"))
            rowAtt.addView(createBadge("A", st.alpa, "#FEE2E2", "#B91C1C"))
            cardInner.addView(rowAtt)

            // Discipline Points & SP Level
            val rowPoints = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (6 * density).toInt()
                }
                layoutParams = lp
            }

            val tvPoints = TextView(this).apply {
                val pts = s.points ?: 0
                text = "⚖️ Poin Disiplin: $pts"
                setTextColor(if (pts > 30) Color.parseColor("#DC2626") else Color.parseColor("#0F766E"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowPoints.addView(tvPoints)

            val spText = s.spLevel ?: "Aman"
            val tvSp = TextView(this).apply {
                text = "Status: $spText"
                val (spBg, spFg) = when {
                    spText.contains("SP-3") -> Pair("#FEE2E2", "#991B1B")
                    spText.contains("SP-2") -> Pair("#FFEDD5", "#9A3412")
                    spText.contains("SP-1") -> Pair("#FEF3C7", "#92400E")
                    else -> Pair("#ECFDF5", "#065F46")
                }
                setBackgroundColor(Color.parseColor(spBg))
                setTextColor(Color.parseColor(spFg))
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
            }
            rowPoints.addView(tvSp)
            cardInner.addView(rowPoints)

            // Action Button: Catat Poin Disiplin
            val btnDiscipline = Button(this).apply {
                text = "➕ Catat Poin Kedisiplinan Siswa"
                textSize = 11f
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (38 * density).toInt()).apply {
                    topMargin = (8 * density).toInt()
                }
                layoutParams = lp
                setOnClickListener {
                    showCreateDisciplineDialog(s.id, s.name, s.className ?: "-")
                }
            }
            cardInner.addView(btnDiscipline)

            card.addView(cardInner)
            container.addView(card)
        }
    }

    private fun loadBkTodayActivity() {
        ApiClient.getClient(this).getBkTodayActivity().enqueue(object : Callback<BkTodayActivityResponse> {
            override fun onResponse(call: Call<BkTodayActivityResponse>, response: Response<BkTodayActivityResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    renderTodayActivity(data)
                }
            }
            override fun onFailure(call: Call<BkTodayActivityResponse>, t: Throwable) {
                // Background refresh ignore
            }
        })
    }

    private fun renderTodayActivity(data: BkTodayActivityResponse) {
        val density = resources.displayMetrics.density
        val lates = data.lateArrivals ?: emptyList()
        val earlies = data.earlyLeaves ?: emptyList()

        binding.tvBkTodayLateCount.text = "${lates.size} Siswa Terlambat di Ruang BK Hari Ini"
        binding.tvBkTodayEarlyCount.text = "${earlies.size} Siswa Izin Pulang Cepat Disetujui BK Hari Ini"

        val containerLate = binding.containerBkTodayLate
        containerLate.removeAllViews()

        if (lates.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "✅ Tidak ada siswa terlambat yang tercatat di ruang BK hari ini."
                setTextColor(Color.parseColor("#10B981"))
                textSize = 11.5f
                setPadding(20, 16, 20, 16)
                setBackgroundColor(Color.parseColor("#ECFDF5"))
            }
            containerLate.addView(tvEmpty)
        } else {
            lates.forEach { item ->
                val card = CardView(this).apply {
                    radius = 10 * density
                    cardElevation = 1.5f * density
                    useCompatPadding = true
                    setCardBackgroundColor(Color.WHITE)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = (6 * density).toInt()
                    }
                    layoutParams = lp
                }
                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = (10 * density).toInt()
                    setPadding(p, p, p, p)
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val tvName = TextView(this).apply {
                    text = "🚨 ${item.studentName ?: "Siswa"} (${item.className ?: "-"})"
                    setTextColor(Color.parseColor("#DC2626"))
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(tvName)

                val timeStr = item.scanTime?.let {
                    try {
                        val d = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(it)
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(d ?: java.util.Date())
                    } catch (e: Exception) { it }
                } ?: "-"
                val tvTime = TextView(this).apply {
                    text = "🕒 $timeStr WIB"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 11f
                }
                row.addView(tvTime)
                inner.addView(row)

                val tvNote = TextView(this).apply {
                    text = item.note ?: "Terlambat di Ruang BK (Poin +5)"
                    setTextColor(Color.parseColor("#475569"))
                    textSize = 11f
                    setPadding(0, 4, 0, 0)
                }
                inner.addView(tvNote)

                card.addView(inner)
                containerLate.addView(card)
            }
        }

        val containerEarly = binding.containerBkTodayEarly
        containerEarly.removeAllViews()

        if (earlies.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "ℹ️ Tidak ada siswa izin pulang cepat hari ini."
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11.5f
                setPadding(20, 16, 20, 16)
                setBackgroundColor(Color.parseColor("#F1F5F9"))
            }
            containerEarly.addView(tvEmpty)
        } else {
            earlies.forEach { item ->
                val card = CardView(this).apply {
                    radius = 10 * density
                    cardElevation = 1.5f * density
                    useCompatPadding = true
                    setCardBackgroundColor(Color.WHITE)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = (6 * density).toInt()
                    }
                    layoutParams = lp
                }
                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = (10 * density).toInt()
                    setPadding(p, p, p, p)
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val tvName = TextView(this).apply {
                    text = "🚪 ${item.studentName ?: "Siswa"} (${item.className ?: "-"})"
                    setTextColor(Color.parseColor("#2563EB"))
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(tvName)

                val timeStr = item.scanTime?.let {
                    try {
                        val d = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(it)
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(d ?: java.util.Date())
                    } catch (e: Exception) { it }
                } ?: "-"
                val tvTime = TextView(this).apply {
                    text = "🕒 $timeStr WIB"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 11f
                }
                row.addView(tvTime)
                inner.addView(row)

                val tvNote = TextView(this).apply {
                    text = item.note ?: "Izin Pulang Cepat Disetujui BK"
                    setTextColor(Color.parseColor("#475569"))
                    textSize = 11f
                    setPadding(0, 4, 0, 0)
                }
                inner.addView(tvNote)

                card.addView(inner)
                containerEarly.addView(card)
            }
        }
    }

    private fun showCreateDisciplineDialog(studentId: String, studentName: String, className: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val tvInfo = TextView(this).apply {
            text = "Catat Kedisiplinan: $studentName ($className)"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }
        container.addView(tvInfo)

        val rgType = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        val rbPelanggaran = android.widget.RadioButton(this).apply {
            text = "Pelanggaran (+Poin)"
            isChecked = true
            setTextColor(Color.parseColor("#DC2626"))
        }
        val rbPrestasi = android.widget.RadioButton(this).apply {
            text = "Prestasi (-Poin)"
            setTextColor(Color.parseColor("#059669"))
        }
        rgType.addView(rbPelanggaran)
        rgType.addView(rbPrestasi)
        container.addView(rgType)

        val etPoints = EditText(this).apply {
            hint = "Jumlah Poin (contoh: 5, 10, 20)"
            setText("5")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            layoutParams = lp
        }
        container.addView(etPoints)

        val etDesc = EditText(this).apply {
            hint = "Keterangan / Alasan (contoh: Terlambat masuk ruang BK)"
            setLines(3)
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            layoutParams = lp
        }
        container.addView(etDesc)

        AlertDialog.Builder(this)
            .setTitle("⚖️ Catat Poin Kedisiplinan")
            .setView(container)
            .setPositiveButton("Simpan Poin") { _, _ ->
                val type = if (rbPelanggaran.isChecked) "PELANGGARAN" else "PRESTASI"
                val pts = etPoints.text.toString().toIntOrNull() ?: 5
                val desc = etDesc.text.toString().trim()

                if (desc.isEmpty()) {
                    Toast.makeText(this, "Harap isi keterangan pelanggaran / prestasi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val req = CreateDisciplineRequest(
                    studentId = studentId,
                    type = type,
                    description = desc,
                    points = pts
                )

                Toast.makeText(this, "Menyimpan catatan kedisiplinan...", Toast.LENGTH_SHORT).show()
                ApiClient.getClient(this).createDisciplineRecord(req).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@BkActivity, "✅ Catatan kedisiplinan berhasil disimpan!", Toast.LENGTH_LONG).show()
                            loadBkClassRecap(currentSelectedClass)
                        } else {
                            Toast.makeText(this@BkActivity, "Gagal menyimpan kedisiplinan", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@BkActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun loadPendingStudentLeaves() {
        Toast.makeText(this, "Memuat permohonan izin siswa...", Toast.LENGTH_SHORT).show()
        ApiClient.getClient(this).getPendingStudentLeavesForBk(null, "PENDING").enqueue(object : Callback<PendingStudentLeavesResponse> {
            override fun onResponse(call: Call<PendingStudentLeavesResponse>, response: Response<PendingStudentLeavesResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    val leaves = data.leaves ?: emptyList()
                    binding.tvBkLeavesSummary.text = "Terdapat ${leaves.size} permohonan izin siswa yang menunggu verifikasi BK"
                    renderBkLeaves(leaves)
                } else {
                    Toast.makeText(this@BkActivity, "Gagal memuat izin siswa", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<PendingStudentLeavesResponse>, t: Throwable) {
                Toast.makeText(this@BkActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun renderBkLeaves(leaves: List<StudentLeaveRequestDto>) {
        val container = binding.llBkLeavesList
        container.removeAllViews()

        if (leaves.isEmpty()) {
            val cardEmpty = CardView(this).apply {
                radius = 12f * resources.displayMetrics.density
                cardElevation = 1f * resources.displayMetrics.density
                setCardBackgroundColor(Color.parseColor("#F8FAFC"))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                layoutParams = lp
            }
            val tvEmpty = TextView(this).apply {
                text = "🎉 Tidak ada permohonan izin/sakit yang menunggu verifikasi saat ini."
                setTextColor(Color.parseColor("#64748B"))
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(30, 40, 30, 40)
            }
            cardEmpty.addView(tvEmpty)
            container.addView(cardEmpty)
            return
        }

        leaves.forEach { item ->
            val card = CardView(this).apply {
                radius = 12f * resources.displayMetrics.density
                cardElevation = 2f * resources.displayMetrics.density
                setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 14
                }
                layoutParams = lp
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                text = "${item.studentName} (${item.className})"
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }
            topRow.addView(tvName)

            val catLabel = when (item.category.uppercase()) {
                "SICK" -> "🤒 SAKIT"
                "PERMISSION" -> "✉️ IZIN"
                "DISPENSATION" -> "🎖️ DISPENSASI"
                else -> item.category
            }
            val catColor = when (item.category.uppercase()) {
                "SICK" -> "#EA580C"
                "PERMISSION" -> "#2563EB"
                "DISPENSATION" -> "#7C3AED"
                else -> "#475569"
            }

            val tvCatBadge = TextView(this).apply {
                text = catLabel
                setTextColor(Color.parseColor(catColor))
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#F1F5F9"))
                setPadding(14, 4, 14, 4)
            }
            topRow.addView(tvCatBadge)
            inner.addView(topRow)

            val sDate = item.startDate.take(10)
            val eDate = item.endDate.take(10)
            val tvDates = TextView(this).apply {
                text = "📅 Periode: $sDate s/d $eDate"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11.5f
                setPadding(0, 8, 0, 0)
            }
            inner.addView(tvDates)

            val tvReason = TextView(this).apply {
                text = "Keterangan: ${item.reason}"
                setTextColor(Color.parseColor("#334155"))
                textSize = 12f
                setPadding(0, 4, 0, 0)
            }
            inner.addView(tvReason)

            val tvParent = TextView(this).apply {
                text = "Diajukan oleh: ${item.parentName} (${item.parentPhone ?: "-"})"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
                setPadding(0, 4, 0, 0)
            }
            inner.addView(tvParent)

            if (!item.attachmentUrl.isNullOrEmpty()) {
                val btnViewPhoto = Button(this).apply {
                    text = "📎 Lihat Foto Bukti / Surat Dokter"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#2563EB"))
                    setBackgroundColor(Color.parseColor("#EFF6FF"))
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * resources.displayMetrics.density).toInt()).apply {
                        topMargin = 10
                    }
                    layoutParams = lp
                    setOnClickListener {
                        showProofPhotoDialog(item.studentName, item.attachmentUrl!!)
                    }
                }
                inner.addView(btnViewPhoto)
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 10
                }
                layoutParams = lp
            }

            val btnApprove = Button(this).apply {
                text = "✅ Setujui Izin"
                textSize = 11.5f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#059669"))
                val lp = LinearLayout.LayoutParams(0, (40 * resources.displayMetrics.density).toInt(), 1f).apply {
                    marginEnd = 6
                }
                layoutParams = lp
                setOnClickListener {
                    verifyStudentLeave(item.id, "APPROVED", null)
                }
            }
            btnRow.addView(btnApprove)

            val btnReject = Button(this).apply {
                text = "❌ Tolak"
                textSize = 11.5f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DC2626"))
                val lp = LinearLayout.LayoutParams(0, (40 * resources.displayMetrics.density).toInt(), 1f).apply {
                    marginStart = 6
                }
                layoutParams = lp
                setOnClickListener {
                    showRejectLeaveDialog(item.id, item.studentName)
                }
            }
            btnRow.addView(btnReject)

            inner.addView(btnRow)
            card.addView(inner)
            container.addView(card)
        }
    }

    private fun showRejectLeaveDialog(leaveId: String, studentName: String) {
        val input = EditText(this).apply {
            hint = "Alasan penolakan izin (contoh: Bukti surat tidak valid)"
            setPadding(30, 20, 30, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Tolak Izin: $studentName")
            .setView(input)
            .setPositiveButton("Tolak Izin") { _, _ ->
                val reason = input.text.toString().trim().ifEmpty { "Ditolak oleh Guru BK" }
                verifyStudentLeave(leaveId, "REJECTED", reason)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun verifyStudentLeave(id: String, status: String, note: String?) {
        val req = VerifyLeaveRequest(status = status, rejectionNote = note)
        ApiClient.getClient(this).verifyTeacherLeave(id, req).enqueue(object : Callback<BasicResponse> {
            override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                if (response.isSuccessful) {
                    val action = if (status == "APPROVED") "disetujui" else "ditolak"
                    Toast.makeText(this@BkActivity, "✅ Permohonan izin berhasil $action!", Toast.LENGTH_SHORT).show()
                    loadPendingStudentLeaves()
                } else {
                    Toast.makeText(this@BkActivity, "Gagal memproses verifikasi izin", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                Toast.makeText(this@BkActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private var dynamicQrDialog: AlertDialog? = null
    private var dynamicQrHandler: Handler? = null
    private var dynamicQrRunnable: Runnable? = null
    private var dynamicQrTtlSeconds: Int = 45

    private fun showDynamicRotatingQrDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 32)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }

        val tvTitle = TextView(this).apply {
            text = "🛡️ DYNAMIC ROTATING QR RUANG BK"
            setTextColor(Color.parseColor("#065F46"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }
        dialogView.addView(tvTitle)

        val tvSub = TextView(this).apply {
            text = "Anti-Fraud 45-Detik: Tunjukkan QR ini ke Siswa yang Berdiri di Meja BK"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11.5f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 4, 0, 16)
        }
        dialogView.addView(tvSub)

        val cardQr = CardView(this).apply {
            radius = 16 * resources.displayMetrics.density
            cardElevation = 4 * resources.displayMetrics.density
            setCardBackgroundColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams((250 * resources.displayMetrics.density).toInt(), (250 * resources.displayMetrics.density).toInt()).apply {
                gravity = android.view.Gravity.CENTER
            }
            layoutParams = lp
        }

        val ivQr = ImageView(this).apply {
            setPadding(16, 16, 16, 16)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        cardQr.addView(ivQr)
        dialogView.addView(cardQr)

        val tvTtl = TextView(this).apply {
            text = "⏳ Memuat token QR baru..."
            setTextColor(Color.parseColor("#D97706"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 4)
        }
        dialogView.addView(tvTtl)

        val tvHash = TextView(this).apply {
            text = "HMAC SHA-256 Token Protection Active"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        dialogView.addView(tvHash)

        fun fetchAndRenderQr() {
            ApiClient.getClient(this).getBkDynamicQr("RUANG_BK_UTAMA").enqueue(object : Callback<BkDynamicQrResponse> {
                override fun onResponse(call: Call<BkDynamicQrResponse>, response: Response<BkDynamicQrResponse>) {
                    val body = response.body()
                    if (response.isSuccessful && body?.success == true && body.data != null) {
                        val tokenData = body.data
                        val bmp = QrCodeHelper.generateQrCodeBitmap(tokenData.tokenPayload, 550)
                        if (bmp != null) {
                            ivQr.setImageBitmap(bmp)
                        }
                        dynamicQrTtlSeconds = tokenData.ttlSeconds
                        tvTtl.text = "⏳ Berputar otomatis dalam ${dynamicQrTtlSeconds}s"
                        tvHash.text = "Token: ${tokenData.currentTokenHash.take(12)}... • Window #${tokenData.windowIndex}"
                    } else {
                        tvTtl.text = "⚠️ Gagal memuat token"
                    }
                }
                override fun onFailure(call: Call<BkDynamicQrResponse>, t: Throwable) {
                    tvTtl.text = "⚠️ Koneksi server terputus: ${t.message}"
                }
            })
        }

        fetchAndRenderQr()

        dynamicQrHandler = Handler(Looper.getMainLooper())
        dynamicQrRunnable = object : Runnable {
            override fun run() {
                dynamicQrTtlSeconds--
                if (dynamicQrTtlSeconds <= 0) {
                    fetchAndRenderQr()
                } else {
                    tvTtl.text = "⏳ Berputar otomatis dalam ${dynamicQrTtlSeconds}s"
                }
                dynamicQrHandler?.postDelayed(this, 1000)
            }
        }
        dynamicQrHandler?.postDelayed(dynamicQrRunnable!!, 1000)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Tutup") { d, _ ->
                d.dismiss()
            }
            .setOnDismissListener {
                dynamicQrRunnable?.let { dynamicQrHandler?.removeCallbacks(it) }
                dynamicQrHandler = null
            }
            .create()

        dynamicQrDialog = dialog
        dialog.show()
    }

    private fun showProofPhotoDialog(studentName: String, photoUrl: String) {
        val sv = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val tvInfo = TextView(this).apply {
            text = "📄 Foto Bukti Surat / Keterangan Dokter"
            setTextColor(Color.parseColor("#1E293B"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        container.addView(tvInfo)

        val ivPhoto = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                height = (320 * resources.displayMetrics.density).toInt()
            }
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#F1F5F9"))
        }
        container.addView(ivPhoto)

        val tvFooter = TextView(this).apply {
            text = "Diunggah langsung oleh orang tua siswa melalui aplikasi SmartCBT."
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            setPadding(0, 10, 0, 0)
        }
        container.addView(tvFooter)
        sv.addView(container)

        Thread {
            try {
                if (photoUrl.startsWith("data:image")) {
                    val base64Data = photoUrl.substringAfter(",")
                    val decoded = Base64.decode(base64Data, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                    runOnUiThread { ivPhoto.setImageBitmap(bmp) }
                } else {
                    val finalUrl = if (photoUrl.startsWith("http://") || photoUrl.startsWith("https://")) {
                        photoUrl
                    } else {
                        ApiClient.BASE_URL.removeSuffix("/") + "/" + photoUrl.removePrefix("/")
                    }
                    val client = OkHttpClient()
                    val req = Request.Builder().url(finalUrl).build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val stream = resp.body()?.byteStream()
                        if (stream != null) {
                            val bmp = BitmapFactory.decodeStream(stream)
                            runOnUiThread { ivPhoto.setImageBitmap(bmp) }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@BkActivity, "Gagal memuat foto bukti: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()

        AlertDialog.Builder(this)
            .setTitle("📎 Surat Izin: $studentName")
            .setView(sv)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun showBkManualAttendanceDialog() {
        val sv = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 16)
        }

        val tvHeader = TextView(this).apply {
            text = "📱 Form Presensi Manual BK (Tanpa HP)"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        container.addView(tvHeader)

        val tvSub = TextView(this).apply {
            text = "Pencatatan darurat untuk siswa yang HP-nya tertinggal, baterai habis, atau rusak. Data otomatis terhubung ke rekap gerbang & orang tua."
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            setPadding(0, 0, 0, 14)
        }
        container.addView(tvSub)

        val etStudent = EditText(this).apply {
            hint = "Nama atau NISN Siswa"
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
        }
        container.addView(etStudent)

        val rgType = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }
        val rbIn = RadioButton(this).apply {
            text = "🚪 Masuk (GATE_IN)"
            isChecked = true
            setTextColor(Color.parseColor("#059669"))
        }
        val rbOut = RadioButton(this).apply {
            text = "🏃 Pulang (GATE_OUT)"
            setTextColor(Color.parseColor("#2563EB"))
        }
        rgType.addView(rbIn)
        rgType.addView(rbOut)
        container.addView(rgType)

        val tvStatusLabel = TextView(this).apply {
            text = "Status Kehadiran:"
            setTextColor(Color.parseColor("#334155"))
            textSize = 11.5f
            setPadding(0, 8, 0, 4)
        }
        container.addView(tvStatusLabel)

        val statusList = arrayOf("PRESENT (Hadir Tepat Waktu)", "LATE (Terlambat)", "SICK (Sakit)", "PERMISSION (Izin)")
        val spStatus = Spinner(this).apply {
            adapter = ArrayAdapter(this@BkActivity, android.R.layout.simple_spinner_dropdown_item, statusList)
        }
        container.addView(spStatus)

        val tvReasonLabel = TextView(this).apply {
            text = "Alasan Tanpa HP:"
            setTextColor(Color.parseColor("#334155"))
            textSize = 11.5f
            setPadding(0, 10, 0, 4)
        }
        container.addView(tvReasonLabel)

        val reasonOptions = arrayOf(
            "FORGOT (HP Tertinggal di Rumah)",
            "BATTERY_EMPTY (Baterai HP Habis)",
            "NO_PHONE (Tidak Membawa HP / Rusak)",
            "OTHER (Alasan Khusus Lainnya)"
        )
        val spReason = Spinner(this).apply {
            adapter = ArrayAdapter(this@BkActivity, android.R.layout.simple_spinner_dropdown_item, reasonOptions)
        }
        container.addView(spReason)

        val etNotes = EditText(this).apply {
            hint = "Catatan Tambahan Guru BK (Opsional)"
            setLines(2)
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            layoutParams = lp
        }
        container.addView(etNotes)

        sv.addView(container)

        AlertDialog.Builder(this)
            .setTitle("➕ Catat Presensi Siswa")
            .setView(sv)
            .setPositiveButton("Simpan Presensi") { _, _ ->
                val studentInput = etStudent.text.toString().trim()
                if (studentInput.isEmpty()) {
                    Toast.makeText(this, "Harap isi nama atau NISN siswa!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val type = if (rbIn.isChecked) "GATE_IN" else "GATE_OUT"
                val rawStatus = spStatus.selectedItem.toString()
                val status = rawStatus.substringBefore(" ")
                val rawReason = spReason.selectedItem.toString()
                val reasonCode = rawReason.substringBefore(" ")
                val note = etNotes.text.toString().trim()

                val body = mapOf(
                    "studentId" to studentInput,
                    "type" to type,
                    "status" to status,
                    "reasonWithoutPhone" to reasonCode,
                    "note" to note
                )

                Toast.makeText(this, "Menyimpan presensi manual BK...", Toast.LENGTH_SHORT).show()
                ApiClient.getClient(this).submitBkManualAttendance(body).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@BkActivity, "✅ Presensi manual BK berhasil dicatat!", Toast.LENGTH_LONG).show()
                            loadBkManualLogs()
                        } else {
                            Toast.makeText(this@BkActivity, "Gagal mencatat presensi", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@BkActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun loadBkManualLogs() {
        ApiClient.getClient(this).getBkManualTodayLog().enqueue(object : Callback<Map<String, Any>> {
            override fun onResponse(call: Call<Map<String, Any>>, response: Response<Map<String, Any>>) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val rawLogs = body["logs"] as? List<Map<String, Any>> ?: emptyList()
                    renderBkManualLogs(rawLogs)
                } else {
                    Toast.makeText(this@BkActivity, "Gagal memuat log presensi BK", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {
                Toast.makeText(this@BkActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun renderBkManualLogs(logs: List<Map<String, Any>>) {
        val container = binding.llBkManualLogsList
        container.removeAllViews()

        if (logs.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "Belum ada siswa yang dicatat presensi manual oleh Guru BK hari ini."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(10, 20, 10, 20)
            }
            container.addView(tvEmpty)
            return
        }

        logs.forEach { log ->
            val userMap = log["user"] as? Map<String, Any>
            val studentName = userMap?.get("name")?.toString() ?: "Siswa"
            val className = userMap?.get("className")?.toString() ?: "-"
            val nisn = userMap?.get("nisn")?.toString() ?: "-"
            val type = log["type"]?.toString() ?: "GATE_IN"
            val status = log["status"]?.toString() ?: "PRESENT"
            val reason = log["reasonWithoutPhone"]?.toString() ?: "-"
            val note = log["notes"]?.toString() ?: ""
            val scanTime = log["scanTime"]?.toString()?.takeLast(13)?.take(5) ?: "-"

            val card = CardView(this).apply {
                radius = 10f * resources.displayMetrics.density
                cardElevation = 1.5f * resources.displayMetrics.density
                setCardBackgroundColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 10
                }
                layoutParams = lp
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                text = "$studentName ($className)"
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }
            topRow.addView(tvName)

            val tvTime = TextView(this).apply {
                text = "🕒 $scanTime WIB"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
            }
            topRow.addView(tvTime)
            inner.addView(topRow)

            val badgesRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 0)
            }

            val typeBadge = TextView(this).apply {
                text = if (type == "GATE_IN") "🚪 MASUK" else "🏃 PULANG"
                setTextColor(if (type == "GATE_IN") Color.parseColor("#059669") else Color.parseColor("#2563EB"))
                setBackgroundColor(Color.parseColor("#F1F5F9"))
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding(10, 3, 10, 3)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 6
                }
                layoutParams = lp
            }
            badgesRow.addView(typeBadge)

            val reasonBadge = TextView(this).apply {
                text = "📱 $reason"
                setTextColor(Color.parseColor("#EA580C"))
                setBackgroundColor(Color.parseColor("#FFF7ED"))
                textSize = 10f
                setPadding(10, 3, 10, 3)
            }
            badgesRow.addView(reasonBadge)
            inner.addView(badgesRow)

            if (note.isNotEmpty()) {
                val tvNote = TextView(this).apply {
                    text = "Catatan: $note"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 11f
                    setPadding(0, 4, 0, 0)
                }
                inner.addView(tvNote)
            }

            card.addView(inner)
            container.addView(card)
        }
    }

    private fun loadBk360AttendanceRecap() {
        Toast.makeText(this, "Memuat rekap presensi 360°...", Toast.LENGTH_SHORT).show()
        ApiClient.getClient(this).getBkUnifiedAttendanceRecap(currentSelectedClass, null).enqueue(object : Callback<BkUnifiedRecapResponse> {
            override fun onResponse(call: Call<BkUnifiedRecapResponse>, response: Response<BkUnifiedRecapResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    renderBk360(data)
                } else {
                    Toast.makeText(this@BkActivity, "Gagal memuat rekap 360°", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<BkUnifiedRecapResponse>, t: Throwable) {
                Toast.makeText(this@BkActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun renderBk360(data: BkUnifiedRecapResponse) {
        val container = binding.llBk360Cards
        container.removeAllViews()

        val summary = data.summary
        val gate = summary?.gate
        val sholat = summary?.sholat
        val students = data.students ?: emptyList()

        val cardSummary = CardView(this).apply {
            radius = 12f * resources.displayMetrics.density
            cardElevation = 2f * resources.displayMetrics.density
            setCardBackgroundColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14
            }
            layoutParams = lp
        }

        val innerSummary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }

        val tvTitle = TextView(this).apply {
            text = "📊 STATISTIK HARI INI (${data.className ?: "Semua Kelas Ampuan"})"
            setTextColor(Color.parseColor("#7C3AED"))
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
        }
        innerSummary.addView(tvTitle)

        val btnDownloadBkPdf = Button(this).apply {
            text = "📄 Unduh / Cetak Laporan PDF Lengkap (A4 Portal)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7C3AED"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (42 * resources.displayMetrics.density).toInt()).apply {
                topMargin = 10
                bottomMargin = 8
            }
            layoutParams = lp
            setOnClickListener {
                com.school.smartcbt.utils.AttendancePdfHelper.showDownloadPdfDialog(
                    activity = this@BkActivity,
                    defaultClassName = currentSelectedClass,
                    title = "Laporan Presensi 360° Siswa BK"
                )
            }
        }
        innerSummary.addView(btnDownloadBkPdf)

        val statsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }

        val colGate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            }
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            setPadding(16, 12, 16, 12)
        }
        val tvGateH = TextView(this).apply {
            text = "🚪 Gerbang Masuk"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
        }
        val tvGateInfo = TextView(this).apply {
            text = "Hadir: ${gate?.hadir ?: 0}\nTelat: ${gate?.terlambat ?: 0}\nSakit/Izin: ${gate?.sakitIzin ?: 0}\nBelum: ${gate?.belumMasuk ?: 0}"
            setTextColor(Color.parseColor("#475569"))
            textSize = 11f
            setPadding(0, 4, 0, 0)
        }
        colGate.addView(tvGateH)
        colGate.addView(tvGateInfo)
        statsGrid.addView(colGate)

        val colSholat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 6
            }
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            setPadding(16, 12, 16, 12)
        }
        val tvSholatH = TextView(this).apply {
            text = "🕌 Sholat Dhuhur"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
        }
        val tvSholatInfo = TextView(this).apply {
            text = "Berjamaah: ${sholat?.berjamaah ?: 0}\nBerhalangan: ${sholat?.haid ?: 0}\nBelum: ${sholat?.belum ?: 0}"
            setTextColor(Color.parseColor("#475569"))
            textSize = 11f
            setPadding(0, 4, 0, 0)
        }
        colSholat.addView(tvSholatH)
        colSholat.addView(tvSholatInfo)
        statsGrid.addView(colSholat)

        innerSummary.addView(statsGrid)
        cardSummary.addView(innerSummary)
        container.addView(cardSummary)

        val tvListHeader = TextView(this).apply {
            text = "👥 Integrasi Presensi Per Siswa (${students.size} Siswa)"
            setTextColor(Color.parseColor("#334155"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 4, 0, 10)
        }
        container.addView(tvListHeader)

        if (students.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "Tidak ada data presensi siswa untuk ditampilkan."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(10, 10, 10, 10)
            }
            container.addView(tvEmpty)
            return
        }

        students.forEach { s ->
            val card = CardView(this).apply {
                radius = 10f * resources.displayMetrics.density
                cardElevation = 1.5f * resources.displayMetrics.density
                setCardBackgroundColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 8
                }
                layoutParams = lp
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val genderIcon = if (s.gender?.uppercase() == "P" || s.gender?.uppercase() == "FEMALE") "👧" else "👦"
            val tvName = TextView(this).apply {
                text = "$genderIcon ${s.name} (${s.className ?: "-"})"
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }
            topRow.addView(tvName)

            val tvNisn = TextView(this).apply {
                text = "NISN: ${s.nisn ?: "-"}"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10.5f
            }
            topRow.addView(tvNisn)
            inner.addView(topRow)

            val badgesRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
            }

            val gateText = when (s.gateStatus.uppercase()) {
                "HADIR" -> "🚪 Hadir"
                "TERLAMBAT" -> "🏃 Telat"
                "SAKIT" -> "🤒 Sakit"
                "IZIN" -> "✉️ Izin"
                else -> "🚪 Belum Masuk"
            }
            val gateColor = when (s.gateStatus.uppercase()) {
                "HADIR" -> "#059669"
                "TERLAMBAT" -> "#EA580C"
                "SAKIT", "IZIN" -> "#2563EB"
                else -> "#94A3B8"
            }
            val badgeGate = TextView(this).apply {
                text = gateText
                setTextColor(Color.parseColor(gateColor))
                setBackgroundColor(Color.parseColor("#F1F5F9"))
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding(10, 4, 10, 4)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 6
                }
                layoutParams = lp
            }
            badgesRow.addView(badgeGate)

            val badgeMapel = TextView(this).apply {
                text = "📖 ${s.mapelCount} Mapel"
                setTextColor(Color.parseColor("#0284C7"))
                setBackgroundColor(Color.parseColor("#F0F9FF"))
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding(10, 4, 10, 4)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 6
                }
                layoutParams = lp
            }
            badgesRow.addView(badgeMapel)

            val prayerText = when (s.prayerStatus.uppercase()) {
                "BERJAMAAH" -> "🕌 Sholat"
                "BERHALANGAN_HAID" -> "🌸 Berhalangan (Haid)"
                "ALPHA" -> "❌ Alpha"
                else -> "⏳ Belum Sholat"
            }
            val prayerColor = when (s.prayerStatus.uppercase()) {
                "BERJAMAAH" -> "#059669"
                "BERHALANGAN_HAID" -> "#EC4899"
                "ALPHA" -> "#DC2626"
                else -> "#94A3B8"
            }
            val badgePrayer = TextView(this).apply {
                text = prayerText
                setTextColor(Color.parseColor(prayerColor))
                setBackgroundColor(Color.parseColor("#F1F5F9"))
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding(10, 4, 10, 4)
            }
            badgesRow.addView(badgePrayer)

            inner.addView(badgesRow)
            card.addView(inner)
            container.addView(card)
        }
    }
}

