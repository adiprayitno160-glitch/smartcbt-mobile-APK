package com.school.smartcbt.ui

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.school.smartcbt.R
import com.school.smartcbt.data.model.*
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.databinding.ActivityEvotingBinding
import com.school.smartcbt.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Persistent in-memory cache to guarantee photos never flicker, reload, or disappear
object CandidateImageMemoryCache {
    val cache = ConcurrentHashMap<String, Bitmap>()
}

class EvotingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEvotingBinding
    private lateinit var sessionManager: SessionManager

    private val imageExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var candidateList: List<CandidateDto> = emptyList()
    private var selectedCandidate: CandidateDto? = null
    private var isSubmittingVote: Boolean = false
    private var hasAlreadyVoted: Boolean = false

    // Multi-color palette for candidates (Neat, vibrant gradients & accents)
    private val candidateThemes = listOf(
        Pair("#4F46E5", "#EEF2FF"), // Indigo
        Pair("#059669", "#ECFDF5"), // Emerald
        Pair("#D97706", "#FFFBEB"), // Amber
        Pair("#E11D48", "#FFF1F2"), // Rose
        Pair("#7C3AED", "#F5F3FF"), // Purple
        Pair("#0284C7", "#F0F9FF")  // Sky Blue
    )

    private val cardViewsMap = mutableMapOf<String, CardView>()
    private val btnCoblosMap = mutableMapOf<String, Button>()
    private val badgeSelectedMap = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEvotingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        setupListeners()
        loadVoteStatusAndCandidates()
        checkClassOfficerFeature()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSubmitVote.setOnClickListener {
            val candidate = selectedCandidate
            if (candidate == null) {
                Toast.makeText(this, "Silakan pilih salah satu pasangan calon terlebih dahulu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showDoubleConfirmationDialog(candidate)
        }
    }

    /**
     * 1. Cek Status Hak Suara Siswa & Status Modul e-Voting
     */
    private fun loadVoteStatusAndCandidates() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.tvLoadingMessage.text = "Memeriksa status hak suara..."

        val api = ApiClient.getClient(this)

        api.getStudentVoteStatus().enqueue(object : Callback<VoteStatusResponse> {
            override fun onResponse(call: Call<VoteStatusResponse>, response: Response<VoteStatusResponse>) {
                val voteData = response.body()?.data
                hasAlreadyVoted = voteData?.hasVoted == true

                if (hasAlreadyVoted) {
                    binding.tvVotingStatusBadge.text = "● SUDAH MEMILIH"
                    binding.tvVotingStatusBadge.setBackgroundColor(Color.parseColor("#15803D"))
                    binding.cardAlreadyVotedState.visibility = View.VISIBLE
                    binding.layoutSubmitBottomBar.visibility = View.GONE

                    val rawVotedAt = voteData?.votedAt
                    val votedTime = if (!rawVotedAt.isNullOrEmpty()) {
                        try {
                            rawVotedAt.replace("T", " ").substring(0, 19) + " WIB"
                        } catch (e: Exception) {
                            rawVotedAt
                        }
                    } else {
                        "Tercatat di Server SIAKAD"
                    }
                    binding.tvAlreadyVotedTime.text = "Hak suara telah digunakan pada: $votedTime"

                    val savedReceipt = sessionManager.getEvotingReceipt().ifEmpty { "EVT-TERCATAT-SAH" }
                    binding.tvAlreadyVotedReceipt.text = savedReceipt
                }

                loadCandidates()
            }

            override fun onFailure(call: Call<VoteStatusResponse>, t: Throwable) {
                loadCandidates()
            }
        })
    }

    /**
     * 2. Mengambil Data Kandidat Paslon secara Dinamis
     */
    private fun loadCandidates() {
        binding.tvLoadingMessage.text = "Memuat daftar pasangan calon..."
        val api = ApiClient.getClient(this)

        api.getEvotingCandidates().enqueue(object : Callback<CandidateListResponse> {
            override fun onResponse(call: Call<CandidateListResponse>, response: Response<CandidateListResponse>) {
                binding.layoutLoading.visibility = View.GONE

                if (response.isSuccessful && response.body()?.success == true) {
                    val list = response.body()?.data ?: emptyList()
                    candidateList = list
                    renderCandidates(list)
                } else if (response.code() == 403) {
                    showErrorDialog(
                        "Sesi e-Voting Ditutup",
                        "Modul e-Voting sedang dinonaktifkan oleh administrator SIAKAD atau di luar jadwal pemungutan suara."
                    )
                } else {
                    Toast.makeText(this@EvotingActivity, "Gagal memuat kandidat: ${response.message()}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<CandidateListResponse>, t: Throwable) {
                binding.layoutLoading.visibility = View.GONE
                Toast.makeText(this@EvotingActivity, "Koneksi ke server bermasalah: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /**
     * 3. Render Kartu Kandidat Lengkap, Rapi & Ringkas (Compact & Dynamic)
     */
    private fun renderCandidates(list: List<CandidateDto>) {
        val container = binding.layoutCandidatesContainer
        container.removeAllViews()
        cardViewsMap.clear()
        btnCoblosMap.clear()
        badgeSelectedMap.clear()

        if (list.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Belum ada pasangan calon yang terdaftar."
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 40, 0, 40)
            }
            container.addView(emptyTv)
            return
        }

        list.forEachIndexed { index, candidate ->
            val theme = candidateThemes[index % candidateThemes.size]
            val cardView = createCompactCandidateCard(candidate, theme, index)
            container.addView(cardView)
        }
    }

    /**
     * Desain Kartu Paslon Kompak, Elegan & Multi-Color
     * Foto ukuran proporsional (76dp) + Fallback Avatar Placeholder
     */
    private fun createCompactCandidateCard(
        candidate: CandidateDto,
        theme: Pair<String, String>,
        index: Int
    ): CardView {
        val isSelected = selectedCandidate?.id == candidate.id

        val card = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 20)
            }
            radius = 28f
            cardElevation = if (isSelected) 8f else 2f
            setCardBackgroundColor(Color.WHITE)
            tag = candidate.id
        }
        cardViewsMap[candidate.id] = card

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }

        // --- ROW 1: Header (Nomor Urut, Nama Paslon & Foto Kompak) ---
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Frame Foto Paslon (76dp x 76dp)
        val photoCard = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(160, 160)
            radius = 22f
            cardElevation = 2f
            setCardBackgroundColor(Color.parseColor("#F1F5F9"))
        }

        val ivPhoto = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Default placeholder: Person avatar silhouette, NOT school logo!
            setImageResource(R.drawable.ic_avatar_placeholder)
        }
        photoCard.addView(ivPhoto)

        // Load foto ke ImageView secara asinkron
        loadCandidatePhoto(candidate.photoUrl, ivPhoto)

        // Info Kolom (Badge No Urut + Nama Ketua & Wakil)
        val infoCol = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 20
            }
            orientation = LinearLayout.VERTICAL
        }

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvNoBadge = TextView(this).apply {
            text = "PASLON 0${candidate.candidateNumber}"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(theme.first))
            setPadding(16, 6, 16, 6)
        }

        val tvSelectedBadge = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 12
            }
            text = "✓ TERPILIH"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#15803D"))
            setBackgroundColor(Color.parseColor("#DCFCE7"))
            setPadding(14, 6, 14, 6)
            visibility = if (isSelected) View.VISIBLE else View.GONE
        }
        badgeSelectedMap[candidate.id] = tvSelectedBadge

        badgeRow.addView(tvNoBadge)
        badgeRow.addView(tvSelectedBadge)

        val tvChair = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            text = candidate.chairmanName
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val tvVice = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 2 }
            text = "Wakil: ${candidate.viceChairmanName}"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        infoCol.addView(badgeRow)
        infoCol.addView(tvChair)
        infoCol.addView(tvVice)

        topRow.addView(photoCard)
        topRow.addView(infoCol)
        mainLayout.addView(topRow)

        // --- ROW 2: Ringkasan Visi & Tombol Modal Visi-Misi ---
        val visionTeaserBox = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(theme.second))
            setPadding(16, 10, 16, 10)
        }

        val tvVisionSnippet = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "\"${candidate.vision}\""
            textSize = 10.5f
            setTextColor(Color.parseColor("#334155"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val btnViewVisionMission = TextView(this).apply {
            text = "Detail Visi Misi ›"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(theme.first))
            setPadding(12, 4, 4, 4)
            setOnClickListener {
                showVisionMissionBottomSheet(candidate, theme)
            }
        }

        visionTeaserBox.addView(tvVisionSnippet)
        visionTeaserBox.addView(btnViewVisionMission)
        mainLayout.addView(visionTeaserBox)

        // --- ROW 3: Tombol Aksi Coblos ---
        if (!hasAlreadyVoted) {
            val btnCoblos = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    96
                ).apply {
                    topMargin = 14
                }
                text = if (isSelected) "✓ SUDAH ANDA PILIH" else "PILIH PASLON NO. ${candidate.candidateNumber}"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(if (isSelected) "#10B981" else theme.first))
                setOnClickListener { selectCandidate(candidate) }
            }
            btnCoblosMap[candidate.id] = btnCoblos
            mainLayout.addView(btnCoblos)

            // Klik kartu atau foto juga memilih kandidat
            card.setOnClickListener { selectCandidate(candidate) }
            photoCard.setOnClickListener { selectCandidate(candidate) }
        }

        card.addView(mainLayout)
        return card
    }

    /**
     * Memilih Paslon secara dinamis tanpa reload/destroy view (Anti-flicker & Anti-disappear)
     */
    private fun selectCandidate(candidate: CandidateDto) {
        if (hasAlreadyVoted) return

        selectedCandidate = candidate

        // Update style visual semua card
        candidateList.forEachIndexed { index, c ->
            val isTarget = c.id == candidate.id
            val theme = candidateThemes[index % candidateThemes.size]

            val card = cardViewsMap[c.id]
            card?.cardElevation = if (isTarget) 10f else 2f

            val btn = btnCoblosMap[c.id]
            btn?.text = if (isTarget) "✓ SUDAH ANDA PILIH" else "PILIH PASLON NO. ${c.candidateNumber}"
            btn?.setBackgroundColor(Color.parseColor(if (isTarget) "#10B981" else theme.first))

            val badge = badgeSelectedMap[c.id]
            badge?.visibility = if (isTarget) View.VISIBLE else View.GONE
        }

        // Tampilkan Bottom Action Bar
        binding.layoutSubmitBottomBar.visibility = View.VISIBLE
        binding.tvSelectedCandidateSummary.text = "Pilihan: Paslon 0${candidate.candidateNumber} (${candidate.chairmanName} & ${candidate.viceChairmanName})"
        binding.btnSubmitVote.text = "KIRIM SUARA SAH PASLON 0${candidate.candidateNumber}"
    }

    /**
     * Load Foto Kandidat secara Asinkron dengan Persistent Memory Cache
     * Menggunakan buffering byte array lengkap agar gambar tidak corrupt atau hilang
     */
    private fun loadCandidatePhoto(photoUrl: String?, imageView: ImageView) {
        if (photoUrl.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_avatar_placeholder)
            return
        }

        val fullUrl = if (photoUrl.startsWith("http")) photoUrl else ApiClient.getBaseServerUrl(this) + photoUrl
        imageView.tag = fullUrl

        val cached = CandidateImageMemoryCache.cache[fullUrl]
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        // Tampilkan placeholder person avatar selama memuat
        imageView.setImageResource(R.drawable.ic_avatar_placeholder)

        imageExecutor.execute {
            try {
                val url = URL(fullUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    doInput = true
                }
                conn.connect()
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            CandidateImageMemoryCache.cache[fullUrl] = bitmap
                            mainHandler.post {
                                if (imageView.tag == fullUrl) {
                                    imageView.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Biarkan tetap menggunakan avatar placeholder
            }
        }
    }

    /**
     * BottomSheet Dialog Visi & Misi Terbuka
     */
    private fun showVisionMissionBottomSheet(candidate: CandidateDto, theme: Pair<String, String>) {
        val dialog = BottomSheetDialog(this)
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 36)
            setBackgroundColor(Color.WHITE)
        }

        val tvTitle = TextView(this).apply {
            text = "Visi & Misi Paslon No. ${candidate.candidateNumber}"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
        }

        val tvNames = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
            text = "${candidate.chairmanName} & ${candidate.viceChairmanName}"
            textSize = 12f
            setTextColor(Color.parseColor(theme.first))
            typeface = Typeface.DEFAULT_BOLD
        }

        val tvVisionHeading = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            text = "VISI:"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#475569"))
        }

        val tvVisionBody = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
            text = candidate.vision
            textSize = 13f
            setTextColor(Color.parseColor("#1E293B"))
            setLineSpacing(4f, 1.1f)
        }

        val tvMissionHeading = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            text = "PROGRAM KERJA & MISI:"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#475569"))
        }

        view.addView(tvTitle)
        view.addView(tvNames)
        view.addView(tvVisionHeading)
        view.addView(tvVisionBody)
        view.addView(tvMissionHeading)

        val missions = candidate.mission ?: emptyList()
        if (missions.isNotEmpty()) {
            missions.forEachIndexed { i, m ->
                val tvItem = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 6 }
                    text = "${i + 1}. $m"
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#334155"))
                }
                view.addView(tvItem)
            }
        } else {
            val tvEmptyMission = TextView(this).apply {
                text = "Misi belum ditambahkan."
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
            }
            view.addView(tvEmptyMission)
        }

        val btnClose = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                90
            ).apply { topMargin = 28 }
            text = "Tutup"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0F172A"))
            setOnClickListener { dialog.dismiss() }
        }
        view.addView(btnClose)

        dialog.setContentView(view)
        dialog.show()
    }

    /**
     * 4. Fitur Khusus Pengurus Kelas: Monitoring DPT & Partisipasi Kelas
     */
    private fun checkClassOfficerFeature() {
        // Cek jika siswa adalah Pengurus Kelas / Ketua Kelas
        val isOfficer = sessionManager.isClassOfficer()
        val userRole = sessionManager.getClassRole()

        if (isOfficer) {
            binding.cardClassOfficerMonitoring.visibility = View.VISIBLE
            binding.tvOfficerBadge.text = "PENGURUS KELAS (${userRole.uppercase()})"
            loadClassTurnout()
        } else {
            binding.cardClassOfficerMonitoring.visibility = View.GONE
        }
    }

    private fun loadClassTurnout() {
        val api = ApiClient.getClient(this)
        api.getClassTurnout().enqueue(object : Callback<ClassTurnoutResponse> {
            override fun onResponse(call: Call<ClassTurnoutResponse>, response: Response<ClassTurnoutResponse>) {
                val data = response.body()?.data ?: return

                binding.tvOfficerClassTitle.text = "DPT Kelas ${data.className}"
                binding.tvOfficerTurnoutPct.text = "${data.turnoutPercentage}%"
                binding.pbClassTurnout.progress = data.turnoutPercentage.toInt()
                binding.tvOfficerClassStats.text = "Suara: ${data.votedCount} / ${data.totalDpt} Siswa (${data.unvotedCount} belum)"

                binding.btnViewClassmates.setOnClickListener {
                    showClassmatesMonitoringDialog(data)
                }
            }

            override fun onFailure(call: Call<ClassTurnoutResponse>, t: Throwable) {
                binding.tvOfficerClassStats.text = "Gagal memuat DPT kelas"
            }
        })
    }

    /**
     * Dialog Daftar Siswa di Kelas (Siapa Sudah / Belum Memilih)
     * Menjaga Asas LUBER JURDIL: Pilihan paslon tidak ditampilkan
     */
    private fun showClassmatesMonitoringDialog(data: ClassTurnoutData) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            setBackgroundColor(Color.WHITE)
        }

        val tvTitle = TextView(this).apply {
            text = "📋 Monitoring DPT Kelas ${data.className}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0F172A"))
        }

        val tvSub = TextView(this).apply {
            text = "Total: ${data.totalDpt} Siswa • Sudah: ${data.votedCount} • Belum: ${data.unvotedCount}"
            textSize = 11.5f
            setTextColor(Color.parseColor("#4F46E5"))
            typeface = Typeface.DEFAULT_BOLD
        }

        val tvNote = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6; bottomMargin = 14 }
            text = "Asas Rahasia: Hanya status kehadiran yang ditampilkan untuk membantu pengurus kelas mengingatkan teman."
            textSize = 10f
            setTextColor(Color.parseColor("#64748B"))
        }

        container.addView(tvTitle)
        container.addView(tvSub)
        container.addView(tvNote)

        val students = data.students ?: emptyList()
        // Urutkan yang belum memilih di atas agar mudah dicek
        val sortedStudents = students.sortedBy { it.hasVoted }

        sortedStudents.forEach { s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8; bottomMargin = 8 }
                setPadding(12, 10, 12, 10)
                setBackgroundColor(Color.parseColor(if (s.hasVoted) "#F0FDF4" else "#FFFBEB"))
            }

            val colName = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val tvName = TextView(this).apply {
                text = s.name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0F172A"))
            }

            val tvNisn = TextView(this).apply {
                text = "NISN: ${s.nisn}"
                textSize = 10f
                setTextColor(Color.parseColor("#64748B"))
            }

            colName.addView(tvName)
            colName.addView(tvNisn)

            val badge = TextView(this).apply {
                text = if (s.hasVoted) "✓ SUDAH" else "BELUM"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(if (s.hasVoted) "#16A34A" else "#D97706"))
                setPadding(12, 6, 12, 6)
            }

            row.addView(colName)
            row.addView(badge)
            container.addView(row)
        }

        val btnClose = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                88
            ).apply { topMargin = 20 }
            text = "Tutup"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0F172A"))
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(btnClose)

        scroll.addView(container)
        dialog.setContentView(scroll)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.85).toInt()
        )
        dialog.show()
    }

    /**
     * 5. Pop-up Konfirmasi Ganda Sebelum Suara Dikirim
     */
    private fun showDoubleConfirmationDialog(candidate: CandidateDto) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }

        val tvPrompt = TextView(this).apply {
            text = "Apakah Anda YAKIN ingin memberikan suara sah kepada:"
            textSize = 13.5f
            setTextColor(Color.parseColor("#334155"))
        }

        val tvCandidate = TextView(this).apply {
            text = "PASLON NO. ${candidate.candidateNumber}\n${candidate.chairmanName} & ${candidate.viceChairmanName}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4F46E5"))
            setPadding(0, 12, 0, 12)
        }

        val tvWarning = TextView(this).apply {
            text = "⚠️ PERINGATAN INTEGRITAS:\nPilihan Anda bersifat FINAL, RAHASIA (LUBER JURDIL), dan tidak dapat diubah setelah tombol kirim ditekan."
            textSize = 11f
            setTextColor(Color.parseColor("#DC2626"))
        }

        dialogView.addView(tvPrompt)
        dialogView.addView(tvCandidate)
        dialogView.addView(tvWarning)

        AlertDialog.Builder(this)
            .setTitle("Konfirmasi Pemilihan e-Voting")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("YA, SAYA YAKIN") { _, _ ->
                submitVoteToBackend(candidate)
            }
            .setNegativeButton("BATAL", null)
            .show()
    }

    /**
     * 6. Submit Suara ke Backend dengan X-Device-Id & SHA-256 Receipt
     */
    private fun submitVoteToBackend(candidate: CandidateDto) {
        if (isSubmittingVote) return
        isSubmittingVote = true

        binding.layoutLoading.visibility = View.VISIBLE
        binding.tvLoadingMessage.text = "Mengirim surat suara ke bilik suara anonim..."
        binding.btnSubmitVote.isEnabled = false

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
        val api = ApiClient.getClient(this)

        api.castVote(deviceId, VoteRequest(candidate.id)).enqueue(object : Callback<VoteResponse> {
            override fun onResponse(call: Call<VoteResponse>, response: Response<VoteResponse>) {
                binding.layoutLoading.visibility = View.GONE
                isSubmittingVote = false
                binding.btnSubmitVote.isEnabled = true

                if (response.isSuccessful && response.body()?.success == true) {
                    val receiptData = response.body()?.data
                    val receiptToken = receiptData?.receiptToken ?: "EVT-SAH-${System.currentTimeMillis()}"

                    sessionManager.saveEvotingReceipt(receiptToken)
                    hasAlreadyVoted = true

                    showSuccessReceiptDialog(receiptToken, candidate)
                } else if (response.code() == 409) {
                    showErrorDialog("Sudah Memilih", "Sistem mencatat Anda sudah pernah menggunakan hak suara sebelumnya.")
                    loadVoteStatusAndCandidates()
                } else if (response.code() == 403) {
                    val msg = response.body()?.message ?: "Sesi pemungutan suara telah ditutup."
                    showErrorDialog("Pemilihan Ditutup", msg)
                } else {
                    val msg = response.body()?.message ?: "Gagal mengirim suara (${response.code()})"
                    showErrorDialog("Gagal Memilih", msg)
                }
            }

            override fun onFailure(call: Call<VoteResponse>, t: Throwable) {
                binding.layoutLoading.visibility = View.GONE
                isSubmittingVote = false
                binding.btnSubmitVote.isEnabled = true
                showErrorDialog("Kesalahan Koneksi", "Gagal menghubungi server e-Voting: ${t.message}")
            }
        })
    }

    /**
     * 7. Tanda Terima Digital Resmi (Digital Receipt Modal)
     */
    private fun showSuccessReceiptDialog(receiptToken: String, candidate: CandidateDto) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 36, 40, 36)
            setBackgroundColor(Color.WHITE)
        }

        val ivCheck = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120)
            setImageResource(R.drawable.ic_attendance)
            setColorFilter(Color.parseColor("#16A34A"))
        }

        val tvTitle = TextView(this).apply {
            text = "Suara Berhasil Terkirim!"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#15803D"))
            setPadding(0, 16, 0, 4)
        }

        val tvSubtitle = TextView(this).apply {
            text = "Terima kasih telah menyalurkan aspirasi secara LUBER JURDIL."
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
        }

        val receiptBox = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            setPadding(24, 16, 24, 16)
        }

        val tvReceiptLabel = TextView(this).apply {
            text = "KODE TANDA TERIMA DIGITAL (BUKTI SAH):"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#475569"))
        }

        val tvToken = TextView(this).apply {
            text = receiptToken
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 6, 0, 0)
        }

        receiptBox.addView(tvReceiptLabel)
        receiptBox.addView(tvToken)

        val btnClose = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                96
            ).apply { topMargin = 28 }
            text = "SELESAI & KEMBALI"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4F46E5"))
            setOnClickListener {
                dialog.dismiss()
                loadVoteStatusAndCandidates()
                loadClassTurnout()
            }
        }

        dialogView.addView(ivCheck)
        dialogView.addView(tvTitle)
        dialogView.addView(tvSubtitle)
        dialogView.addView(receiptBox)
        dialogView.addView(btnClose)

        dialog.setContentView(dialogView)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Mengerti", null)
            .show()
    }
}
