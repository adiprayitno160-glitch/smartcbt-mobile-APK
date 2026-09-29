package com.school.smartcbt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.school.smartcbt.databinding.ActivityExamBinding
import com.school.smartcbt.utils.SessionManager
import com.school.smartcbt.data.model.*
import com.school.smartcbt.data.remote.ApiClient
import com.school.smartcbt.data.local.AppDatabase
import com.school.smartcbt.data.local.QuestionEntity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.ImageView
import android.widget.SeekBar
import android.graphics.BitmapFactory
import java.net.URL

enum class QuestionType {
    MULTIPLE_CHOICE, MATCHING, ESSAY
}

data class QuestionModel(
    val id: String,
    val text: String,
    val type: QuestionType = QuestionType.MULTIPLE_CHOICE,
    val options: List<String> = emptyList(), // OPSI A, B, C, D (4 Pilihan)
    val imageUrl: String? = null,
    val audioUrl: String? = null
)

class ExamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamBinding
    private lateinit var sessionManager: SessionManager
    private var strikeCount = 0
    private var lastStrikeTime = 0L
    private var activeExamToken: String? = null
    private var countDownTimer: CountDownTimer? = null
    private var currentQuestionIndex = 0
    private var studentExamId: String? = null
    private var examId: String? = null
    private var examDurationMinutes: Int = 90

    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            if (studentAnswers.isNotEmpty()) {
                submitExamAnswers(isFinished = false)
            }
            autoSaveHandler.postDelayed(this, 30_000)
        }
    }
    private var essayTextWatcher: TextWatcher? = null
    private val essayDebounceHandler = Handler(Looper.getMainLooper())
    private var essayDebounceRunnable: Runnable? = null

    // Audio Player untuk Soal Listening
    private var mediaPlayer: MediaPlayer? = null
    private var isAudioPlaying = false
    private val audioHandler = Handler(Looper.getMainLooper())
    private var audioProgressRunnable: Runnable? = null

    // DAFTAR SOAL UJIAN CBT
    private val questionsList: MutableList<QuestionModel> = mutableListOf()

    companion object {
        private fun generateExamQuestions(): List<QuestionModel> {
            val list = mutableListOf<QuestionModel>()
            val sampleQuestions = listOf(
                "Berapakah hasil dari 15 x 12 - 50?" to listOf("120", "130", "140", "150"),
                "Sebuah persegi memiliki keliling 48 cm. Berapakah luas persegi tersebut?" to listOf("124 cm²", "144 cm²", "169 cm²", "196 cm²"),
                "Jika nilai x = 4 dan y = 3, maka nilai dari 3x² + 2y - 10 adalah..." to listOf("44", "48", "52", "56"),
                "Panjang hipotenusa segitiga siku-siku dengan alas 6 cm dan tinggi 8 cm adalah..." to listOf("9 cm", "10 cm", "12 cm", "14 cm"),
                "Di antara pecahan berikut, manakah yang memiliki nilai paling besar?" to listOf("3/4", "5/8", "7/10", "2/3"),
                "Berapakah rata-rata (mean) dari data nilai: 70, 80, 85, 90, 75?" to listOf("78", "80", "82", "84"),
                "Sebuah lingkaran memiliki jari-jari 14 cm. Berapakah luas lingkaran tersebut? (π = 22/7)" to listOf("528 cm²", "616 cm²", "628 cm²", "716 cm²"),
                "Berapakah FPB (Faktor Persekutuan Terbesar) dari 24 dan 36?" to listOf("6", "8", "12", "18")
            )

            for (i in 1..50) {
                if (i == 3) {
                    list.add(
                        QuestionModel(
                            "" + i,
                            "Jodohkan istilah statistika berikut dengan definisinya yang tepat:\n(Pilih pasangan nomor dan huruf yang benar)",
                            QuestionType.MATCHING,
                            listOf(
                                "1-A, 2-B, 3-C (Modus-Terbanyak, Median-Tengah, Mean-Rata2)",
                                "1-B, 2-A, 3-C (Modus-Rata2, Median-Terbanyak, Mean-Tengah)",
                                "1-C, 2-B, 3-A (Modus-Tengah, Median-Terbanyak, Mean-Rata2)",
                                "1-A, 2-C, 3-B (Modus-Terbanyak, Median-Rata2, Mean-Tengah)"
                            )
                        )
                    )
                } else if (i == 50) {
                    list.add(
                        QuestionModel(
                            "" + i,
                            "Jelaskan langkah-langkah menghitung volume tabung dengan diameter alas 14 cm dan tinggi 20 cm! (Tuliskan rumus dan penjabarannya secara runtut)",
                            QuestionType.ESSAY
                        )
                    )
                } else {
                    val sample = sampleQuestions[(i - 1) % sampleQuestions.size]
                    list.add(
                        QuestionModel(
                            "" + i,
                            "Soal Nomor " + i + ": " + sample.first,
                            QuestionType.MULTIPLE_CHOICE,
                            sample.second
                        )
                    )
                }
            }
            return list
        }
    }

    private val studentAnswers = mutableMapOf<Int, String>()
    private val doubtStatus = mutableMapOf<Int, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. EXAMBROWSER SECURITY: FLAG_SECURE (Blokir Screenshot, Screen Recording & Cast)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // 2. EXAMBROWSER ENTERPRISE SECURITY: Blokir Floating Overlays & Chat Bubbles (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setHideOverlayWindows(true)
            } catch (e: Exception) {}
        }

        binding = ActivityExamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        examId = intent.getStringExtra("EXAM_ID")
        examDurationMinutes = intent.getIntExtra("EXAM_DURATION", 90)
        val token = intent.getStringExtra("EXAM_TOKEN") ?: ""
        activeExamToken = token

        applyKioskImmersiveMode()
        applyAntiPasteSecurity()
        setupExamHeader()
        setupQuestionNavigation()
        setupBackBlocker()

        fetchExamQuestions(examId, token)
    }

    private fun fetchExamQuestions(eId: String?, token: String) {
        binding.tvQuestionText.text = "Sedang memeriksa naskah soal lokal..."

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@ExamActivity)
            val cachedQuestions = if (!eId.isNullOrEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        db.cbtDao().getQuestionsByExamId(eId)
                    } catch (e: Exception) {
                        emptyList<QuestionEntity>()
                    }
                }
            } else emptyList()

            if (cachedQuestions.isNotEmpty()) {
                val gson = Gson()
                val listType = object : TypeToken<List<String>>() {}.type
                questionsList.clear()

                // Pulihkan studentExamId dari SharedPreferences lokal
                if (studentExamId.isNullOrEmpty() && !eId.isNullOrEmpty()) {
                    val prefs = getSharedPreferences("CbtExamPrefs", Context.MODE_PRIVATE)
                    studentExamId = prefs.getString("cbt_student_exam_id_$eId", null)
                }

                // Jika masih belum ada studentExamId, minta sinkronisasi ke server di background
                if (studentExamId.isNullOrEmpty() && !eId.isNullOrEmpty()) {
                    val req = DownloadQuestionsRequest(examId = eId, token = token)
                    ApiClient.getClient(this@ExamActivity).downloadCbtQuestions(req).enqueue(object : Callback<DownloadQuestionsResponse> {
                        override fun onResponse(call: Call<DownloadQuestionsResponse>, response: Response<DownloadQuestionsResponse>) {
                            response.body()?.studentExamId?.let { sId ->
                                studentExamId = sId
                                getSharedPreferences("CbtExamPrefs", Context.MODE_PRIVATE).edit()
                                    .putString("cbt_student_exam_id_$eId", sId)
                                    .apply()
                            }
                        }
                        override fun onFailure(call: Call<DownloadQuestionsResponse>, t: Throwable) {}
                    })
                }

                cachedQuestions.forEach { cq ->
                    val opts: List<String> = try {
                        gson.fromJson(cq.options, listType) ?: emptyList()
                    } catch (e: Exception) { emptyList() }

                    val qType = when (cq.type.uppercase()) {
                        "MATCHING" -> QuestionType.MATCHING
                        "ESSAY" -> QuestionType.ESSAY
                        else -> QuestionType.MULTIPLE_CHOICE
                    }

                    questionsList.add(
                        QuestionModel(
                            id = cq.id,
                            text = cq.content,
                            type = qType,
                            options = opts,
                            imageUrl = cq.imageUrl,
                            audioUrl = cq.audioUrl
                        )
                    )

                    if (!cq.selectedAnswer.isNullOrBlank()) {
                        studentAnswers[cq.orderNum] = cq.selectedAnswer!!
                    }
                }

                Toast.makeText(this@ExamActivity, "⚡ Memuat ${questionsList.size} butir soal dari database offline lokal (Room DB)!", Toast.LENGTH_SHORT).show()
                setupExamTimer(examDurationMinutes)
                currentQuestionIndex = 0
                renderQuestion(currentQuestionIndex)
                updateUnbkCounters()
                return@launch
            }

            // Jika belum ada di penyimpanan lokal, unduh dari Server
            binding.tvQuestionText.text = "Mengunduh naskah soal resmi dari Server Proktor..."
            val req = DownloadQuestionsRequest(examId = eId, token = token)
            ApiClient.getClient(this@ExamActivity).downloadCbtQuestions(req).enqueue(object : Callback<DownloadQuestionsResponse> {
                override fun onResponse(call: Call<DownloadQuestionsResponse>, response: Response<DownloadQuestionsResponse>) {
                    val body = response.body()
                    if (response.isSuccessful && body?.success == true && !body.questions.isNullOrEmpty()) {
                        studentExamId = body.studentExamId
                        if (!studentExamId.isNullOrEmpty() && !eId.isNullOrEmpty()) {
                            getSharedPreferences("CbtExamPrefs", Context.MODE_PRIVATE).edit()
                                .putString("cbt_student_exam_id_$eId", studentExamId)
                                .apply()
                        }
                        val duration = body.durationMinutes ?: examDurationMinutes
                        val backendQuestions = body.questions
                        questionsList.clear()

                        val entitiesToCache = mutableListOf<QuestionEntity>()
                        val gson = Gson()

                        backendQuestions.forEachIndexed { idx, bq ->
                            val options = mutableListOf<String>()
                            bq.optionA?.let { if (it.isNotBlank()) options.add(it) }
                            bq.optionB?.let { if (it.isNotBlank()) options.add(it) }
                            bq.optionC?.let { if (it.isNotBlank()) options.add(it) }
                            bq.optionD?.let { if (it.isNotBlank()) options.add(it) }

                            val qType = when (bq.type?.uppercase()) {
                                "MATCHING" -> QuestionType.MATCHING
                                "ESSAY" -> QuestionType.ESSAY
                                else -> QuestionType.MULTIPLE_CHOICE
                            }

                            questionsList.add(
                                QuestionModel(
                                    id = bq.id,
                                    text = bq.questionText ?: "",
                                    type = qType,
                                    options = options,
                                    imageUrl = bq.imageUrl,
                                    audioUrl = bq.audioUrl
                                )
                            )

                            entitiesToCache.add(
                                QuestionEntity(
                                    id = bq.id,
                                    examId = eId ?: "online_exam",
                                    content = bq.questionText ?: "",
                                    type = bq.type ?: "MULTIPLE_CHOICE",
                                    options = gson.toJson(options),
                                    orderNum = idx,
                                    selectedAnswer = null,
                                    imageUrl = bq.imageUrl,
                                    audioUrl = bq.audioUrl
                                )
                            )
                        }

                        // Simpan ke Room DB lokal secara asynchronous
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val db = AppDatabase.getDatabase(this@ExamActivity)
                                db.cbtDao().insertQuestions(entitiesToCache)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        Toast.makeText(this@ExamActivity, "✅ Berhasil memuat & menyimpan ${questionsList.size} butir soal ke lokal!", Toast.LENGTH_SHORT).show()
                        setupExamTimer(duration)
                        currentQuestionIndex = 0
                        renderQuestion(currentQuestionIndex)
                        updateUnbkCounters()
                    } else {
                        val errMsg = body?.message ?: try {
                            val errJson = response.errorBody()?.string()
                            org.json.JSONObject(errJson ?: "").optString("message", "Token ujian salah atau belum dirilis oleh Pengawas.")
                        } catch (e: Exception) { "Token ujian salah atau belum dirilis oleh Pengawas." }

                        AlertDialog.Builder(this@ExamActivity)
                            .setTitle("⚠️ Akses Ujian Ditolak")
                            .setMessage(errMsg)
                            .setPositiveButton("Tutup") { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                }

                override fun onFailure(call: Call<DownloadQuestionsResponse>, t: Throwable) {
                    AlertDialog.Builder(this@ExamActivity)
                        .setTitle("⚠️ Koneksi Server Gagal")
                        .setMessage("Tidak dapat mengunduh naskah soal dari Server Proktor (${t.message}). Pastikan HP terhubung ke Wi-Fi sekolah.")
                        .setPositiveButton("Coba Lagi") { _, _ -> fetchExamQuestions(eId, token) }
                        .setNegativeButton("Keluar") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                }
            })
        }
    }

    private fun persistAnswerLocally(questionIndex: Int, answer: String) {
        if (answer.isNotBlank()) {
            studentAnswers[questionIndex] = answer
        } else {
            studentAnswers.remove(questionIndex)
        }
        val q = questionsList.getOrNull(questionIndex) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AppDatabase.getDatabase(this@ExamActivity).cbtDao().updateAnswer(q.id, answer)
            } catch (e: Exception) {}
        }
    }

    private fun loadFallbackQuestions() {
        Toast.makeText(this@ExamActivity, "Menggunakan bank soal cadangan lokal", Toast.LENGTH_SHORT).show()
        questionsList.clear()
        questionsList.addAll(generateExamQuestions())
        setupExamTimer(examDurationMinutes)
        currentQuestionIndex = 0
        renderQuestion(currentQuestionIndex)
        updateUnbkCounters()
    }

    private fun setupBackBlocker() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@ExamActivity, "🔒 Tombol Kembali Dinonaktifkan selama Ujian Berlangsung!", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyKioskImmersiveMode()
            clearSystemClipboard()
        }
    }

    private fun applyKioskImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun applyAntiPasteSecurity() {
        val noOpActionMode = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }

        binding.tvQuestionText.customSelectionActionModeCallback = noOpActionMode
        binding.tvQuestionText.isLongClickable = false
        binding.tvQuestionText.setTextIsSelectable(false)

        clearSystemClipboard()
    }

    private fun clearSystemClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (e: Exception) {
            // Safe fallback
        }
    }

    private fun setupExamHeader() {
        val studentName = sessionManager.getName()
        val examTitle = intent.getStringExtra("EXAM_TITLE") ?: intent.getStringExtra("EXAM_SUBJECT") ?: "Ujian CBT Siswa"
        val token = intent.getStringExtra("EXAM_TOKEN") ?: "UNBK26"

        binding.tvStudentName.text = studentName
        binding.tvExamTitle.text = examTitle
        Toast.makeText(this, "🔐 ExamBrowser Aktif (Kiosk & Anti-Cheat). Token: $token", Toast.LENGTH_SHORT).show()
    }

    private fun setupExamTimer(durationMinutes: Int = 90) {
        countDownTimer?.cancel()
        val totalMillis = durationMinutes * 60 * 1000L
        countDownTimer = object : CountDownTimer(totalMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val hours = (millisUntilFinished / 3600000)
                val minutes = (millisUntilFinished % 3600000) / 60000
                val seconds = (millisUntilFinished % 60000) / 1000
                binding.tvTimer.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
            }

            override fun onFinish() {
                binding.tvTimer.text = "00:00:00"
                Toast.makeText(this@ExamActivity, "⏰ WAKTU HABIS! Mengumpulkan lembar jawaban otomatis ke Server Proktor...", Toast.LENGTH_LONG).show()
                submitExamAnswers(isFinished = true)
            }
        }.start()
    }

    private fun updateUnbkCounters() {
        val total = questionsList.size
        val answered = studentAnswers.size
        var doubt = 0
        for (i in 0 until total) {
            if (doubtStatus[i] == true) {
                doubt++
            }
        }
        val unanswered = total - answered

        binding.tvAnsweredCount.text = "🟢 Terjawab: " + answered
        binding.tvDoubtCount.text = "🟡 Ragu: " + doubt
        binding.tvUnansweredCount.text = "⚪ Belum: " + unanswered
    }

    private fun stopListeningAudio() {
        audioProgressRunnable?.let { audioHandler.removeCallbacks(it) }
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null
        isAudioPlaying = false
        binding.btnPlayAudio.text = "▶"
    }

    private fun setupListeningPlayer(rawAudioUrl: String) {
        stopListeningAudio()
        binding.cardListeningAudio.visibility = View.VISIBLE

        val serverBase = ApiClient.getBaseServerUrl(this).trimEnd('/')
        val fullAudioUrl = if (rawAudioUrl.startsWith("http")) rawAudioUrl else "$serverBase/" + rawAudioUrl.trimStart('/')

        binding.tvAudioDuration.text = "00:00 / --:--"
        binding.seekBarAudio.progress = 0
        binding.btnPlayAudio.text = "▶"

        binding.btnPlayAudio.setOnClickListener {
            if (isAudioPlaying) {
                mediaPlayer?.pause()
                isAudioPlaying = false
                binding.btnPlayAudio.text = "▶"
            } else {
                if (mediaPlayer == null) {
                    try {
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(fullAudioUrl)
                            setOnPreparedListener { mp ->
                                val totalSec = mp.duration / 1000
                                val totalStr = String.format("%02d:%02d", totalSec / 60, totalSec % 60)
                                binding.tvAudioDuration.text = "00:00 / $totalStr"
                                binding.seekBarAudio.max = mp.duration
                                mp.start()
                                isAudioPlaying = true
                                binding.btnPlayAudio.text = "⏸"
                                startAudioProgressTracking()
                            }
                            setOnCompletionListener {
                                isAudioPlaying = false
                                binding.btnPlayAudio.text = "▶"
                                binding.seekBarAudio.progress = 0
                            }
                            setOnErrorListener { _, _, _ ->
                                Toast.makeText(this@ExamActivity, "Gagal memutar berkas audio listening", Toast.LENGTH_SHORT).show()
                                stopListeningAudio()
                                true
                            }
                            prepareAsync()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Tidak dapat memutar audio: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    mediaPlayer?.start()
                    isAudioPlaying = true
                    binding.btnPlayAudio.text = "⏸"
                    startAudioProgressTracking()
                }
            }
        }

        binding.seekBarAudio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun startAudioProgressTracking() {
        audioProgressRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        val curr = mp.currentPosition
                        val dur = mp.duration
                        binding.seekBarAudio.progress = curr
                        val curSec = curr / 1000
                        val totSec = dur / 1000
                        binding.tvAudioDuration.text = String.format("%02d:%02d / %02d:%02d", curSec / 60, curSec % 60, totSec / 60, totSec % 60)
                        audioHandler.postDelayed(this, 500)
                    }
                }
            }
        }
        audioHandler.post(audioProgressRunnable!!)
    }

    private fun renderQuestion(index: Int) {
        val q = questionsList[index]
        val typeLabel = when (q.type) {
            QuestionType.MULTIPLE_CHOICE -> "[Pilihan Ganda A-D]"
            QuestionType.MATCHING -> "[Menjodohkan]"
            QuestionType.ESSAY -> "[Uraian / Essai]"
        }

        binding.tvQuestionNumber.text = "Soal No. " + (index + 1) + " dari " + questionsList.size + " " + typeLabel
        
        // 1. Audio Listening Handler
        if (!q.audioUrl.isNullOrEmpty()) {
            setupListeningPlayer(q.audioUrl)
        } else {
            stopListeningAudio()
            binding.cardListeningAudio.visibility = View.GONE
        }

        // 2. Image Attachment Handler
        if (!q.imageUrl.isNullOrEmpty()) {
            binding.ivQuestionImage.visibility = View.VISIBLE
            val serverBase = ApiClient.getBaseServerUrl(this).trimEnd('/')
            val fullImgUrl = if (q.imageUrl.startsWith("http")) q.imageUrl else "$serverBase/" + q.imageUrl.trimStart('/')
            binding.ivQuestionImage.tag = fullImgUrl
            Thread {
                try {
                    val conn = URL(fullImgUrl).openConnection()
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val bmp = BitmapFactory.decodeStream(conn.getInputStream())
                    runOnUiThread {
                        if (binding.ivQuestionImage.tag == fullImgUrl && bmp != null) {
                            binding.ivQuestionImage.setImageBitmap(bmp)
                        }
                    }
                } catch (e: Exception) {
                    // Safe fallback
                }
            }.start()
        } else {
            binding.ivQuestionImage.visibility = View.GONE
        }

        // 3. Formula / KaTeX / LaTeX or Plain Text Question Handler
        val containsMathOrHtml = q.text.contains("\\(") || q.text.contains("$$") || q.text.contains("\\frac") || 
                                 q.text.contains("\\sqrt") || q.text.contains("<img") || q.text.contains("<p>") || 
                                 q.text.contains("^{") || q.text.contains("_{") || q.text.contains("\\times")
        if (containsMathOrHtml) {
            binding.tvQuestionText.visibility = View.GONE
            binding.wvQuestionMath.visibility = View.VISIBLE
            binding.wvQuestionMath.settings.javaScriptEnabled = true
            binding.wvQuestionMath.settings.domStorageEnabled = true
            
            val katexHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css">
                    <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js"></script>
                    <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/contrib/auto-render.min.js" onload="renderMathInElement(document.body, {delimiters: [{left: '$$', right: '$$', display: true}, {left: '\\(', right: '\\)', display: false}, {left: '$', right: '$', display: false}]});"></script>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; font-size: 16px; font-weight: bold; color: #1E293B; margin: 0; padding: 4px; line-height: 1.6; }
                        img { max-width: 100%; height: auto; border-radius: 8px; margin: 8px 0; }
                    </style>
                </head>
                <body>
                    ${q.text}
                </body>
                </html>
            """.trimIndent()
            val serverBase = ApiClient.getBaseServerUrl(this)
            binding.wvQuestionMath.loadDataWithBaseURL(serverBase, katexHtml, "text/html", "UTF-8", null)
        } else {
            binding.wvQuestionMath.visibility = View.GONE
            binding.tvQuestionText.visibility = View.VISIBLE
            binding.tvQuestionText.text = q.text
        }

        val isDoubt = doubtStatus[index] == true
        val isAnswered = studentAnswers.containsKey(index) && studentAnswers[index]!!.isNotEmpty()

        if (isDoubt) {
            binding.tvQuestionStatusBadge.text = "🟡 RAGU-RAGU"
            binding.tvQuestionStatusBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
            binding.tvQuestionStatusBadge.setTextColor(Color.parseColor("#D97706"))
            binding.btnDoubt.text = "✅ Hapus Ragu"
            binding.btnDoubt.setBackgroundColor(Color.parseColor("#10B981"))
        } else if (isAnswered) {
            binding.tvQuestionStatusBadge.text = "🟢 SUDAH DIJAWAB"
            binding.tvQuestionStatusBadge.setBackgroundColor(Color.parseColor("#D1FAE5"))
            binding.tvQuestionStatusBadge.setTextColor(Color.parseColor("#059669"))
            binding.btnDoubt.text = "🟡 Ragu-Ragu"
            binding.btnDoubt.setBackgroundColor(Color.parseColor("#F59E0B"))
        } else {
            binding.tvQuestionStatusBadge.text = "⚪ BELUM DIJAWAB"
            binding.tvQuestionStatusBadge.setBackgroundColor(Color.parseColor("#F1F5F9"))
            binding.tvQuestionStatusBadge.setTextColor(Color.parseColor("#64748B"))
            binding.btnDoubt.text = "🟡 Ragu-Ragu"
            binding.btnDoubt.setBackgroundColor(Color.parseColor("#F59E0B"))
        }

        binding.rgOptions.removeAllViews()

        // 4. Answer Input (ESSAY vs MULTIPLE CHOICE)
        if (q.type == QuestionType.ESSAY) {
            binding.layoutEssayContainer.visibility = View.VISIBLE
            binding.rgOptions.visibility = View.GONE

            val savedAns = studentAnswers[index] ?: ""
            binding.etEssayAnswer.setText(savedAns)
            val wordCount = if (savedAns.isBlank()) 0 else savedAns.trim().split("\\s+".toRegex()).size
            binding.tvEssayCharCounter.text = "$wordCount kata • ${savedAns.length} karakter"

            // Reactive Autosave TextWatcher with Debounce
            essayTextWatcher?.let { binding.etEssayAnswer.removeTextChangedListener(it) }
            essayDebounceRunnable?.let { essayDebounceHandler.removeCallbacks(it) }

            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    val typed = s?.toString() ?: ""
                    val words = if (typed.isBlank()) 0 else typed.trim().split("\\s+".toRegex()).size
                    binding.tvEssayCharCounter.text = "$words kata • ${typed.length} karakter"

                    essayDebounceRunnable?.let { essayDebounceHandler.removeCallbacks(it) }
                    essayDebounceRunnable = Runnable {
                        if (typed.isNotBlank()) {
                            persistAnswerLocally(index, typed.trim())
                            binding.tvEssayAutoSaveNotice.text = "💾 Tersimpan otomatis ke lembar ujian"
                            binding.tvEssayAutoSaveNotice.setTextColor(Color.parseColor("#10B981"))
                        } else {
                            persistAnswerLocally(index, "")
                            binding.tvEssayAutoSaveNotice.text = "⚪ Belum ada jawaban yang ditulis"
                            binding.tvEssayAutoSaveNotice.setTextColor(Color.parseColor("#94A3B8"))
                        }
                        updateUnbkCounters()
                    }
                    essayDebounceHandler.postDelayed(essayDebounceRunnable!!, 400)
                }
                override fun afterTextChanged(s: Editable?) {}
            }
            essayTextWatcher = watcher
            binding.etEssayAnswer.addTextChangedListener(watcher)
        } else {
            essayTextWatcher?.let { binding.etEssayAnswer.removeTextChangedListener(it) }
            essayDebounceRunnable?.let { essayDebounceHandler.removeCallbacks(it) }
            binding.layoutEssayContainer.visibility = View.GONE
            binding.rgOptions.visibility = View.VISIBLE

            // OPSI PG SAMPAI DENGAN D (A, B, C, D)
            val letters = listOf("A", "B", "C", "D")
            for (i in q.options.indices) {
                val rb = RadioButton(this)
                rb.id = View.generateViewId()
                rb.text = letters.getOrElse(i) { "" + (i + 1) } + ".  " + q.options[i]
                rb.textSize = 14f
                rb.setPadding(12, 16, 12, 16)
                rb.setTextColor(Color.parseColor("#1E293B"))

                if (studentAnswers[index] == i.toString() || studentAnswers[index] == letters.getOrElse(i) { "" }) {
                    rb.isChecked = true
                }

                rb.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        persistAnswerLocally(index, i.toString())
                        updateUnbkCounters()
                        renderQuestion(currentQuestionIndex)
                    }
                }
                binding.rgOptions.addView(rb)
            }
        }

        binding.btnPrev.isEnabled = index > 0
        if (index == questionsList.size - 1) {
            binding.btnNext.text = "✅ Selesai / Kumpul"
        } else {
            binding.btnNext.text = "Selanjutnya ❯"
        }
    }

    private fun setupQuestionNavigation() {
        binding.btnPrev.setOnClickListener {
            if (currentQuestionIndex > 0) {
                currentQuestionIndex--
                renderQuestion(currentQuestionIndex)
            }
        }

        binding.btnNext.setOnClickListener {
            if (currentQuestionIndex < questionsList.size - 1) {
                currentQuestionIndex++
                renderQuestion(currentQuestionIndex)
            } else {
                showSubmitConfirmDialog()
            }
        }

        binding.btnDoubt.setOnClickListener {
            val currentDoubt = doubtStatus[currentQuestionIndex] ?: false
            doubtStatus[currentQuestionIndex] = !currentDoubt
            updateUnbkCounters()
            renderQuestion(currentQuestionIndex)
        }

        binding.btnDaftarSoal.setOnClickListener {
            showUnbkQuestionGridDialog()
        }
    }

    /**
     * POPUP TOTAL SOAL STANDAR CBT / UNBK (KOMPAK & MENGAKOMODASI 50+ SOAL)
     * Desain tombol matriks diperkecil dan ditata 5 kolom presisi sehingga
     * 50 butir soal tampil rapi, padat, mudah digulir, dan responsif.
     */
    private fun showUnbkQuestionGridDialog() {
        val rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setPadding(24, 16, 24, 16)

        // 1. REKAP TOTAL SOAL (HEADER POPUP)
        val total = questionsList.size
        val answered = studentAnswers.size
        var doubt = 0
        for (i in 0 until total) {
            if (doubtStatus[i] == true) doubt++
        }
        val unanswered = total - answered

        val headerCard = CardView(this)
        headerCard.radius = 12f
        headerCard.cardElevation = 2f

        val headerLayout = LinearLayout(this)
        headerLayout.orientation = LinearLayout.VERTICAL
        headerLayout.setPadding(16, 12, 16, 12)
        headerLayout.setBackgroundColor(Color.parseColor("#F8FAFC"))

        val tvTotalHeader = TextView(this)
        tvTotalHeader.text = "📋 TOTAL SOAL UJIAN: " + total + " BUTIR"
        tvTotalHeader.setTextColor(Color.parseColor("#0F172A"))
        tvTotalHeader.textSize = 13f
        tvTotalHeader.typeface = Typeface.DEFAULT_BOLD
        headerLayout.addView(tvTotalHeader)

        val statsLayout = LinearLayout(this)
        statsLayout.orientation = LinearLayout.HORIZONTAL
        statsLayout.setPadding(0, 6, 0, 0)

        val tvTerjawab = TextView(this)
        tvTerjawab.text = "🟢 " + answered + " Terjawab"
        tvTerjawab.setTextColor(Color.parseColor("#059669"))
        tvTerjawab.textSize = 11f
        tvTerjawab.typeface = Typeface.DEFAULT_BOLD
        tvTerjawab.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        statsLayout.addView(tvTerjawab)

        val tvRagu = TextView(this)
        tvRagu.text = "🟡 " + doubt + " Ragu"
        tvRagu.setTextColor(Color.parseColor("#D97706"))
        tvRagu.textSize = 11f
        tvRagu.typeface = Typeface.DEFAULT_BOLD
        tvRagu.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        statsLayout.addView(tvRagu)

        val tvBelum = TextView(this)
        tvBelum.text = "⚪ " + unanswered + " Belum"
        tvBelum.setTextColor(Color.parseColor("#64748B"))
        tvBelum.textSize = 11f
        tvBelum.typeface = Typeface.DEFAULT_BOLD
        tvBelum.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        statsLayout.addView(tvBelum)

        headerLayout.addView(statsLayout)
        headerCard.addView(headerLayout)
        rootLayout.addView(headerCard)

        // 2. SCROLLABLE GRID MATRIKS NOMOR SOAL (KOMPAK 5 KOLOM)
        val scrollView = ScrollView(this)
        scrollView.setPadding(0, 12, 0, 8)

        val grid = GridLayout(this)
        grid.columnCount = 5 // 5 Kolom per baris
        grid.alignmentMode = GridLayout.ALIGN_MARGINS

        val letters = listOf("A", "B", "C", "D")

        for (i in questionsList.indices) {
            val q = questionsList[i]
            val isAns = studentAnswers.containsKey(i) && studentAnswers[i]!!.isNotEmpty()
            val isDoubt = doubtStatus[i] == true
            val isCurrent = (i == currentQuestionIndex)

            val btn = Button(this)
            btn.textSize = 11f
            btn.typeface = Typeface.DEFAULT_BOLD
            btn.setTextColor(Color.WHITE)
            btn.gravity = Gravity.CENTER
            btn.setPadding(2, 2, 2, 2)
            btn.minHeight = 0
            btn.minimumHeight = 0
            btn.minWidth = 0
            btn.minimumWidth = 0

            val selectedOptionText = if (isAns) {
                if (q.type == QuestionType.ESSAY) {
                    " [E]"
                } else {
                    val ansIdx = studentAnswers[i]?.toIntOrNull()
                    if (ansIdx != null && ansIdx in letters.indices) {
                        " [" + letters[ansIdx] + "]"
                    } else {
                        " [✓]"
                    }
                }
            } else {
                " [-]"
            }

            btn.text = "" + (i + 1) + selectedOptionText

            if (isDoubt) {
                btn.setBackgroundColor(Color.parseColor("#F59E0B")) // Kuning Ragu
                btn.text = "" + (i + 1) + selectedOptionText + "?"
            } else if (isAns) {
                btn.setBackgroundColor(Color.parseColor("#10B981")) // Hijau Terjawab
            } else {
                btn.setBackgroundColor(Color.parseColor("#94A3B8")) // Abu-abu Belum
                btn.setTextColor(Color.parseColor("#FFFFFF"))
            }

            if (isCurrent) {
                // Highlight soal yang sedang aktif
                btn.setTextColor(Color.parseColor("#FEF08A"))
            }

            val params = GridLayout.LayoutParams()
            params.width = 135
            params.height = 100
            params.setMargins(6, 6, 6, 6)
            btn.layoutParams = params

            btn.setOnClickListener {
                currentQuestionIndex = i
                renderQuestion(currentQuestionIndex)
                (btn.tag as? AlertDialog)?.dismiss()
            }
            grid.addView(btn)
        }

        scrollView.addView(grid)
        rootLayout.addView(scrollView)

        val dialog = AlertDialog.Builder(this)
            .setTitle("📑 MATRIKS 50 SOAL CBT")
            .setView(rootLayout)
            .setNegativeButton("Tutup", null)
            .create()

        for (i in 0 until grid.childCount) {
            grid.getChildAt(i).tag = dialog
        }
        dialog.show()
    }

    private fun showSubmitConfirmDialog() {
        val total = questionsList.size
        val answered = studentAnswers.size
        val unanswered = total - answered

        val warningMsg = if (unanswered > 0) {
            "⚠️ PERHATIAN: Masih ada $unanswered soal yang BELUM dijawab.\nApakah Anda yakin ingin menyelesaikan ujian sekarang?"
        } else {
            "Semua $total butir soal telah terjawab dengan lengkap.\nApakah Anda yakin ingin mengirim lembar jawaban ke server proktor?"
        }

        AlertDialog.Builder(this)
            .setTitle("📤 Konfirmasi Selesai Ujian")
            .setMessage(warningMsg)
            .setPositiveButton("Ya, Kumpulkan Jawaban") { _, _ ->
                submitExamAnswers(isFinished = true)
            }
            .setNegativeButton("Cek Kembali", null)
            .show()
    }

    private fun submitExamAnswers(isFinished: Boolean) {
        val letters = listOf("A", "B", "C", "D")
        val answersMap = mutableMapOf<String, String>()

        for (i in questionsList.indices) {
            val q = questionsList[i]
            val ans = studentAnswers[i]
            if (!ans.isNullOrEmpty()) {
                val ansVal = if (q.type == QuestionType.ESSAY) {
                    ans
                } else {
                    val idx = ans.toIntOrNull()
                    if (idx != null && idx in letters.indices) letters[idx] else ans
                }
                answersMap[q.id] = ansVal
            }
        }

        val jsonStr = org.json.JSONObject(answersMap as Map<*, *>).toString()
        var sExamId = studentExamId
        if (sExamId == null && examId != null) {
            val prefs = getSharedPreferences("CbtExamPrefs", Context.MODE_PRIVATE)
            sExamId = prefs.getString("cbt_student_exam_id_$examId", null)
            if (sExamId != null) {
                studentExamId = sExamId
            }
        }

        if (sExamId != null) {
            val req = SyncExamRequest(
                studentExamId = sExamId,
                answersJson = jsonStr,
                strikeCount = strikeCount,
                isFinished = isFinished
            )
            ApiClient.getClient(this).syncExamAnswers(req).enqueue(object : Callback<SyncExamResponse> {
                override fun onResponse(call: Call<SyncExamResponse>, response: Response<SyncExamResponse>) {
                    if (isFinished) {
                        if (response.isSuccessful && response.body()?.success == true) {
                            val score = response.body()?.score
                            val scoreMsg = if (score != null) "\nSkor Sementara: $score" else ""
                            AlertDialog.Builder(this@ExamActivity)
                                .setTitle("✅ Ujian Selesai")
                                .setMessage("Lembar jawaban berhasil diterima Meja Proktor!$scoreMsg")
                                .setPositiveButton("Kembali ke Beranda") { _, _ -> finish() }
                                .setCancelable(false)
                                .show()
                        } else {
                            val errMsg = response.body()?.message ?: "Gagal memproses lembar jawaban di server proktor."
                            Toast.makeText(this@ExamActivity, "⚠️ $errMsg", Toast.LENGTH_LONG).show()
                            showSubmissionRetryDialog()
                        }
                    }
                }

                override fun onFailure(call: Call<SyncExamResponse>, t: Throwable) {
                    if (isFinished) {
                        Toast.makeText(this@ExamActivity, "Koneksi terputus: ${t.message}", Toast.LENGTH_SHORT).show()
                        showSubmissionRetryDialog()
                    }
                }
            })
        } else {
            if (isFinished) {
                AlertDialog.Builder(this)
                    .setTitle("✅ Ujian Selesai")
                    .setMessage("Lembar jawaban telah tersimpan di memori perangkat Anda.")
                    .setPositiveButton("Kembali ke Beranda") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun showSubmissionRetryDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Gagal Mengumpulkan Lembar Jawaban")
            .setMessage("Koneksi ke Server Proktor terputus saat pengiriman lembar jawaban final. Seluruh jawaban Anda telah aman tersimpan di memori perangkat. Silakan periksa koneksi Wi-Fi ujian dan tekan 'Kirim Ulang'.")
            .setPositiveButton("Kirim Ulang") { _, _ ->
                submitExamAnswers(isFinished = true)
            }
            .setNegativeButton("Tutup", null)
            .setCancelable(false)
            .show()
    }

    private var hasShownWarningDialog = false

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (isInMultiWindowMode) {
            val now = System.currentTimeMillis()
            if (now - lastStrikeTime > 2000) {
                lastStrikeTime = now
                strikeCount++
                reportCheatStrikeToServer("Terdeteksi Membuka Layar Terbagi (Split-Screen / Multi-Window)")
            }
            showCheatWarningModal()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Terpicu instan saat siswa menekan tombol Home atau Recent Apps
        val now = System.currentTimeMillis()
        if (now - lastStrikeTime > 3000) {
            lastStrikeTime = now
            strikeCount++
            reportCheatStrikeToServer("Mencoba menekan tombol Home / Beralih Aplikasi")
        }
    }

    override fun onPause() {
        super.onPause()
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        val now = System.currentTimeMillis()
        if (now - lastStrikeTime > 3000) {
            lastStrikeTime = now
            strikeCount++
            reportCheatStrikeToServer("Layar ujian kehilangan fokus / Aplikasi beralih")
        }
    }

    override fun onResume() {
        super.onResume()
        applyKioskImmersiveMode()

        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        autoSaveHandler.postDelayed(autoSaveRunnable, 30_000)

        if (strikeCount > 0) {
            showCheatWarningModal()
        }
    }

    private fun showCheatWarningModal() {
        if (isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)) return

        try {
            val density = resources.displayMetrics.density
            val isLocked = strikeCount >= 3

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setBackgroundColor(Color.WHITE)
                val pad = (20 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            // Header Icon & Title Card
            val headerCard = CardView(this).apply {
                radius = 16 * density
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor(if (isLocked) "#FEF2F2" else "#FFFBEB"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (14 * density).toInt()
                }
            }

            val headerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                val padH = (16 * density).toInt()
                setPadding(padH, padH, padH, padH)
            }

            val tvIcon = TextView(this).apply {
                text = if (isLocked) "🚨" else "⚠️"
                textSize = 36f
                gravity = android.view.Gravity.CENTER
            }
            headerLayout.addView(tvIcon)

            val tvTitle = TextView(this).apply {
                text = if (isLocked) "UJIAN DIKUNCI (STRIKE MAKSIMAL)" else "PERINGATAN INTEGRITAS CBT"
                textSize = 15.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (isLocked) "#991B1B" else "#92400E"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt())
            }
            headerLayout.addView(tvTitle)

            val tvBadge = TextView(this).apply {
                text = "Peringatan Pelanggaran: Strike $strikeCount / 3"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (isLocked) "#B91C1C" else "#D97706"))
                setBackgroundColor(Color.parseColor(if (isLocked) "#FEE2E2" else "#FEF3C7"))
                val h = (10 * density).toInt()
                val v = (4 * density).toInt()
                setPadding(h, v, h, v)
                gravity = android.view.Gravity.CENTER
            }
            headerLayout.addView(tvBadge)

            headerCard.addView(headerLayout)
            root.addView(headerCard)

            // Message Body
            val tvMsg = TextView(this).apply {
                text = if (isLocked) {
                    "Anda telah meninggalkan layar ujian sebanyak $strikeCount kali. Lembar ujian Anda telah terkunci secara otomatis demi integritas CBT.\n\nHarap hubungi Proktor / Pengawas Ruangan untuk memasukkan Token Buka Kunci agar dapat melanjutkan ujian."
                } else {
                    "Anda terdeteksi meninggalkan/beralih dari layar ujian! Aktivitas ini dicatat otomatis oleh sistem pengawas (Proktor).\n\nJika mencapai 3x peringatan, ujian akan dikunci secara otomatis."
                }
                textSize = 12.5f
                setTextColor(Color.parseColor("#334155"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (14 * density).toInt())
            }
            root.addView(tvMsg)

            // Input Token Proktor Section
            val tokenCard = CardView(this).apply {
                radius = 12 * density
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor("#F8FAFC"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (16 * density).toInt()
                }
            }

            val tokenLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (12 * density).toInt()
                setPadding(p, p, p, p)
            }

            val tvTokenLabel = TextView(this).apply {
                text = "🔑 Token Buka Kunci Proktor:"
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E293B"))
            }
            tokenLayout.addView(tvTokenLabel)

            val etToken = EditText(this).apply {
                hint = "Masukkan Token Proktor (Contoh: 6 Digit)"
                textSize = 13f
                setSingleLine()
                setBackgroundColor(Color.WHITE)
                setTextColor(Color.parseColor("#0F172A"))
                val pE = (10 * density).toInt()
                setPadding(pE, pE, pE, pE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (6 * density).toInt()
                }
            }
            tokenLayout.addView(etToken)
            tokenCard.addView(tokenLayout)
            root.addView(tokenCard)

            var dialog: AlertDialog? = null

            // Button 1: Buka Kunci via Token
            val btnUnlockToken = Button(this).apply {
                text = "⚡ Buka Kunci dengan Token"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2563EB"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (46 * density).toInt()
                )
                setOnClickListener {
                    val input = etToken.text.toString().trim()
                    if (input.isEmpty()) {
                        Toast.makeText(this@ExamActivity, "Harap isi Token Proktor terlebih dahulu", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val validToken = activeExamToken?.trim()
                    if (!validToken.isNullOrBlank() && input.equals(validToken, ignoreCase = true)) {
                        strikeCount = 0
                        Toast.makeText(this@ExamActivity, "✅ Kunci Ujian Berhasil Dibuka oleh Proktor!", Toast.LENGTH_LONG).show()
                        dialog?.dismiss()
                    } else {
                        Toast.makeText(this@ExamActivity, "❌ Token Proktor Tidak Valid!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            root.addView(btnUnlockToken)

            // Button 2: Secondary Action (Lanjutkan / Kumpulkan)
            val btnSecondary = Button(this).apply {
                text = if (isLocked) "📥 Kumpulkan Ujian & Keluar" else "Paham & Kembali ke Ujian"
                textSize = 12f
                setTextColor(Color.parseColor(if (isLocked) "#B91C1C" else "#475569"))
                setBackgroundColor(Color.parseColor(if (isLocked) "#FEE2E2" else "#F1F5F9"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (42 * density).toInt()
                ).apply {
                    topMargin = (8 * density).toInt()
                }
                setOnClickListener {
                    if (isLocked) {
                        dialog?.dismiss()
                        submitExamAnswers(isFinished = true)
                    } else {
                        dialog?.dismiss()
                    }
                }
            }
            root.addView(btnSecondary)

            val scroll = ScrollView(this).apply { addView(root) }

            dialog = AlertDialog.Builder(this)
                .setView(scroll)
                .setCancelable(false)
                .create()

            dialog.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun reportCheatStrikeToServer(reason: String) {
        val sExamId = studentExamId
        if (sExamId != null) {
            ApiClient.getClient(this).reportCheatStrike(CheatStrikeRequest(sExamId)).enqueue(object : Callback<CheatStrikeResponse> {
                override fun onResponse(call: Call<CheatStrikeResponse>, response: Response<CheatStrikeResponse>) {}
                override fun onFailure(call: Call<CheatStrikeResponse>, t: Throwable) {}
            })
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        stopListeningAudio()
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        essayDebounceRunnable?.let { essayDebounceHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    /**
     * Membaca tingkat persentase baterai aktual perangkat siswa (1-100%)
     */
    fun getBatteryPercentage(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level >= 0) level else 85
        } catch (e: Exception) {
            85
        }
    }
}

