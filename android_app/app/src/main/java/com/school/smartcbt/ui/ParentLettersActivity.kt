package com.school.smartcbt.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.school.smartcbt.data.model.BasicResponse
import com.school.smartcbt.data.model.OfficialLetterDto
import com.school.smartcbt.data.model.ParentLettersResponse
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.databinding.ActivityParentLettersBinding
import com.school.smartcbt.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ParentLettersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentLettersBinding
    private lateinit var sessionManager: SessionManager

    companion object {
        const val EXTRA_STUDENT_ID = "extra_student_id"
        const val EXTRA_STUDENT_NAME = "extra_student_name"
        const val EXTRA_CLASS_NAME = "extra_class_name"
    }

    private var targetStudentId: String? = null
    private var childName: String = "Ananda"
    private var className: String = "-"

    private var allLetters: List<OfficialLetterDto> = emptyList()
    private var currentFilter: String = "ALL" // "ALL", "PENDING", "CONFIRMED"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentLettersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        targetStudentId = intent.getStringExtra(EXTRA_STUDENT_ID)
        childName = intent.getStringExtra(EXTRA_STUDENT_NAME)?.ifEmpty { null } ?: sessionManager.getName().ifEmpty { "Ananda" }
        className = intent.getStringExtra(EXTRA_CLASS_NAME)?.ifEmpty { null } ?: sessionManager.getClassName().ifEmpty { "VII-A" }

        binding.tvLettersSubtitle.text = "Wali Murid: $childName • Kelas $className"

        binding.btnBackLetters.setOnClickListener { finish() }
        binding.btnRefreshLetters.setOnClickListener {
            Toast.makeText(this, "Menyegarkan surat edaran...", Toast.LENGTH_SHORT).show()
            loadLetters()
        }

        setupTabs()
        loadLetters()
    }

    private fun setupTabs() {
        binding.tabAll.setOnClickListener {
            currentFilter = "ALL"
            updateTabStyles()
            renderLetters()
        }
        binding.tabPending.setOnClickListener {
            currentFilter = "PENDING"
            updateTabStyles()
            renderLetters()
        }
        binding.tabConfirmed.setOnClickListener {
            currentFilter = "CONFIRMED"
            updateTabStyles()
            renderLetters()
        }
    }

    private fun updateTabStyles() {
        val activeBg = Color.parseColor("#1E293B")
        val activeText = Color.WHITE
        val inactiveBg = Color.parseColor("#F1F5F9")
        val inactiveText = Color.parseColor("#475569")

        binding.tabAll.setBackgroundColor(if (currentFilter == "ALL") activeBg else inactiveBg)
        binding.tabAll.setTextColor(if (currentFilter == "ALL") activeText else inactiveText)

        binding.tabPending.setBackgroundColor(if (currentFilter == "PENDING") activeBg else inactiveBg)
        binding.tabPending.setTextColor(if (currentFilter == "PENDING") activeText else inactiveText)

        binding.tabConfirmed.setBackgroundColor(if (currentFilter == "CONFIRMED") activeBg else inactiveBg)
        binding.tabConfirmed.setTextColor(if (currentFilter == "CONFIRMED") activeText else inactiveText)
    }

    private fun loadLetters() {
        ApiClient.getClient(this).getParentOfficialLetters(targetStudentId).enqueue(object : Callback<ParentLettersResponse> {
            override fun onResponse(call: Call<ParentLettersResponse>, response: Response<ParentLettersResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    allLetters = response.body()!!.letters ?: emptyList()
                    renderLetters()
                } else {
                    binding.tvEmptyLetters.visibility = View.VISIBLE
                    binding.tvEmptyLetters.text = "Gagal memuat arsip surat resmi sekolah."
                }
            }

            override fun onFailure(call: Call<ParentLettersResponse>, t: Throwable) {
                binding.tvEmptyLetters.visibility = View.VISIBLE
                binding.tvEmptyLetters.text = "Koneksi terputus: "
            }
        })
    }

    private fun renderLetters() {
        binding.containerLetters.removeAllViews()

        val filtered = when (currentFilter) {
            "PENDING" -> allLetters.filter { it.isConfirmed != true }
            "CONFIRMED" -> allLetters.filter { it.isConfirmed == true }
            else -> allLetters
        }

        if (filtered.isEmpty()) {
            val emptyMsg = when (currentFilter) {
                "PENDING" -> "🎉 Hebat! Tidak ada surat resmi yang membutuhkan konfirmasi tertunda."
                "CONFIRMED" -> "Belum ada surat yang Anda setujui."
                else -> "Belum ada surat edaran resmi yang diterbitkan untuk saat ini."
            }
            val tvEmpty = TextView(this).apply {
                text = emptyMsg
                setTextColor(Color.parseColor("#64748B"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(40, 60, 40, 60)
            }
            binding.containerLetters.addView(tvEmpty)
            return
        }

        val density = resources.displayMetrics.density

        filtered.forEach { letter ->
            val card = CardView(this).apply {
                radius = 16 * density
                cardElevation = 2.5f * density
                useCompatPadding = true
                setCardBackgroundColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (12 * density).toInt()
                }
                layoutParams = lp
            }

            val cardInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (16 * density).toInt()
                setPadding(p, p, p, p)
            }

            // Top Row: Number & Status Badge
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvNo = TextView(this).apply {
                text = "📌 "
                textSize = 11f
                setTextColor(Color.parseColor("#0284C7"))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topRow.addView(tvNo)

            val isConfirmed = letter.isConfirmed == true
            val statusBadge = TextView(this).apply {
                text = if (isConfirmed) "✅ TELAH DISETUJUI" else "⚠️ MENUNGGU KONFIRMASI"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isConfirmed) Color.parseColor("#065F46") else Color.parseColor("#B45309"))
                setBackgroundColor(if (isConfirmed) Color.parseColor("#D1FAE5") else Color.parseColor("#FEF3C7"))
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }
            topRow.addView(statusBadge)
            cardInner.addView(topRow)

            // Letter Title
            val tvTitle = TextView(this).apply {
                text = letter.title
                textSize = 15f
                setTextColor(Color.parseColor("#0F172A"))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
            }
            cardInner.addView(tvTitle)

            // Target & Date
            val targetLabel = when (letter.targetType) {
                "ALL" -> "👥 Ditujukan ke Seluruh Wali Murid"
                "GRADE" -> "🎓 Ditujukan ke Tingkat ${letter.targetValue ?: "Semua"}"
                "CLASS" -> "🏫 Ditujukan ke Kelas ${letter.targetValue ?: "Semua"}"
                else -> "👤 Pemberitahuan Personal"
            }
            val tvTarget = TextView(this).apply {
                val sizeStr = letter.fileSize ?: "PDF"
                text = "$targetLabel • Berkas ($sizeStr)"
                textSize = 11f
                setTextColor(Color.parseColor("#64748B"))
            }
            cardInner.addView(tvTarget)

            // Summary Content
            val tvContent = TextView(this).apply {
                text = letter.content
                textSize = 12f
                setTextColor(Color.parseColor("#334155"))
                setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
            }
            cardInner.addView(tvContent)

            // Action Buttons Row
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            }

            // Button 1: View PDF
            val btnViewPdf = Button(this).apply {
                text = "👁️ Buka / Baca PDF Resmi"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#0284C7"))
                setTextColor(Color.WHITE)
                val lpBtn = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f).apply {
                    marginEnd = if (!isConfirmed) (8 * density).toInt() else 0
                }
                layoutParams = lpBtn
                setOnClickListener {
                    openOfficialPdf(letter)
                }
            }
            btnRow.addView(btnViewPdf)

            // Button 2: Confirm (if not yet confirmed)
            if (!isConfirmed) {
                val btnConfirm = Button(this).apply {
                    text = "✍️ Setujui & Konfirmasi"
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundColor(Color.parseColor("#059669"))
                    setTextColor(Color.WHITE)
                    val lpBtn = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f)
                    layoutParams = lpBtn
                    setOnClickListener {
                        confirmLetter(letter)
                    }
                }
                btnRow.addView(btnConfirm)
            }

            cardInner.addView(btnRow)
            card.addView(cardInner)
            binding.containerLetters.addView(card)
        }
    }

    private fun openOfficialPdf(letter: OfficialLetterDto) {
        val intent = Intent(this, PdfViewerActivity::class.java).apply {
            putExtra(PdfViewerActivity.EXTRA_TITLE, letter.title)
            putExtra(PdfViewerActivity.EXTRA_DOC_TYPE, "SURAT_RESMI")
            if (!letter.fileUrl.isNullOrEmpty()) {
                putExtra(PdfViewerActivity.EXTRA_URL, letter.fileUrl)
            }
        }
        startActivity(intent)
    }

    private fun confirmLetter(letter: OfficialLetterDto) {
        val letterTitle = letter.title.ifEmpty { "Surat Resmi Sekolah" }
        val letterNo = letter.letterNo.ifEmpty { "-" }
        AlertDialog.Builder(this)
            .setTitle("✍️ Konfirmasi Persetujuan Surat")
            .setMessage("Apakah Anda selaku orang tua/wali dari $childName menyetujui isi dari surat resmi:\n\n\"$letterTitle\" ($letterNo)?")
            .setPositiveButton("Ya, Saya Setuju") { _, _ ->
                val studentId = targetStudentId ?: sessionManager.getUserId()
                val payload = mapOf(
                    "studentId" to studentId,
                    "notes" to "Telah dibaca dan disetujui wali murid dari $childName melalui aplikasi Android"
                )

                Toast.makeText(this, "Mengirim konfirmasi persetujuan...", Toast.LENGTH_SHORT).show()

                ApiClient.getClient(this).confirmOfficialLetter(letter.id, payload).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (response.isSuccessful) {
                            Toast.makeText(this@ParentLettersActivity, "✅ Berhasil menyetujui surat edaran resmi!", Toast.LENGTH_LONG).show()
                            loadLetters()
                        } else {
                            val err = response.errorBody()?.string() ?: response.message()
                            Toast.makeText(this@ParentLettersActivity, "Gagal mengonfirmasi: $err", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Toast.makeText(this@ParentLettersActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}