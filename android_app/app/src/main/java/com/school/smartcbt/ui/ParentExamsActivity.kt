package com.school.smartcbt.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.school.smartcbt.data.model.ActiveExamsResponse
import com.school.smartcbt.data.model.ExamItemDto
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.databinding.ActivityParentExamsBinding
import com.school.smartcbt.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ParentExamsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentExamsBinding
    private lateinit var sessionManager: SessionManager

    companion object {
        const val EXTRA_STUDENT_ID = "extra_student_id"
        const val EXTRA_STUDENT_NAME = "extra_student_name"
        const val EXTRA_CLASS_NAME = "extra_class_name"
    }

    private var targetStudentId: String? = null
    private var childName: String = "Ananda"
    private var className: String = "VII-A"
    private var isCompletedTabActive: Boolean = false
    private var allExams: List<ExamItemDto> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentExamsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        targetStudentId = intent.getStringExtra(EXTRA_STUDENT_ID)
        childName = intent.getStringExtra(EXTRA_STUDENT_NAME)?.ifEmpty { null } ?: sessionManager.getName().ifEmpty { "Ananda" }
        className = intent.getStringExtra(EXTRA_CLASS_NAME)?.ifEmpty { null } ?: sessionManager.getClassName().ifEmpty { "VII-A" }

        binding.tvParentExamsSubtitle.text = "Siswa: $childName • Kelas $className"

        binding.btnBackParentExams.setOnClickListener { finish() }
        binding.btnRefreshParentExams.setOnClickListener {
            Toast.makeText(this, "Menyegarkan jadwal ujian...", Toast.LENGTH_SHORT).show()
            loadExams()
        }

        setupTabs()
        loadExams()
    }

    private fun setupTabs() {
        binding.btnTabUpcomingExams.setOnClickListener {
            if (isCompletedTabActive) {
                isCompletedTabActive = false
                updateTabStyles()
                renderExams()
            }
        }
        binding.btnTabCompletedExams.setOnClickListener {
            if (!isCompletedTabActive) {
                isCompletedTabActive = true
                updateTabStyles()
                renderExams()
            }
        }
    }

    private fun updateTabStyles() {
        if (!isCompletedTabActive) {
            binding.btnTabUpcomingExams.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0A2E5C"))
            binding.btnTabUpcomingExams.setTextColor(Color.WHITE)
            binding.btnTabCompletedExams.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F1F5F9"))
            binding.btnTabCompletedExams.setTextColor(Color.parseColor("#64748B"))
        } else {
            binding.btnTabCompletedExams.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0A2E5C"))
            binding.btnTabCompletedExams.setTextColor(Color.WHITE)
            binding.btnTabUpcomingExams.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F1F5F9"))
            binding.btnTabUpcomingExams.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun loadExams() {
        ApiClient.getClient(this).getStudentCbtExams().enqueue(object : Callback<ActiveExamsResponse> {
            override fun onResponse(call: Call<ActiveExamsResponse>, response: Response<ActiveExamsResponse>) {
                val exams = response.body()?.exams ?: emptyList()
                allExams = exams
                renderExams()
            }

            override fun onFailure(call: Call<ActiveExamsResponse>, t: Throwable) {
                renderEmptyState("Koneksi ke server CBT terputus (${t.message}).")
            }
        })
    }

    private fun isCompleted(exam: ExamItemDto): Boolean {
        val s = exam.status.uppercase()
        return s == "FINISHED" || s == "COMPLETED" || s == "SUBMITTED" || s == "DONE"
    }

    private fun renderExams() {
        binding.containerParentExams.removeAllViews()
        val filtered = if (isCompletedTabActive) {
            allExams.filter { isCompleted(it) }
        } else {
            allExams.filter { !isCompleted(it) }
        }

        if (filtered.isEmpty()) {
            val emptyMsg = if (isCompletedTabActive) {
                "Belum ada riwayat ujian yang telah diselesaikan Ananda $childName."
            } else {
                "Saat ini belum ada jadwal ulangan harian atau asesmen CBT aktif untuk kelas $className."
            }
            renderEmptyState(emptyMsg)
            return
        }

        val density = resources.displayMetrics.density

        filtered.forEach { exam ->
            val card = CardView(this).apply {
                radius = 16 * density
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

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (16 * density).toInt()
                setPadding(p, p, p, p)
            }

            // Row 1: Session & Status Badge
            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvSession = TextView(this).apply {
                val sName = exam.sessionName ?: "Sesi Reguler"
                text = "🏛️ $sName"
                setTextColor(Color.parseColor("#475569"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowTop.addView(tvSession)

            val tvBadge = TextView(this).apply {
                if (isCompletedTabActive) {
                    text = "✅ Selesai Dikerjakan"
                    setTextColor(Color.parseColor("#166534"))
                    setBackgroundColor(Color.parseColor("#DCFCE7"))
                } else {
                    text = "⏳ Sesi Aktif"
                    setTextColor(Color.parseColor("#1E40AF"))
                    setBackgroundColor(Color.parseColor("#DBEAFE"))
                }
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                val hP = (8 * density).toInt()
                val vP = (3 * density).toInt()
                setPadding(hP, vP, hP, vP)
            }
            rowTop.addView(tvBadge)
            inner.addView(rowTop)

            // Subject & Title
            val tvSubject = TextView(this).apply {
                text = exam.subject
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            inner.addView(tvSubject)

            val tvTitle = TextView(this).apply {
                text = exam.title
                setTextColor(Color.parseColor("#64748B"))
                textSize = 12f
                setPadding(0, (2 * density).toInt(), 0, (8 * density).toInt())
            }
            inner.addView(tvTitle)

            // Detail Time Banner
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor(if (isCompletedTabActive) "#F0FDF4" else "#EFF6FF"))
                val pB = (10 * density).toInt()
                setPadding(pB, pB, pB, pB)
            }

            val tvTime = TextView(this).apply {
                val start = exam.startTimeStr ?: "07:30"
                val end = exam.endTimeStr ?: "12:00"
                text = "⏰ $start - $end WIB"
                setTextColor(Color.parseColor(if (isCompletedTabActive) "#15803D" else "#1D4ED8"))
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            banner.addView(tvTime)

            val tvDuration = TextView(this).apply {
                val qCount = exam.questionsCount.takeIf { it > 0 } ?: 25
                text = "⏱️ ${exam.durationMinutes} Menit • $qCount Soal"
                setTextColor(Color.parseColor(if (isCompletedTabActive) "#166534" else "#1E40AF"))
                textSize = 11f
            }
            banner.addView(tvDuration)
            inner.addView(banner)

            card.addView(inner)
            binding.containerParentExams.addView(card)
        }
    }

    private fun renderEmptyState(msg: String) {
        val density = resources.displayMetrics.density
        val tvEmpty = TextView(this).apply {
            text = msg
            setTextColor(Color.parseColor("#64748B"))
            textSize = 13f
            gravity = Gravity.CENTER
            val pad = (40 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        binding.containerParentExams.addView(tvEmpty)
    }
}
