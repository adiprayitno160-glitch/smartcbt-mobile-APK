package com.school.smartcbt.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.school.smartcbt.data.model.AnnouncementItemDto
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.databinding.ActivityAnnouncementBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class AnnouncementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnnouncementBinding
    private var allAnnouncements: List<AnnouncementItemDto> = emptyList()
    private var currentFilter = "ALL"
    private var currentSearchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnnouncementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackAnnouncement.setOnClickListener { finish() }
        binding.btnRefreshAnnouncement.setOnClickListener { loadAnnouncements() }

        setupCategoryFilters()
        setupSearch()
        loadAnnouncements()
    }

    private fun setupCategoryFilters() {
        val chips = listOf(
            Triple(binding.chipCatAll, "ALL", "Semua"),
            Triple(binding.chipCatImportant, "PENTING", "📌 PENTING"),
            Triple(binding.chipCatExam, "UJIAN", "📝 UJIAN"),
            Triple(binding.chipCatAcademic, "AKADEMIK", "🎓 AKADEMIK"),
            Triple(binding.chipCatGeneral, "UMUM", "📢 UMUM")
        )

        chips.forEach { (chip, category, _) ->
            chip.setOnClickListener {
                currentFilter = category
                chips.forEach { (c, cat, _) ->
                    if (cat == currentFilter) {
                        c.setBackgroundColor(Color.parseColor("#1E3A8A"))
                        c.setTextColor(Color.WHITE)
                    } else {
                        c.setBackgroundColor(Color.parseColor("#F1F5F9"))
                        c.setTextColor(Color.parseColor("#475569"))
                    }
                }
                applyFilterAndRender()
            }
        }
    }

    private fun setupSearch() {
        binding.etSearchAnnouncement.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applyFilterAndRender()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadAnnouncements() {
        Toast.makeText(this, "Memuat pengumuman sekolah...", Toast.LENGTH_SHORT).show()

        ApiClient.getClient(this).getAnnouncements().enqueue(object : Callback<List<AnnouncementItemDto>> {
            override fun onResponse(call: Call<List<AnnouncementItemDto>>, response: Response<List<AnnouncementItemDto>>) {
                if (response.isSuccessful && response.body() != null) {
                    allAnnouncements = response.body()!!
                    binding.tvAnnouncementSubtitle.text = "${allAnnouncements.size} Pengumuman Terbit"
                    applyFilterAndRender()
                } else {
                    Toast.makeText(this@AnnouncementActivity, "Gagal mengambil pengumuman", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<List<AnnouncementItemDto>>, t: Throwable) {
                Toast.makeText(this@AnnouncementActivity, "Koneksi gagal: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun applyFilterAndRender() {
        var filtered = allAnnouncements

        if (currentFilter != "ALL") {
            filtered = filtered.filter {
                (it.category ?: "UMUM").uppercase().contains(currentFilter) ||
                (it.title).uppercase().contains(currentFilter)
            }
        }

        if (currentSearchQuery.isNotEmpty()) {
            val q = currentSearchQuery.lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(q) ||
                it.content.lowercase().contains(q) ||
                (it.author ?: "").lowercase().contains(q)
            }
        }

        renderAnnouncements(filtered)
    }

    private fun renderAnnouncements(list: List<AnnouncementItemDto>) {
        binding.containerAnnouncements.removeAllViews()

        if (list.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "Tidak ada pengumuman yang sesuai dengan filter atau kata kunci."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 48, 0, 48)
            }
            binding.containerAnnouncements.addView(tvEmpty)
            return
        }

        for (item in list) {
            val card = CardView(this).apply {
                radius = 16f
                cardElevation = 2f
                setCardBackgroundColor(Color.WHITE)
                useCompatPadding = true
                isClickable = true
                isFocusable = true
                setOnClickListener { showFullAnnouncementDialog(item) }
            }

            val cardInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
            }

            // Top Header: Tag & Date
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val catText = item.category ?: "UMUM"
            val tvTag = TextView(this).apply {
                text = if (item.isPinned == true) "📌 $catText • DISEMATKAN" else catText
                setTextColor(Color.WHITE)
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(8, 4, 8, 4)
                setBackgroundColor(
                    if (item.isPinned == true) Color.parseColor("#B45309")
                    else if (catText.contains("PENTING")) Color.parseColor("#DC2626")
                    else Color.parseColor("#1E3A8A")
                )
            }

            val dateStr = if (item.createdAt.length >= 10) item.createdAt.substring(0, 10) else item.createdAt
            val tvDate = TextView(this).apply {
                text = dateStr
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10f
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            headerRow.addView(tvTag)
            headerRow.addView(tvDate)

            // Title
            val tvTitle = TextView(this).apply {
                text = item.title
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 10, 0, 4)
            }

            // Snippet
            val snippet = if (item.content.length > 120) item.content.substring(0, 120) + "..." else item.content
            val tvSnippet = TextView(this).apply {
                text = snippet
                setTextColor(Color.parseColor("#475569"))
                textSize = 12f
                setLineSpacing(4f, 1f)
            }

            // Author & Letter No
            val metaLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6, 0, 0)
            }

            if (!item.letterNo.isNullOrEmpty()) {
                val tvLetterNo = TextView(this).apply {
                    text = "No. Surat: ${item.letterNo}"
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                metaLayout.addView(tvLetterNo)
            }

            if (!item.author.isNullOrEmpty()) {
                val tvAuthor = TextView(this).apply {
                    text = "Oleh: ${item.author}"
                    setTextColor(Color.parseColor("#2563EB"))
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                metaLayout.addView(tvAuthor)
            }

            cardInner.addView(headerRow)
            cardInner.addView(tvTitle)
            cardInner.addView(tvSnippet)
            cardInner.addView(metaLayout)

            // Lampiran Berkas PDF
            if (!item.fileUrl.isNullOrEmpty()) {
                val tvPdf = TextView(this).apply {
                    text = "📄 Buka Dokumen PDF Resmi (${item.fileSize ?: "PDF"}) ↗"
                    setTextColor(Color.parseColor("#DC2626"))
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(16, 12, 16, 12)
                    setBackgroundColor(Color.parseColor("#FEF2F2"))
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 12
                    }
                    layoutParams = params
                    setOnClickListener {
                        openPdfFile(item.fileUrl)
                    }
                }
                cardInner.addView(tvPdf)
            }

            card.addView(cardInner)
            binding.containerAnnouncements.addView(card)
        }
    }

    private fun showFullAnnouncementDialog(item: AnnouncementItemDto) {
        val dateStr = if (item.createdAt.length >= 10) item.createdAt.substring(0, 10) else item.createdAt
        val sb = StringBuilder()
        sb.append("📅 Tanggal Terbit: $dateStr\n")
        if (!item.letterNo.isNullOrEmpty()) {
            sb.append("📜 No. Surat: ${item.letterNo}\n")
        }
        if (!item.author.isNullOrEmpty()) {
            sb.append("✍️ Rilis: ${item.author}\n")
        }
        sb.append("🏷️ Kategori / Sasaran: ${item.category ?: "UMUM"}\n\n")
        sb.append(item.content)

        val builder = AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage(sb.toString())
            .setPositiveButton("Tutup", null)

        if (!item.fileUrl.isNullOrEmpty()) {
            builder.setNeutralButton("📄 Buka File PDF") { _, _ ->
                openPdfFile(item.fileUrl)
            }
        }
        builder.show()
    }

    private fun openPdfFile(rawUrl: String) {
        try {
            val fullUrl = if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl 
                          else ApiClient.BASE_URL.trimEnd('/') + (if (rawUrl.startsWith("/")) "" else "/") + rawUrl
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fullUrl))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat membuka dokumen PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
